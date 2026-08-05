package io.github.amsonix.molt.internal.junk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JunkManifestMergerTest {

    @Test
    fun mergeIntoManifest_insertsActivityInsideApplication() {
        val merged = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <application android:label="@string/app_name">
                    <activity android:name=".MainActivity" />
                </application>
            </manifest>
        """.trimIndent()
        val junk = JunkManifestMerger.buildManifestSnippet(
            listOf("com.example.junk.pkg.AActivity"),
        )
        val result = JunkManifestMerger.mergeIntoManifest(merged, junk)
        assertTrue(result.merged)
        assertTrue(result.manifest.contains("com.example.junk.pkg.AActivity"))
        assertTrue(result.manifest.contains("<application android:label=\"@string/app_name\">"))
        assertTrue(result.manifest.contains(".MainActivity"))
    }

    @Test
    fun mergeIntoManifest_failsWhenApplicationTagMissing() {
        val merged = """
            <manifest package="com.example.app" />
        """.trimIndent()
        val junk = JunkManifestMerger.buildManifestSnippet(listOf("com.example.AActivity"))
        val result = JunkManifestMerger.mergeIntoManifest(merged, junk)
        assertFalse(result.merged)
        assertTrue(result.failureReason!!.contains("<application>"))
    }

    @Test
    fun mergeIntoManifest_succeedsOnSelfClosingApplication() {
        val merged = """
            <manifest package="com.example.app">
                <application />
            </manifest>
        """.trimIndent()
        val junk = JunkManifestMerger.buildManifestSnippet(listOf("com.example.AActivity"))
        val result = JunkManifestMerger.mergeIntoManifest(merged, junk)
        assertTrue(result.merged)
        assertTrue(result.manifest.contains("com.example.AActivity"))
    }

    @Test
    fun mergeIntoManifest_insertsActivityWithApplicationAttributes() {
        val merged = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <application
                    android:name=".App"
                    android:label="@string/app_name" />
            </manifest>
        """.trimIndent()
        val junk = JunkManifestMerger.buildManifestSnippet(listOf("com.example.junk.BActivity"))
        val result = JunkManifestMerger.mergeIntoManifest(merged, junk)
        assertTrue(result.merged)
        assertTrue(result.manifest.contains("com.example.junk.BActivity"))
        assertTrue(result.manifest.contains("android:name=\".App\""))
    }
}
