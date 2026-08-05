package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.mapping.R8MappingAliasExpander
import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** ART dexdump 校验：patched dex 不得出现 string size 0 异常。 */
class DexInPlaceDexdumpVerifyTest {

    @Test
    fun patchedDexFiles_passDexdumpVerify() {
        val root = IntegrationTestAssumptions.projectRoot()
        val unsigned = IntegrationTestAssumptions.assumeIntegrationApk(root)
        val component = IntegrationTestAssumptions.assumeComponentMapping(root)
        val view = IntegrationTestAssumptions.assumeViewMapping(root)
        val r8Mapping = DexIntegrationFixture.r8Mapping(root)
        val componentMapping = R8MappingAliasExpander.expand(
            RenameMapping.fromJson(component.readText()),
            r8Mapping.takeIf { it.isFile },
        )
        val viewMapping = R8MappingAliasExpander.expand(
            RenameMapping.fromJson(view.readText()),
            r8Mapping.takeIf { it.isFile },
        )
        assumeTrue("component mapping expand failed", componentMapping != null)
        assumeTrue("view mapping expand failed", viewMapping != null)
        val merged = componentMapping!!.mergedWith(viewMapping!!)
        val dexdump = locateDexdump()
        assumeTrue("dexdump not found (set ANDROID_HOME)", dexdump != null)
        ZipFile(unsigned).use { zip ->
            val dexNames = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
                .toList()
            val allDex = dexNames.map { zip.getInputStream(zip.getEntry(it)).readBytes() }
            val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(allDex, merged)
            dexNames.forEachIndexed { index, name ->
                val patched = DexInPlaceRenameEngine.remapBytes(allDex[index], merged, rewritePlan)
                File("/tmp/patched-$name").writeBytes(patched)
                val bad = findInvalidStringData(patched)
                if (bad != null) {
                    val tmp = File("/tmp/patched-$name")
                    tmp.writeBytes(patched)
                    error("$name invalid string: $bad (written ${tmp.absolutePath})")
                }
                val tmp = File.createTempFile("patched-", ".dex")
                tmp.writeBytes(patched)
                val proc = ProcessBuilder(dexdump!!.absolutePath, tmp.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().use { reader ->
                    buildString {
                        var lineNumber = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (lineNumber++ < 8) {
                                appendLine(line)
                            }
                        }
                    }
                }
                proc.waitFor()
                assertEquals(
                    "$name dexdump verify failed:\n$output",
                    0,
                    proc.exitValue(),
                )
                tmp.delete()
            }
        }
    }

    private fun findInvalidStringData(dex: ByteArray): String? {
        val stringIdsSize = readU32(dex, 0x38)
        val stringIdsOff = readU32(dex, 0x3C)
        for (i in 0 until stringIdsSize) {
            val off = readU32(dex, stringIdsOff + i * 4)
            if (off <= 0) continue
            var pos = off
            var size = 0
            var shift = 0
            while (pos < dex.size) {
                val b = dex[pos++].toInt() and 0xFF
                size = size or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (size == 0 && (pos >= dex.size || dex[pos] != 0.toByte())) {
                val snippet = dex.copyOfRange(off, minOf(off + 16, dex.size))
                return "idx=$i off=$off next=${dex.getOrNull(pos)?.toInt()?.and(0xFF)} snippet=${snippet.joinToString { "%02x".format(it) }}"
            }
        }
        return null
    }

    private fun readU32(dex: ByteArray, offset: Int): Int {
        return (dex[offset].toInt() and 0xFF) or
            ((dex[offset + 1].toInt() and 0xFF) shl 8) or
            ((dex[offset + 2].toInt() and 0xFF) shl 16) or
            ((dex[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun locateDexdump(): File? {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        val buildTools = File(sdk, "build-tools")
        if (!buildTools.isDirectory) return null
        return buildTools.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedWith(compareByDescending(BuildToolsVersionOrder) { it.name })
            ?.firstNotNullOfOrNull { dir ->
                File(dir, "dexdump").takeIf { it.isFile && it.canExecute() }
            }
    }
}
