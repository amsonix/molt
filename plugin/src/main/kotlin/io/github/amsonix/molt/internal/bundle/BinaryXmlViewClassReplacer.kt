package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.rename.ComponentRenameEntry
import io.github.amsonix.molt.internal.rename.RenameMapping
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 替换 binary XML string pool 中的 View FQCN。
 * 按 RES_XML chunk 内扫描 RES_STRING_POOL，兼容 headerSize 非 8 及 pool 前有其它 chunk 的布局。
 */
internal object BinaryXmlViewClassReplacer {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val SORTED_FLAG = 1
    private const val UTF8_FLAG = 1 shl 8

    fun replace(input: ByteArray, mapping: RenameMapping, strict: Boolean = false): ByteArray {
        val result = rewrite(input, mapping)
        if (strict && result.formatStatus != ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED) {
            error("BinaryXmlViewClassReplacer: ${result.failureReason}")
        }
        return result.bytes
    }

    fun rewrite(
        input: ByteArray,
        mapping: RenameMapping,
    ): ProtoXmlViewClassReplacer.RewriteResult {
        if (input.size < 2 || readU16(input, 0) != RES_XML_TYPE) {
            return result(
                input,
                ProtoXmlViewClassReplacer.FormatStatus.UNSUPPORTED,
                reason = "not binary XML",
            )
        }
        return try {
            rewriteSupported(input, mapping)
        } catch (exception: Exception) {
            result(
                input,
                ProtoXmlViewClassReplacer.FormatStatus.PARSE_FAILED,
                reason = "${exception::class.java.simpleName}: ${exception.message}",
            )
        }
    }

    private fun rewriteSupported(
        input: ByteArray,
        mapping: RenameMapping,
    ): ProtoXmlViewClassReplacer.RewriteResult {
        require(input.size >= 16) { "binary XML header is truncated" }
        val xmlSize = readU32(input, 4)
        require(xmlSize > 8 && xmlSize <= input.size) { "invalid XML chunk size=$xmlSize" }
        val poolOffset = findStringPoolOffset(input, xmlEnd = xmlSize)
            ?: throw IllegalArgumentException("missing string pool in binary XML")
        require(readU16(input, poolOffset) == RES_STRING_POOL_TYPE) { "missing leading string pool" }

        val poolSize = readU32(input, poolOffset + 4)
        require(poolSize >= STRING_POOL_HEADER_SIZE && poolSize <= input.size - poolOffset) {
            "invalid string pool size=$poolSize"
        }
        val poolEnd = poolOffset + poolSize
        val entries = mapping.entries().sortedByDescending { it.original.length }
        val oldPool = input.copyOfRange(poolOffset, poolEnd)
        val poolRewrite = requireNotNull(rewritePool(oldPool, entries)) {
            "invalid string pool contents"
        }
        if (poolRewrite.replacementCount == 0) {
            return result(input, ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED)
        }

        val newPool = poolRewrite.bytes
        val delta = newPool.size - oldPool.size
        val output = ByteArray(input.size + delta)
        System.arraycopy(input, 0, output, 0, poolOffset)
        System.arraycopy(newPool, 0, output, poolOffset, newPool.size)
        System.arraycopy(input, poolEnd, output, poolEnd + delta, input.size - poolEnd)
        writeU32(output, 4, xmlSize + delta)
        return result(
            output,
            ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED,
            replacementCount = poolRewrite.replacementCount,
        )
    }

    /** 相对名 ".MainActivity" / ".foo.Bar" 对齐 FQCN mapping，输出完整混淆 FQCN。 */
    private fun remapPoolString(value: String, entries: List<ComponentRenameEntry>): String {
        entries.forEach { entry ->
            if (value == entry.original) return entry.obfuscated
        }
        if (value.startsWith(".")) {
            val relative = value.substring(1)
            entries.forEach { entry ->
                val original = entry.original
                if (original == relative || original.endsWith(".$relative")) {
                    return entry.obfuscated
                }
            }
        }
        return value
    }

    private fun rewritePool(
        poolBytes: ByteArray,
        entries: List<ComponentRenameEntry>,
    ): PoolRewrite? {
        if (poolBytes.size < STRING_POOL_HEADER_SIZE) return null
        val headerSize = readU16(poolBytes, 2)
        val oldChunkSize = readU32(poolBytes, 4)
        val stringCount = readU32(poolBytes, 8)
        val styleCount = readU32(poolBytes, 12)
        val flags = readU32(poolBytes, 16)
        val stringsStart = readU32(poolBytes, 20)
        val stylesStart = readU32(poolBytes, 24)
        val utf8 = flags and UTF8_FLAG != 0

        val stringOffsetsEnd = headerSize + stringCount * 4
        val styleOffsetsEnd = stringOffsetsEnd + styleCount * 4
        if (headerSize < STRING_POOL_HEADER_SIZE ||
            oldChunkSize > poolBytes.size ||
            stringCount < 0 ||
            styleCount < 0 ||
            stringOffsetsEnd < headerSize ||
            styleOffsetsEnd < stringOffsetsEnd ||
            styleOffsetsEnd > stringsStart ||
            stringsStart > oldChunkSize
        ) {
            return null
        }
        if (styleCount > 0 && (stylesStart < stringsStart || stylesStart > oldChunkSize)) return null

        val offsets = IntArray(stringCount) { i -> readU32(poolBytes, headerSize + i * 4) }
        val oldStrings = ArrayList<String>(stringCount)
        offsets.forEach { offset ->
            oldStrings += readPoolString(poolBytes, stringsStart + offset, utf8) ?: return null
        }
        val newStrings = oldStrings.map { original ->
            remapPoolString(original, entries)
        }
        val replacementCount = oldStrings.indices.count { index -> oldStrings[index] != newStrings[index] }
        if (replacementCount == 0) return PoolRewrite(poolBytes, 0)

        val styleOffsets = poolBytes.copyOfRange(stringOffsetsEnd, styleOffsetsEnd)
        val styleBlob = if (styleCount > 0) {
            poolBytes.copyOfRange(stylesStart, oldChunkSize)
        } else {
            byteArrayOf()
        }
        val encoded = newStrings.map { encodePoolString(it, utf8) }
        val newStringsStart = styleOffsetsEnd
        var dataSize = 0
        encoded.forEach { dataSize += it.size }
        val newStylesStart = if (styleCount > 0) align4(newStringsStart + dataSize) else 0
        val newChunkSize = if (styleCount > 0) {
            newStylesStart + styleBlob.size
        } else {
            align4(newStringsStart + dataSize)
        }

        // string 内容变化后原排序关系不再可信。
        val newFlags = flags and SORTED_FLAG.inv()

        val out = ByteArray(newChunkSize)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        outBuf.putShort(RES_STRING_POOL_TYPE.toShort())
        outBuf.putShort(headerSize.toShort())
        outBuf.putInt(newChunkSize)
        outBuf.putInt(stringCount)
        outBuf.putInt(styleCount)
        outBuf.putInt(newFlags)
        outBuf.putInt(newStringsStart)
        outBuf.putInt(newStylesStart)

        outBuf.position(headerSize)
        var running = 0
        encoded.forEach { bytes ->
            outBuf.putInt(running)
            running += bytes.size
        }
        outBuf.put(styleOffsets)

        outBuf.position(newStringsStart)
        encoded.forEach { outBuf.put(it) }
        if (styleCount > 0) {
            outBuf.position(newStylesStart)
            outBuf.put(styleBlob)
        }
        return PoolRewrite(out, replacementCount)
    }

    /** 在 RES_XML chunk 内按 chunk 链扫描 string pool（AOSP 通常位于 offset 8，部分 SDK layout 前有 resource map 等）。 */
    private fun findStringPoolOffset(input: ByteArray, xmlEnd: Int): Int? {
        val headerSize = readU16(input, 2).coerceAtLeast(8)
        var offset = headerSize
        while (offset + 8 <= xmlEnd) {
            if (readU16(input, offset) == RES_STRING_POOL_TYPE) return offset
            val chunkSize = readU32(input, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > xmlEnd) break
            offset += chunkSize
        }
        return null
    }

    private fun readPoolString(poolBytes: ByteArray, relativeOffset: Int, utf8: Boolean): String? {
        if (relativeOffset < 0 || relativeOffset >= poolBytes.size) return null
        var index = relativeOffset
        if (utf8) {
            val (_, charLenSize) = decodeLength(poolBytes, index)
            if (charLenSize == 0) return null
            index += charLenSize
            val (byteLen, byteLenSize) = decodeLength(poolBytes, index)
            if (byteLenSize == 0) return null
            index += byteLenSize
            if (byteLen < 0 || byteLen > poolBytes.size - index) return null
            return String(poolBytes, index, byteLen, Charsets.UTF_8)
        }
        if (index + 2 > poolBytes.size) return null
        val charLen = readU16(poolBytes, index)
        index += 2
        if (charLen < 0 || charLen > (poolBytes.size - index) / 2) return null
        return String(poolBytes, index, charLen * 2, Charsets.UTF_16LE)
    }

    private fun encodePoolString(value: String, utf8: Boolean): ByteArray {
        return if (utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            concatBytes(encodeLength(value.length), encodeLength(bytes.size), bytes, byteArrayOf(0))
        } else {
            val bytes = value.toByteArray(Charsets.UTF_16LE)
            ByteArray(2 + bytes.size + 2).also { arr ->
                writeU16(arr, 0, value.length)
                bytes.copyInto(arr, 2)
            }
        }
    }

    /** AOSP ResStringPool.decodeLength：1 或 2 字节长度前缀。 */
    private fun decodeLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        if (offset >= data.size) return 0 to 0
        val b0 = data[offset].toInt() and 0xFF
        return if (b0 and 0x80 != 0) {
            if (offset + 1 >= data.size) return 0 to 0
            val b1 = data[offset + 1].toInt() and 0xFF
            (((b0 and 0x7F) shl 8) or b1) to 2
        } else {
            b0 to 1
        }
    }

    private fun encodeLength(value: Int): ByteArray =
        if (value >= 0x80) {
            byteArrayOf(((value shr 8) or 0x80).toByte(), (value and 0xFF).toByte())
        } else {
            byteArrayOf(value.toByte())
        }

    private fun concatBytes(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        return ByteArray(total).also { arr ->
            var pos = 0
            parts.forEach { part ->
                part.copyInto(arr, pos)
                pos += part.size
            }
        }
    }

    private fun align4(value: Int): Int = (value + 3) and -4

    private fun readU16(input: ByteArray, offset: Int): Int =
        (input[offset].toInt() and 0xFF) or ((input[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeU16(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun readU32(input: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(input, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun writeU32(output: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(output, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun result(
        bytes: ByteArray,
        status: ProtoXmlViewClassReplacer.FormatStatus,
        replacementCount: Int = 0,
        reason: String? = null,
    ): ProtoXmlViewClassReplacer.RewriteResult =
        ProtoXmlViewClassReplacer.RewriteResult(bytes, status, replacementCount, reason)

    private data class PoolRewrite(
        val bytes: ByteArray,
        val replacementCount: Int,
    )

    private fun readUleb128(input: ByteArray, offset: Int): Int {
        var result = 0
        var shift = 0
        var index = offset
        while (index < input.size) {
            val b = input[index++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun uleb128Size(input: ByteArray, offset: Int): Int {
        var index = offset
        while (index < input.size) {
            if (input[index++].toInt() and 0x80 == 0) break
        }
        return index - offset
    }

    private fun uleb128Bytes(value: Int): ByteArray {
        var v = value
        val bytes = mutableListOf<Byte>()
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            bytes.add(b.toByte())
        } while (v != 0)
        return bytes.toByteArray()
    }

    private const val STRING_POOL_HEADER_SIZE = 28
}
