package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.mapping.R8MappingAliasExpander
import io.github.amsonix.molt.internal.rename.RenameMapping
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.DualReferenceInstruction
import org.jf.dexlib2.iface.instruction.FieldOffsetInstruction
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.InlineIndexInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.SwitchPayload
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.VariableRegisterInstruction
import org.jf.dexlib2.iface.instruction.VerificationErrorInstruction
import org.jf.dexlib2.iface.instruction.VtableIndexInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.instruction.formats.ArrayPayload
import org.jf.dexlib2.util.ReferenceUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** 组件改包引擎：同 dex 合并、DrawScope 保留、Zip 路径分离。 */
class DexComponentRenameIntegrationTest {

    @Test
    fun componentEngine_onClassesDex_preservesDrawScopeInstructionSemantics() {
        val mapping = loadComponentMapping()
        val preDex = loadDexFromApk(
            "classes.dex",
        )
        val allDex = loadAllDexFromApk()
        val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(allDex, mapping)
        val before = extractMethodInsns(preDex)
        val afterDex = DexInPlaceRenameEngine.remapBytes(preDex, mapping, rewritePlan)
        val after = extractMethodInsns(afterDex)
        assertEquals(before, after)
    }

    @Test
    fun componentEngine_onClassesDex_preservesDrawScopeMethod() {
        val mapping = loadComponentMapping()
        val preDex = loadDexFromApk(
            "classes.dex",
        )
        val allDex = loadAllDexFromApk()
        val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(allDex, mapping)
        val afterDex = DexInPlaceRenameEngine.remapBytes(preDex, mapping, rewritePlan)
        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(afterDex),
        )
        val drawScope = dexFile.classes.firstOrNull {
            it.type == "Landroidx/compose/ui/graphics/drawscope/DrawScope;"
        }
        assertTrue(drawScope != null)
        val method = drawScope?.methods?.firstOrNull { it.name == "drawRect-n-J9OG0\$default" }
        assertTrue(method?.implementation != null)
    }

    @Test
    fun componentEngine_mergedDex_containsMappedApplication() {
        val mapping = loadComponentMapping()
        val preDex = loadDexFromApk(
            "classes.dex",
        )
        val allDex = loadAllDexFromApk()
        val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(allDex, mapping)
        val outDex = DexInPlaceRenameEngine.remapBytes(preDex, mapping, rewritePlan)
        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(outDex),
        )
        val applicationOriginal = mapping.entries()
            .map { it.original }
            .firstOrNull { it.endsWith("Application") }
            ?: error("No Application entry in component mapping")
        val shellTarget = requireNotNull(mapping.resolve(applicationOriginal)) {
            "$applicationOriginal missing from component mapping"
        }
        val targetDescriptor = "L${shellTarget.replace('.', '/')};"
        val found = dexFile.classes.any { it.type == targetDescriptor }
        assertTrue("Application should remain in classes.dex as $targetDescriptor", found)
    }

    @Test
    fun componentEngine_patchedDex_mapOffsetIs4ByteAligned() {
        val mapping = loadComponentMapping()
        val preDex = loadDexFromApk(
            "classes.dex",
        )
        val allDex = loadAllDexFromApk()
        val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(allDex, mapping)
        val outDex = DexInPlaceRenameEngine.remapBytes(preDex, mapping, rewritePlan)
        val mapOff = ByteBuffer.wrap(outDex, 0x34, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals("map_off must be 4-byte aligned", 0, mapOff and 3)
        DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(outDex),
        )
    }

    @Test
    fun fullMappingZipProcessor_doesNotAddSupplementalDex() {
        val unsigned = integrationApk()
        val root = projectRoot()
        val component = DexIntegrationFixture.componentMapping(root)
        val view = DexIntegrationFixture.viewMapping(root)
        val r8Mapping = DexIntegrationFixture.r8Mapping(root)
        require(component.isFile) { "component mapping missing: ${component.path}" }
        require(view.isFile) { "view mapping missing: ${view.path}" }
        val componentMapping = R8MappingAliasExpander.expand(
            RenameMapping.fromJson(component.readText()),
            r8Mapping.takeIf { it.isFile },
        ) ?: error("component mapping expand failed")
        val viewMapping = R8MappingAliasExpander.expand(
            RenameMapping.fromJson(view.readText()),
            r8Mapping.takeIf { it.isFile },
        ) ?: error("view mapping expand failed")
        val outApk = File.createTempFile("unified-inplace", ".apk")
        ZipPostR8RenameProcessor.processZip(
            unsigned,
            outApk,
            ZipPostR8RenameProcessor.Config(
                componentMapping = componentMapping,
                viewMapping = viewMapping,
            ),
        )
        ZipFile(outApk).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .map { it.name }
                .toList()
            val preCount = ZipFile(unsigned).use { pre ->
                pre.entries().asSequence()
                    .count { it.name.matches(Regex("classes\\d*\\.dex")) }
            }
            assertFalse(
                "unified in-place rename should not add supplemental dex entries",
                dexEntries.size > preCount,
            )
        }
    }

    @Test
    fun unchangedDexFile_shouldReturnInputBytes() {
        val mapping = loadComponentMapping()
        val preDex = loadDexFromApk(
            "classes2.dex",
        )
        val out = DexInPlaceRenameEngine.remapBytes(preDex, mapping)
        assertArrayEquals(preDex, out)
    }

    private fun loadComponentMapping(): RenameMapping {
        val root = projectRoot()
        val component = DexIntegrationFixture.componentMapping(root)
        val r8Mapping = DexIntegrationFixture.r8Mapping(root)
        require(component.isFile) { "component mapping missing: ${component.path}" }
        return R8MappingAliasExpander.expand(
            RenameMapping.fromJson(component.readText()),
            r8Mapping.takeIf { it.isFile },
        ) ?: error("component mapping expand failed")
    }

    private fun loadAllDexFromApk(): List<ByteArray> {
        ZipFile(integrationApk()).use { zip ->
            return zip.entries().asSequence()
                .filter { it.name.matches(Regex("(^|.*/)classes\\d*\\.dex$")) }
                .map { zip.getInputStream(it).readBytes() }
                .toList()
        }
    }

    private fun loadDexFromApk(entry: String): ByteArray {
        val apk = integrationApk()
        ZipFile(apk).use { zip ->
            val e = requireNotNull(zip.getEntry(entry)) { "$entry missing from ${apk.path}" }
            return zip.getInputStream(e).readBytes()
        }
    }

    private fun integrationApk(): File = DexIntegrationFixture.apk(projectRoot())

    private fun projectRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        return if (cwd.name == "shell-obfuscate") cwd.parentFile.parentFile else cwd
    }

    private fun extractMethodInsns(dexBytes: ByteArray): List<String> {
        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(dexBytes),
        )
        val classDef = requireNotNull(dexFile.classes.firstOrNull {
            it.type == "Landroidx/compose/ui/graphics/drawscope/DrawScope;"
        }) { "DrawScope missing from classes.dex" }
        val method = requireNotNull(
            classDef.methods.firstOrNull { it.name == "drawRect-n-J9OG0\$default" },
        ) { "DrawScope.drawRect-n-J9OG0\$default missing" }
        val impl = requireNotNull(method.implementation) {
            "DrawScope.drawRect-n-J9OG0\$default has no implementation"
        }
        return impl.instructions.map(::instructionSemantics).toList()
    }

    private fun instructionSemantics(instruction: org.jf.dexlib2.iface.instruction.Instruction): String =
        buildString {
            append(instruction.opcode.name)
            append("|units=").append(instruction.codeUnits)
            if (instruction is OneRegisterInstruction) append("|a=").append(instruction.registerA)
            if (instruction is TwoRegisterInstruction) append("|b=").append(instruction.registerB)
            if (instruction is ThreeRegisterInstruction) append("|c=").append(instruction.registerC)
            if (instruction is FiveRegisterInstruction) {
                append("|c=").append(instruction.registerC)
                append("|d=").append(instruction.registerD)
                append("|e=").append(instruction.registerE)
                append("|f=").append(instruction.registerF)
                append("|g=").append(instruction.registerG)
            }
            if (instruction is VariableRegisterInstruction) {
                append("|count=").append(instruction.registerCount)
            }
            if (instruction is RegisterRangeInstruction) {
                append("|start=").append(instruction.startRegister)
            }
            if (instruction is WideLiteralInstruction) {
                append("|literal=").append(instruction.wideLiteral)
            }
            if (instruction is OffsetInstruction) append("|offset=").append(instruction.codeOffset)
            if (instruction is ReferenceInstruction) {
                append("|refType=").append(instruction.referenceType)
                append("|ref=").append(ReferenceUtil.getReferenceString(instruction.reference))
            }
            if (instruction is DualReferenceInstruction) {
                append("|refType2=").append(instruction.referenceType2)
                append("|ref2=").append(ReferenceUtil.getReferenceString(instruction.reference2))
            }
            if (instruction is VerificationErrorInstruction) {
                append("|verificationError=").append(instruction.verificationError)
            }
            if (instruction is FieldOffsetInstruction) {
                append("|fieldOffset=").append(instruction.fieldOffset)
            }
            if (instruction is InlineIndexInstruction) {
                append("|inlineIndex=").append(instruction.inlineIndex)
            }
            if (instruction is VtableIndexInstruction) {
                append("|vtableIndex=").append(instruction.vtableIndex)
            }
            if (instruction is ArrayPayload) {
                append("|elementWidth=").append(instruction.elementWidth)
                append("|elements=").append(instruction.arrayElements)
            }
            if (instruction is SwitchPayload) {
                append("|switch=").append(
                    instruction.switchElements.map { "${it.key}:${it.offset}" },
                )
            }
        }
}
