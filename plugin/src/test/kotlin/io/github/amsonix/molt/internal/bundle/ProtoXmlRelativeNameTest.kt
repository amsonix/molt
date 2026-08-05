package io.github.amsonix.molt.internal.bundle

import com.android.aapt.Resources
import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoXmlRelativeNameTest {

    @Test
    fun replace_remapsRelativeAttributeValue() {
        val originalName = "com.example.app.main.MainActivity"
        val mapping = RenameMapping.build(
            candidates = setOf(originalName),
            seed = 11,
            excludePatterns = emptyList(),
            salt = "component-relative",
        )
        val obfuscated = mapping.resolve(originalName)!!
        val original = Resources.XmlNode.newBuilder()
            .setElement(
                Resources.XmlElement.newBuilder()
                    .setName("activity")
                    .addAttribute(
                        Resources.XmlAttribute.newBuilder()
                            .setName("name")
                            .setValue(".main.MainActivity")
                            .build(),
                    ),
            )
            .build()
            .toByteArray()

        val patched = ProtoXmlViewClassReplacer.replace(original, mapping)
        val attr = Resources.XmlNode.parseFrom(patched).element.getAttribute(0).value
        assertEquals(obfuscated, attr)
    }
}
