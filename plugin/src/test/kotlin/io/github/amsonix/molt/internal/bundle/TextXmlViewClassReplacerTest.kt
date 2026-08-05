package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextXmlViewClassReplacerTest {

    @Test
    fun rewrite_replacesFqcnAndRelativeClassName() {
        val mapping = RenameMapping.fromJson(
            """{"com.example.app.MainActivity":"a.b.c1"}""",
        )
        val input = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <com.example.app.MainActivity android:layout_width="match_parent" />
                <view class=".MainActivity" />
            </LinearLayout>
            """.trimIndent().toByteArray()

        val result = TextXmlViewClassReplacer.rewrite(input, mapping)

        assertEquals(ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED, result.formatStatus)
        assertTrue(result.replacementCount > 0)
        val text = result.bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("a.b.c1"))
        assertTrue(text.contains(".c1"))
    }
}
