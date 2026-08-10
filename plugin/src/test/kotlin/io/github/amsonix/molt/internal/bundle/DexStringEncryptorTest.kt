package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.rename.RenameMapping
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableExceptionHandler
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableTryBlock
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class DexStringEncryptorTest {

    private val config = DexStringEncryptionConfig(
        fogDescriptor = "Lcom/example/app/shell/fog/Fog;",
        key = intArrayOf(1, 2, 3, 4),
        encryptableR8Types = setOf("Lcom/example/app/renamed/a;"),
        projectPackagePrefixes = listOf("com.example.app."),
        excludeClassPatterns = listOf("*.debug.*"),
        keepStrings = listOf(Regex("""^KEEP_""")),
    )

    @Test
    fun encrypt_fogDecrypt_roundTrip_andDeduplicating_andPerPlaintextKey() {
        val plain = "user_token_12345"
        val cipher = DexStringEncryptor.encrypt(plain, config.key)
        assertFalse("cipher must differ from plaintext", cipher == plain)
        assertEquals("fogDecrypt must restore plaintext", plain, fogDecrypt(cipher))

        assertEquals(
            "identical plaintext must produce identical ciphertext (string pool dedup)",
            DexStringEncryptor.encrypt(plain, config.key),
            DexStringEncryptor.encrypt(plain, config.key),
        )

        // 每明文独立 key：不同明文 -> 密文前缀 key 不同。
        val other = DexStringEncryptor.encrypt("another_message", config.key)
        assertTrue(
            "different plaintexts must embed different keys",
            cipher.take(4) != other.take(4),
        )
    }

    /** 镜像 Fog.d：从密文前 4 个 char 取 key，对剩余部分 XOR 还原。 */
    private fun fogDecrypt(cipher: String): String {
        val chars = cipher.toCharArray()
        if (chars.size < 4) return cipher
        val k = intArrayOf(chars[0].code and 0xFF, chars[1].code and 0xFF, chars[2].code and 0xFF, chars[3].code and 0xFF)
        val salt = (chars.size - 4) and 0xFF
        val out = CharArray(chars.size - 4)
        for (i in 4 until chars.size) {
            val keyByte = k[(i - 4) and 3]
            out[i - 4] = (chars[i].code xor keyByte xor salt xor ((i - 4) and 0xFF)).toChar()
        }
        return String(out)
    }

    @Test
    fun shouldEncrypt_skipsClassNamesDescriptorsIdentifiersPathsAndKeepList() {
        val encryptable = "visit vip page"
        assertTrue(DexStringEncryptor.shouldEncrypt(encryptable, config))

        assertFalse(DexStringEncryptor.shouldEncrypt("com.example.app.MainActivity", config))
        assertFalse(DexStringEncryptor.shouldEncrypt("Lcom/example/app/MainActivity;", config))
        assertFalse(DexStringEncryptor.shouldEncrypt("userId", config))
        assertFalse(DexStringEncryptor.shouldEncrypt("/data/data/com.example.app/cache", config))
        assertFalse(DexStringEncryptor.shouldEncrypt("KEEP_REFERENCE_TARGET", config))
        assertFalse(DexStringEncryptor.shouldEncrypt("ab", config))
        assertFalse(DexStringEncryptor.shouldEncrypt("", config))
    }

    @Test
    fun shouldEncryptClass_matchesKeptPrefixAndR8MappedTypes_butHonorsExcludes() {
        assertTrue("kept class under prefix must encrypt", DexStringEncryptor.shouldEncryptClass("Lcom/example/app/Main;", config))
        assertTrue("R8-mapped project type must encrypt", DexStringEncryptor.shouldEncryptClass("Lcom/example/app/renamed/a;", config))
        assertFalse("debug class must be excluded", DexStringEncryptor.shouldEncryptClass("Lcom/example/app/debug/Tools;", config))
        assertFalse("library class outside prefix must not encrypt", DexStringEncryptor.shouldEncryptClass("Lcom/other/lib/Thing;", config))
    }

    @Test
    fun patch_replacesEncryptableConstStringWithFogDecryptCalls() {
        val plain = "sensitive user data"
        val input = buildDex(
            methodClass(
                "com.example.app.Main",
                listOf(
                    ImmutableInstruction31c(
                        Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference(plain),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )

        val output = DexBinaryPatchWriter.patch(
            input,
            RenameMapping.fromForward(emptyMap()),
            emptySet(),
            config,
        )

        val dexFile = openDex(output)
        val strings = (0 until dexFile.stringSection.size).map(dexFile.stringSection::get)
        assertFalse("plaintext must be gone from string pool", plain in strings)

        val cipher = strings.firstOrNull { fogDecrypt(it) == plain }
        assertTrue("ciphertext must be present", cipher != null)

        val method = dexFile.classes.single().directMethods.single()
        val instructions = method.implementation!!.instructions.toList()
        val fogCall = instructions.filterIsInstance<ReferenceInstruction>()
            .firstOrNull { (it.reference as? MethodReference)?.definingClass == config.fogDescriptor }
        assertTrue("Fog.d invoke must exist", fogCall != null)
        assertTrue(
            "Fog.d name must be 'd'",
            (fogCall!!.reference as MethodReference).name == "d",
        )
    }

    @Test
    fun patch_keepsNonEncryptableStringAsJumboAndSkipsNonProjectClasses() {
        val keep = "com.example.app.MainActivity"
        val input = buildDex(
            methodClass(
                "com.example.app.Main",
                listOf(
                    ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference(keep)),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
            methodClass(
                "com.other.lib.Thing",
                listOf(
                    ImmutableInstruction31c(
                        Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference("library string"),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )

        val output = DexBinaryPatchWriter.patch(
            input,
            RenameMapping.fromForward(emptyMap()),
            emptySet(),
            config,
        )

        val dexFile = openDex(output)
        val strings = (0 until dexFile.stringSection.size).map(dexFile.stringSection::get)
        assertTrue("class-name string must remain", keep in strings)
        assertTrue("library string must remain", "library string" in strings)
        assertFalse("no Fog call for non-project classes", hasFogCall(dexFile))
    }

    private fun hasFogCall(dexFile: DexBackedDexFile): Boolean =
        dexFile.classes.flatMap { it.directMethods + it.virtualMethods }
            .mapNotNull { it.implementation }
            .flatMap { it.instructions }
            .filterIsInstance<ReferenceInstruction>()
            .any { (it.reference as? MethodReference)?.definingClass == config.fogDescriptor }

    @Test
    fun patch_remapsTryBlockAndHandlerByUnitAddresses() {
        // 指令布局让 try 边界的 unit 地址与其它指令的字节偏移同值（nop 起始 unit 3 == 首条
        // 指令字节 6、end unit 4 == 字节 8），旧实现直接用 unit 值查字节映射表会命中错误
        // 指令，start/end 错算成垃圾值（真实线上触发 "Unsigned short value out of range"）。
        // 旧偏移(字节): const-string@0x0(6B) nop@0x6(2B) "ab"@0x8(6B) return@0xe(2B)
        // 加密后 const-string 扩为 14B：新偏移 nop@0xe(14) "ab"@0x10(16) return@0x16(22)
        // try [3,4) 覆盖 nop：start 3u -> 字节 6 -> nop 新偏移 14 -> unit 7；count 保持 1；
        // handler 5u -> 字节 10 -> return-void 新偏移 22 -> unit 11。
        val input = buildDex(
            methodClass(
                "com.example.app.Main",
                listOf(
                    ImmutableInstruction31c(
                        Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference("sensitive user data"),
                    ),
                    ImmutableInstruction10x(Opcode.NOP),
                    ImmutableInstruction31c(
                        Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference("ab"),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                tryBlock = ImmutableTryBlock(3, 1, listOf(ImmutableExceptionHandler(null, 7))),
            ),
        )

        val output = DexBinaryPatchWriter.patch(
            input,
            RenameMapping.fromForward(emptyMap()),
            emptySet(),
            config,
        )

        val method = openDex(output).classes.single().directMethods.single()
        val tries = method.implementation!!.tryBlocks
        assertEquals("try block must survive encryption", 1, tries.size)
        val tryBlock = tries.single()
        // nop 指令（原 unit 3）加密后落在 unit 7；try 覆盖其 1 个 code unit；
        // catch-all handler 指向 return-void（原 unit 5 -> 新 unit 11）。
        assertEquals("try start must be remapped in code units", 7, tryBlock.startCodeAddress)
        assertEquals("try count must be preserved", 1, tryBlock.codeUnitCount)
        assertEquals(
            "handler must be remapped in code units",
            11,
            tryBlock.exceptionHandlers.single().handlerCodeAddress,
        )
    }

    private fun methodClass(
        descriptor: String,
        instructions: List<org.jf.dexlib2.iface.instruction.Instruction>,
        tryBlock: ImmutableTryBlock? = null,
    ): ImmutableClassDef =
        ImmutableClassDef(
            "L${descriptor.replace('.', '/')};",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    "L${descriptor.replace('.', '/')};",
                    "run",
                    emptyList(),
                    "V",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        1,
                        instructions,
                        listOfNotNull(tryBlock),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )

    private fun buildDex(vararg classDefs: ImmutableClassDef): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        classDefs.forEach(pool::internClass)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }

    private fun openDex(bytes: ByteArray): DexBackedDexFile =
        DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(bytes)) as DexBackedDexFile
}
