package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.rename.RenameMapping
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class DexBinaryPatchWriterTest {

    @Test
    fun lexicalBoundaryRename_rebuildsSortedStringAndTypePools() {
        val originalClass = "com.example.ZClass"
        val renamedClass = "a.b"
        val input = buildDex("L${originalClass.replace('.', '/')};")

        val output = DexBinaryPatchWriter.patch(
            input,
            RenameMapping.fromForward(mapOf(originalClass to renamedClass)),
        )

        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(output),
        ) as DexBackedDexFile
        val originalDescriptor = "L${originalClass.replace('.', '/')};"
        val renamedDescriptor = "L${renamedClass.replace('.', '/')};"

        assertTrue(dexFile.classes.any { it.type == renamedDescriptor })
        assertFalse(dexFile.classes.any { it.type == originalDescriptor })

        val strings = (0 until dexFile.stringSection.size).map(dexFile.stringSection::get)
        assertTrue("string_ids must be lexically sorted", strings.zipWithNext().all { it.first <= it.second })
        assertFalse("old descriptor must not remain in string_ids", originalDescriptor in strings)

        val types = (0 until dexFile.typeSection.size).map(dexFile.typeSection::get)
        assertTrue("type_ids must be descriptor sorted", types.zipWithNext().all { it.first <= it.second })
        assertFalse("old descriptor must not remain in type_ids", originalDescriptor in types)
    }

    @Test
    fun globalMapping_collisionInAnotherDex_doesNotGetExtendedAgainPerDex() {
        val host = "com.example.app.feature.Host"
        val synthetic = "com.example.app.feature.b"
        val renamedHost = "target.pkg.Host"
        val occupiedSyntheticTarget = "target.pkg.b"
        val crossDexReferrer = "other.pkg.Referrer"

        val definitionDex = buildDex(
            classDef(descriptor(host)),
            classDef(descriptor(synthetic), descriptor(host)),
        )
        val referenceDex = buildDex(
            classDef(descriptor(occupiedSyntheticTarget)),
            classDef(descriptor(crossDexReferrer), descriptor(synthetic)),
        )
        val baseMapping = RenameMapping.fromForward(mapOf(host to renamedHost))
        val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(
            listOf(definitionDex, referenceDex),
            baseMapping,
        )
        val effectiveMapping = rewritePlan.mapping

        assertNull(
            "global extension must reject a synthetic target occupied in another dex",
            effectiveMapping.resolve(synthetic),
        )

        val rewrittenDefinitionDex = openDex(
            DexInPlaceRenameEngine.remapBytes(definitionDex, baseMapping, rewritePlan),
        )
        val rewrittenReferenceDex = openDex(
            DexInPlaceRenameEngine.remapBytes(referenceDex, baseMapping, rewritePlan),
        )

        assertTrue(rewrittenDefinitionDex.classes.any { it.type == descriptor(synthetic) })
        assertFalse(rewrittenDefinitionDex.classes.any { it.type == descriptor(occupiedSyntheticTarget) })
        assertEquals(
            descriptor(renamedHost),
            rewrittenDefinitionDex.classes
                .single { it.type == descriptor(synthetic) }
                .superclass,
        )
        assertEquals(
            descriptor(synthetic),
            rewrittenReferenceDex.classes
                .single { it.type == descriptor(crossDexReferrer) }
                .superclass,
        )
    }

    @Test
    fun globalMapping_packagePrivateHelperAcrossDexes_movesWithMappedView() {
        val view = "com.example.app.adpv.AnimatedTickerView"
        val helper = "com.example.app.adpv.AnimatedTickerDrawMetrics"
        val renamedView = "xy5.m3"
        val renamedHelper = "xy5.AnimatedTickerDrawMetrics"

        val helperDex = buildDex(
            classDefWithDirectMethods(
                descriptor(helper),
                0,
                listOf(constructor(descriptor(helper), AccessFlags.CONSTRUCTOR.value)),
            ),
        )
        val viewDex = buildDex(
            classDefWithDirectMethods(
                descriptor(view),
                AccessFlags.PUBLIC.value,
                listOf(helperFactoryMethod(descriptor(view), descriptor(helper))),
            ),
        )
        val baseMapping = RenameMapping.fromForward(mapOf(view to renamedView))

        val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(
            listOf(helperDex, viewDex),
            baseMapping,
        )
        val effectiveMapping = rewritePlan.mapping

        assertEquals(renamedHelper, effectiveMapping.resolve(helper))

        val rewrittenHelperDex = openDex(
            DexInPlaceRenameEngine.remapBytes(helperDex, baseMapping, rewritePlan),
        )
        assertTrue(rewrittenHelperDex.classes.any { it.type == descriptor(renamedHelper) })
        assertFalse(rewrittenHelperDex.classes.any { it.type == descriptor(helper) })

        val rewrittenViewDex = openDex(
            DexInPlaceRenameEngine.remapBytes(viewDex, baseMapping, rewritePlan),
        )
        val helperConstructorRef = rewrittenViewDex.classes
            .single { it.type == descriptor(renamedView) }
            .methods
            .flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .filterIsInstance<ReferenceInstruction>()
            .map { it.reference }
            .filterIsInstance<MethodReference>()
            .single { it.name == "<init>" && it.definingClass != "Ljava/lang/Object;" }
        assertEquals(descriptor(renamedHelper), helperConstructorRef.definingClass)
    }

    @Test
    fun globalMapping_sharedDispatcherAcrossTargetPackages_publicizesCrossPackageInnerClass() {
        val firstOuter = "com.example.app.collect.FirstFragment"
        val firstInner = "$firstOuter\$1"
        val secondOuter = "com.example.app.collect.SecondFragment"
        val secondInner = "$secondOuter\$1"
        val dispatcher = "com.example.app.collect.d"
        val renamedFirstOuter = "first.pkg.First"
        val renamedSecondOuter = "second.pkg.Second"
        val renamedDispatcher = "first.pkg.Dispatcher"

        val input = buildDex(
            classDef(descriptor(firstOuter)),
            classDefWithDirectMethods(descriptor(firstInner), 0, emptyList()),
            classDef(descriptor(secondOuter)),
            classDefWithDirectMethods(descriptor(secondInner), 0, emptyList()),
            classDefWithDirectMethods(
                descriptor(dispatcher),
                AccessFlags.PUBLIC.value,
                listOf(
                    dispatcherMethod(
                        descriptor(dispatcher),
                        descriptor(firstInner),
                        descriptor(secondInner),
                    ),
                ),
            ),
        )
        val baseMapping = RenameMapping.fromForward(
            mapOf(
                firstOuter to renamedFirstOuter,
                secondOuter to renamedSecondOuter,
                dispatcher to renamedDispatcher,
            ),
        )

        val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(listOf(input), baseMapping)

        assertFalse(descriptor(firstInner) in rewritePlan.publicClassDescriptors)
        assertTrue(descriptor(secondInner) in rewritePlan.publicClassDescriptors)

        val rewritten = openDex(
            DexInPlaceRenameEngine.remapBytes(input, baseMapping, rewritePlan),
        )
        val rewrittenSecondInner = rewritten.classes.single {
            it.type == descriptor("$renamedSecondOuter\$1")
        }
        assertTrue(AccessFlags.PUBLIC.isSet(rewrittenSecondInner.accessFlags))
    }

    private fun buildDex(classDescriptor: String): ByteArray {
        return buildDex(classDef(classDescriptor))
    }

    private fun buildDex(vararg classDefs: ImmutableClassDef): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        classDefs.forEach(pool::internClass)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }

    private fun classDef(
        classDescriptor: String,
        superclassDescriptor: String = "Ljava/lang/Object;",
    ): ImmutableClassDef =
        ImmutableClassDef(
            classDescriptor,
            AccessFlags.PUBLIC.value,
            superclassDescriptor,
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )

    private fun classDefWithDirectMethods(
        classDescriptor: String,
        accessFlags: Int,
        directMethods: Iterable<ImmutableMethod>,
    ): ImmutableClassDef =
        ImmutableClassDef(
            classDescriptor,
            accessFlags,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            directMethods,
            emptyList(),
        )

    private fun constructor(owner: String, accessFlags: Int): ImmutableMethod =
        ImmutableMethod(
            owner,
            "<init>",
            emptyList(),
            "V",
            accessFlags,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(
                1,
                listOf(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_DIRECT,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        ImmutableMethodReference(
                            "Ljava/lang/Object;",
                            "<init>",
                            emptyList(),
                            "V",
                        ),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                emptyList(),
                emptyList(),
            ),
        )

    private fun helperFactoryMethod(owner: String, helper: String): ImmutableMethod =
        ImmutableMethod(
            owner,
            "createMetrics",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(
                1,
                listOf(
                    ImmutableInstruction21c(
                        Opcode.NEW_INSTANCE,
                        0,
                        ImmutableTypeReference(helper),
                    ),
                    ImmutableInstruction35c(
                        Opcode.INVOKE_DIRECT,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        ImmutableMethodReference(
                            helper,
                            "<init>",
                            emptyList(),
                            "V",
                        ),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                emptyList(),
                emptyList(),
            ),
        )

    private fun dispatcherMethod(
        owner: String,
        firstTarget: String,
        secondTarget: String,
    ): ImmutableMethod =
        ImmutableMethod(
            owner,
            "dispatch",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(
                1,
                listOf(
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction21c(
                        Opcode.CHECK_CAST,
                        0,
                        ImmutableTypeReference(firstTarget),
                    ),
                    ImmutableInstruction21c(
                        Opcode.CHECK_CAST,
                        0,
                        ImmutableTypeReference(secondTarget),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                emptyList(),
                emptyList(),
            ),
        )

    private fun openDex(bytes: ByteArray): DexBackedDexFile =
        DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(bytes),
        ) as DexBackedDexFile

    private fun descriptor(dotName: String): String = "L${dotName.replace('.', '/')};"
}
