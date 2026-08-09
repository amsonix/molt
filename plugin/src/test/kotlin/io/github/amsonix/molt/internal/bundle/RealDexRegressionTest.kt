package io.github.amsonix.molt.internal.bundle

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.TryBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用真实线上 dex（playlet googleRelease R8 产物 classes8.dex，含触发
 * "Unsigned short value out of range: -18" 的 Lak/d;->invokeSuspend try block）
 * 验证字符串加密后 try block 仍合法。路径经 -Dmolt.real.dex= 传入。
 */
class RealDexRegressionTest {

    private val config = DexStringEncryptionConfig(
        fogDescriptor = "Lcom/shortvideo/playlet/shell/fog/Fog;",
        key = intArrayOf(0x1234, 0x5678, 0x9abc, 0xdef0),
        encryptableR8Types = setOf("Lak/d;"),
        projectPackagePrefixes = listOf("com.shortvideo.playlet.", "com.playlet."),
        excludeClassPatterns = emptyList(),
        keepStrings = emptyList(),
    )

    @Test
    fun realDex_invokeSuspend_tryBlockStaysValidAfterEncryption() {
        val path = System.getenv("MOLT_REAL_DEX") ?: System.getProperty("molt.real.dex")
        if (path.isNullOrBlank()) {
            System.err.println("SKIP: MOLT_REAL_DEX not set")
            return
        }
        val dex = org.jf.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            File(path).inputStream().buffered(),
        ) as org.jf.dexlib2.dexbacked.DexBackedDexFile
        val cls = dex.classes.single { it.type == "Lak/d;" }
        val method = (cls.directMethods + cls.virtualMethods).single { it.name == "invokeSuspend" }
        val impl = method.implementation!!

        val rewritten = DexStringEncryptor.rewriteClassStrings(cls, config)
        val outMethod = (rewritten.directMethods + rewritten.virtualMethods).single { it.name == "invokeSuspend" }
        val outImpl = outMethod.implementation!!
        println("orig try: " + impl.tryBlocks.map { "0x${Integer.toHexString(it.startCodeAddress)},${it.codeUnitCount}" })
        println("rewritten try: " + outImpl.tryBlocks.map {
            "0x${Integer.toHexString(it.startCodeAddress)},${it.codeUnitCount},h=0x${Integer.toHexString(it.exceptionHandlers.single().handlerCodeAddress)}"
        })
        for (tb in outImpl.tryBlocks) {
            assertTrue("try count must be positive, was ${tb.codeUnitCount}", tb.codeUnitCount > 0)
            assertTrue(
                "try end 0x${Integer.toHexString(tb.startCodeAddress + tb.codeUnitCount)} must not exceed method size",
                tb.startCodeAddress + tb.codeUnitCount <= outImpl.instructions.sumOf { it.codeUnits },
            )
        }
    }
}
