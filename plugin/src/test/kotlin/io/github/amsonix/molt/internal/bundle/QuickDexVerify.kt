package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.mapping.R8MappingAliasExpander
import io.github.amsonix.molt.internal.rename.RenameMapping
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

/**
 * 本地快速验证：仅 patch classes.dex + dexdump。
 * 用法：./gradlew :build-logic:molt:quickDexVerify
 */
fun main(args: Array<String>) {
    try {
        runQuickVerify(args)
    } catch (e: Exception) {
        System.err.println("QUICK_DEX_VERIFY FAILED: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(1)
    }
}

private fun runQuickVerify(args: Array<String>) {
    val root = projectRoot()
    val dexFilter = args.firstOrNull() ?: "classes.dex"
    val unsigned = DexIntegrationFixture.apkCandidate(root, args.getOrNull(1))
    val component = DexIntegrationFixture.componentMapping(root)
    val view = DexIntegrationFixture.viewMapping(root)
    val r8Mapping = DexIntegrationFixture.r8Mapping(root)

    if (!unsigned.isFile || !component.isFile || !view.isFile) {
        println(
            "SKIP quickDexVerify: missing integration outputs " +
                "(apk=${unsigned.isFile}, component=${component.isFile}, view=${view.isFile}); " +
                "build :app:assembleGoogleRelease first",
        )
        return
    }

    val componentMapping = R8MappingAliasExpander.expand(
        RenameMapping.fromJson(component.readText()),
        r8Mapping.takeIf { it.isFile },
    ) ?: error("component mapping expand failed")
    val viewMapping = R8MappingAliasExpander.expand(
        RenameMapping.fromJson(view.readText()),
        r8Mapping.takeIf { it.isFile },
    ) ?: error("view mapping expand failed")
    val merged = componentMapping.mergedWith(viewMapping)
    val dexdump = locateDexdump() ?: error("dexdump not found; set ANDROID_HOME")

    val elapsed = measureTimeMillis {
        ZipFile(unsigned).use { zip ->
            val allDexNames = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
                .toList()
            val dexNames = allDexNames.filter { dexFilter == "all" || it == dexFilter }
            require(dexNames.isNotEmpty()) { "no dex matched filter=$dexFilter" }

            val allDex = allDexNames.map { zip.getInputStream(zip.getEntry(it)).readBytes() }
            val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(allDex, merged)

            dexNames.forEach { name ->
                val original = allDex[allDexNames.indexOf(name)]
                val patched = DexInPlaceRenameEngine.remapBytes(original, merged, rewritePlan)
                File("/tmp/patched-$name").writeBytes(patched)

                findInvalidStringData(patched)?.let { bad ->
                    error("$name invalid string: $bad (see /tmp/patched-$name)")
                }

                val tmp = File.createTempFile("patched-", ".dex")
                tmp.writeBytes(patched)
                val proc = ProcessBuilder(dexdump.absolutePath, tmp.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().use { reader ->
                    buildString {
                        var lineNumber = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (lineNumber++ < 6) {
                                appendLine(line)
                            }
                        }
                    }
                }
                val exit = proc.waitFor()
                tmp.delete()
                check(exit == 0) {
                    "$name dexdump failed (exit=$exit):\n$output\nfull dex: /tmp/patched-$name"
                }
                println("OK  $name (${patched.size} bytes) -> /tmp/patched-$name")
            }
        }
    }
    println("done in ${elapsed}ms")
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
            return "idx=$i off=$off snippet=${snippet.joinToString { "%02x".format(it) }}"
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
        ?.firstNotNullOfOrNull { dir -> File(dir, "dexdump").takeIf { it.isFile && it.canExecute() } }
}

private fun projectRoot(): File {
    var dir = File(System.getProperty("user.dir"))
    return if (dir.name == "shell-obfuscate") dir.parentFile.parentFile else dir
}
