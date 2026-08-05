package io.github.amsonix.molt.internal.rename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ViewClassScannerTest {

    @Test
    fun scanLayoutFile_findsCustomViewTag() {
        val file = File.createTempFile("layout", ".xml").apply {
            writeText(
                """
                <com.example.app.widget.PLConstraintLayout
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content">
                    <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" />
                </com.example.app.widget.PLConstraintLayout>
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val result = ViewClassScanner.scanLayoutFile(file)
        assertTrue(result.contains("com.example.app.widget.PLConstraintLayout"))
        assertFalse(result.any { it.startsWith("android.") })
    }

    @Test
    fun scanLayoutFile_ignoresAndroidFrameworkTags() {
        val file = File.createTempFile("layout", ".xml").apply {
            writeText("<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
            deleteOnExit()
        }

        assertTrue(ViewClassScanner.scanLayoutFile(file).isEmpty())
    }

    @Test
    fun scanLayoutFile_findsClassAttribute() {
        val file = File.createTempFile("layout", ".xml").apply {
            writeText(
                """
                <layout xmlns:android="http://schemas.android.com/apk/res/android">
                    <data>
                        <variable name="handler" type="com.example.app.ui.ClickHandler" />
                    </data>
                </layout>
                """.trimIndent(),
            )
            deleteOnExit()
        }

        assertEquals(
            setOf("com.example.app.ui.ClickHandler"),
            ViewClassScanner.scanLayoutFile(file),
        )
    }

    @Test
    fun scanLayoutFile_ignoresCustomViewsInsideXmlComments() {
        val file = File.createTempFile("layout", ".xml").apply {
            writeText(
                """
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android">
                    <!--
                    <com.example.app.popup.LastChanceFloatView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content" />
                    <view class="com.thirdparty.sdk.CommentedView" />
                    -->
                    <com.example.LocalView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content" />
                </FrameLayout>
                """.trimIndent(),
            )
            deleteOnExit()
        }

        assertEquals(
            setOf("com.example.LocalView"),
            ViewClassScanner.scanLayoutFile(file),
        )
    }

    @Test
    fun scanLayoutDirsFindsViewOnlyPresentInQualifierAndSkipsNavigation() {
        val resRoot = File.createTempFile("qualified-layout-root", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        File(resRoot, "layout-land").mkdirs()
        File(resRoot, "navigation").mkdirs()
        File(resRoot, "layout-land/only_land.xml").writeText(
            """<com.example.widget.LandOnlyView />""",
        )
        File(resRoot, "navigation/main.xml").writeText(
            """<fragment android:name="com.example.feature.NavigationFragment" />""",
        )

        val scanned = ViewClassScanner.scanLayoutDirs(listOf(File(resRoot, "layout")))

        assertEquals(setOf("com.example.widget.LandOnlyView"), scanned)
    }
}
