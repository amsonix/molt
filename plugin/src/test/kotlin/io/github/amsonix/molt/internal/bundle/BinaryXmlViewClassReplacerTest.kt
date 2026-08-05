package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryXmlViewClassReplacerTest {

    @Test
    fun replace_preservesStyledUtf8PoolAndStringIndexes() {
        val styleBlob = styleBlob(nameIndex = 3)
        val input = buildBinaryXml(
            strings = listOf(
                "com.example.view.OriginalView",
                "unchanged-value",
                ".feature.RelativeView",
                "bold",
            ),
            utf8 = true,
            styleOffsets = intArrayOf(0),
            styleBlob = styleBlob,
        )
        val original = readPool(input)
        val mapping = RenameMapping.fromForward(
            mapOf(
                "com.example.view.OriginalView" to "a.b.C",
                "com.example.feature.RelativeView" to "x.y.Relative",
            ),
        )

        val output = BinaryXmlViewClassReplacer.replace(input, mapping)
        val rewritten = readPool(output)

        assertEquals(
            listOf("a.b.C", "unchanged-value", "x.y.Relative", "bold"),
            rewritten.strings,
        )
        assertEquals(original.styleOffsets, rewritten.styleOffsets)
        assertArrayEquals(styleBlob, rewritten.styleBlob)
        assertNotEquals(original.stylesStart, rewritten.stylesStart)
        assertFalse(rewritten.flags and SORTED_FLAG != 0)
        assertTrue(rewritten.flags and UTF8_FLAG != 0)
        assertEquals(output.size - XML_HEADER_SIZE, rewritten.chunkSize)
        assertEquals(output.size, readInt(output, 4))
    }

    @Test
    fun replace_preservesStyledUtf16PoolAndRelativeName() {
        val styleBlob = styleBlob(nameIndex = 2)
        val input = buildBinaryXml(
            strings = listOf(
                ".screen.DetailView",
                "com.example.Unrelated",
                "italic",
            ),
            utf8 = false,
            styleOffsets = intArrayOf(NO_ENTRY, 0),
            styleBlob = styleBlob,
        )
        val mapping = RenameMapping.fromForward(
            mapOf("com.example.screen.DetailView" to "renamed.Detail"),
        )

        val output = BinaryXmlViewClassReplacer.replace(input, mapping)
        val rewritten = readPool(output)

        assertEquals(
            listOf("renamed.Detail", "com.example.Unrelated", "italic"),
            rewritten.strings,
        )
        assertEquals(listOf(NO_ENTRY, 0), rewritten.styleOffsets)
        assertArrayEquals(styleBlob, rewritten.styleBlob)
        assertFalse(rewritten.flags and UTF8_FLAG != 0)
        assertFalse(rewritten.flags and SORTED_FLAG != 0)
    }

    @Test
    fun replace_returnsOriginalWhenOnlyUnrelatedStringsExist() {
        val input = buildBinaryXml(
            strings = listOf("com.example.Unrelated", "plain text"),
            utf8 = true,
        )
        val mapping = RenameMapping.fromForward(mapOf("com.example.Missing" to "a.b.C"))

        val result = BinaryXmlViewClassReplacer.rewrite(input, mapping)

        assertEquals(ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED, result.formatStatus)
        assertEquals(0, result.replacementCount)
        assertSame(input, result.bytes)
    }

    @Test
    fun replace_findsStringPoolAfterLeadingResourceMapChunk() {
        val styleBlob = styleBlob(nameIndex = 1)
        val poolOnly = buildBinaryXml(
            strings = listOf("com.example.view.OriginalView", "plain"),
            utf8 = true,
            styleOffsets = intArrayOf(0),
            styleBlob = styleBlob,
        )
        val poolBytes = poolOnly.copyOfRange(XML_HEADER_SIZE, poolOnly.size)
        val resourceMapSize = 8
        val input = ByteArray(XML_HEADER_SIZE + resourceMapSize + poolBytes.size)
        val buffer = java.nio.ByteBuffer.wrap(input).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(RES_XML_TYPE.toShort())
        buffer.putShort(XML_HEADER_SIZE.toShort())
        buffer.putInt(input.size)
        buffer.putShort(0x0180.toShort())
        buffer.putShort(8)
        buffer.putInt(resourceMapSize)
        buffer.put(poolBytes)
        java.nio.ByteBuffer.wrap(input, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(input.size)

        val mapping = RenameMapping.fromForward(
            mapOf("com.example.view.OriginalView" to "a.b.C"),
        )
        val output = BinaryXmlViewClassReplacer.replace(input, mapping)
        assertTrue(String(output, Charsets.UTF_8).contains("a.b.C"))
        assertFalse(String(output, Charsets.UTF_8).contains("com.example.view.OriginalView"))
    }

    @Test
    fun rewriteDistinguishesUnsupportedFromMalformedBinaryXml() {
        val mapping = RenameMapping.fromForward(mapOf("com.example.View" to "a.b.C"))
        val unsupported = "<LinearLayout />".toByteArray()
        val malformed = byteArrayOf(
            0x03, 0x00, 0x08, 0x00,
            0x08, 0x00, 0x00, 0x00,
        )

        assertEquals(
            ProtoXmlViewClassReplacer.FormatStatus.UNSUPPORTED,
            BinaryXmlViewClassReplacer.rewrite(unsupported, mapping).formatStatus,
        )
        assertEquals(
            ProtoXmlViewClassReplacer.FormatStatus.PARSE_FAILED,
            BinaryXmlViewClassReplacer.rewrite(malformed, mapping).formatStatus,
        )
    }

    private fun buildBinaryXml(
        strings: List<String>,
        utf8: Boolean,
        styleOffsets: IntArray = intArrayOf(),
        styleBlob: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(styleOffsets.isNotEmpty() || styleBlob.isEmpty())
        val encoded = strings.map { encodeString(it, utf8) }
        val offsets = IntArray(strings.size)
        var stringDataSize = 0
        encoded.forEachIndexed { index, bytes ->
            offsets[index] = stringDataSize
            stringDataSize += bytes.size
        }
        val stringsStart = STRING_POOL_HEADER_SIZE + (strings.size + styleOffsets.size) * Int.SIZE_BYTES
        val stylesStart = if (styleOffsets.isNotEmpty()) align4(stringsStart + stringDataSize) else 0
        val poolSize = if (styleOffsets.isNotEmpty()) {
            stylesStart + styleBlob.size
        } else {
            align4(stringsStart + stringDataSize)
        }
        val output = ByteArray(XML_HEADER_SIZE + poolSize)
        val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(RES_XML_TYPE.toShort())
        buffer.putShort(XML_HEADER_SIZE.toShort())
        buffer.putInt(output.size)
        buffer.putShort(RES_STRING_POOL_TYPE.toShort())
        buffer.putShort(STRING_POOL_HEADER_SIZE.toShort())
        buffer.putInt(poolSize)
        buffer.putInt(strings.size)
        buffer.putInt(styleOffsets.size)
        buffer.putInt(SORTED_FLAG or if (utf8) UTF8_FLAG else 0)
        buffer.putInt(stringsStart)
        buffer.putInt(stylesStart)
        offsets.forEach(buffer::putInt)
        styleOffsets.forEach(buffer::putInt)
        buffer.position(XML_HEADER_SIZE + stringsStart)
        encoded.forEach(buffer::put)
        if (styleOffsets.isNotEmpty()) {
            buffer.position(XML_HEADER_SIZE + stylesStart)
            buffer.put(styleBlob)
        }
        return output
    }

    private fun readPool(xml: ByteArray): PoolSnapshot {
        val poolOffset = XML_HEADER_SIZE
        val chunkSize = readInt(xml, poolOffset + 4)
        val stringCount = readInt(xml, poolOffset + 8)
        val styleCount = readInt(xml, poolOffset + 12)
        val flags = readInt(xml, poolOffset + 16)
        val stringsStart = readInt(xml, poolOffset + 20)
        val stylesStart = readInt(xml, poolOffset + 24)
        val utf8 = flags and UTF8_FLAG != 0
        val stringOffsets = IntArray(stringCount) { index ->
            readInt(xml, poolOffset + STRING_POOL_HEADER_SIZE + index * Int.SIZE_BYTES)
        }
        val styleTableStart = poolOffset + STRING_POOL_HEADER_SIZE + stringCount * Int.SIZE_BYTES
        val styleOffsets = List(styleCount) { index ->
            readInt(xml, styleTableStart + index * Int.SIZE_BYTES)
        }
        val strings = stringOffsets.map { offset ->
            readString(xml, poolOffset + stringsStart + offset, utf8)
        }
        val styleBlob = if (styleCount > 0) {
            xml.copyOfRange(poolOffset + stylesStart, poolOffset + chunkSize)
        } else {
            byteArrayOf()
        }
        return PoolSnapshot(strings, styleOffsets, styleBlob, flags, stylesStart, chunkSize)
    }

    private fun readString(bytes: ByteArray, offset: Int, utf8: Boolean): String {
        if (!utf8) {
            val length = readU16(bytes, offset)
            return String(bytes, offset + 2, length * 2, Charsets.UTF_16LE)
        }
        val (_, characterLengthSize) = readLength(bytes, offset)
        val (byteLength, byteLengthSize) = readLength(bytes, offset + characterLengthSize)
        val dataStart = offset + characterLengthSize + byteLengthSize
        return String(bytes, dataStart, byteLength, Charsets.UTF_8)
    }

    private fun encodeString(value: String, utf8: Boolean): ByteArray {
        if (!utf8) {
            val text = value.toByteArray(Charsets.UTF_16LE)
            return ByteArray(2 + text.size + 2).also { output ->
                writeU16(output, 0, value.length)
                text.copyInto(output, 2)
            }
        }
        val text = value.toByteArray(Charsets.UTF_8)
        return encodeLength(value.length) + encodeLength(text.size) + text + byteArrayOf(0)
    }

    private fun styleBlob(nameIndex: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(nameIndex)
            .putInt(0)
            .putInt(3)
            .putInt(NO_ENTRY)
            .array()

    private fun readLength(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        val first = bytes[offset].toInt() and 0xFF
        if (first and 0x80 == 0) return first to 1
        return (((first and 0x7F) shl 8) or (bytes[offset + 1].toInt() and 0xFF)) to 2
    }

    private fun encodeLength(value: Int): ByteArray =
        if (value < 0x80) {
            byteArrayOf(value.toByte())
        } else {
            byteArrayOf(((value shr 8) or 0x80).toByte(), value.toByte())
        }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    private fun align4(value: Int): Int = (value + 3) and -4

    private data class PoolSnapshot(
        val strings: List<String>,
        val styleOffsets: List<Int>,
        val styleBlob: ByteArray,
        val flags: Int,
        val stylesStart: Int,
        val chunkSize: Int,
    )

    private companion object {
        const val RES_XML_TYPE = 0x0003
        const val RES_STRING_POOL_TYPE = 0x0001
        const val XML_HEADER_SIZE = 8
        const val STRING_POOL_HEADER_SIZE = 28
        const val SORTED_FLAG = 1
        const val UTF8_FLAG = 1 shl 8
        const val NO_ENTRY = -1
    }
}
