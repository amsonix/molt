package io.github.amsonix.molt.internal.bundle

import com.android.aapt.Resources
import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ProtoXmlViewClassReplacerTest {

    @Test
    fun replace_remapsElementNameInProtoLayout() {
        val originalName = "com.example.app.view.SubtitleView"
        val mapping = RenameMapping.build(
            candidates = setOf(originalName),
            seed = 42,
            excludePatterns = emptyList(),
            salt = "view-rename-test",
        )
        val obfuscated = mapping.resolve(originalName)!!
        val original = Resources.XmlNode.newBuilder()
            .setElement(
                Resources.XmlElement.newBuilder()
                    .setName(originalName),
            )
            .build()
            .toByteArray()

        val result = ProtoXmlViewClassReplacer.rewrite(original, mapping)

        assertFalse(result.bytes.contentEquals(original))
        assertEquals(ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED, result.formatStatus)
        assertEquals(1, result.replacementCount)
        assertEquals(obfuscated, Resources.XmlNode.parseFrom(result.bytes).element.name)
    }

    @Test
    fun rewriteReportsSupportedWhenValidProtoHasNoMatch() {
        val original = Resources.XmlNode.newBuilder()
            .setElement(Resources.XmlElement.newBuilder().setName("LinearLayout"))
            .build()
            .toByteArray()
        val mapping = RenameMapping.fromForward(mapOf("com.example.MissingView" to "a.b.C"))

        val result = ProtoXmlViewClassReplacer.rewrite(original, mapping)

        assertEquals(ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED, result.formatStatus)
        assertEquals(0, result.replacementCount)
        assertSame(original, result.bytes)
    }

    @Test
    fun rewriteDistinguishesUnsupportedFromParseFailure() {
        val mapping = RenameMapping.build(
            candidates = setOf("com.example.FooView"),
            seed = 1,
            excludePatterns = emptyList(),
            salt = "view-rename-test",
        )
        val binaryAxml = byteArrayOf(0x03, 0x00, 0x0C, 0x00, 0x00, 0x00)
        val malformedProto = byteArrayOf(0x0A, 0x05, 0x01)

        assertEquals(
            ProtoXmlViewClassReplacer.FormatStatus.UNSUPPORTED,
            ProtoXmlViewClassReplacer.rewrite(binaryAxml, mapping).formatStatus,
        )
        assertEquals(
            ProtoXmlViewClassReplacer.FormatStatus.PARSE_FAILED,
            ProtoXmlViewClassReplacer.rewrite(malformedProto, mapping).formatStatus,
        )
    }
}
