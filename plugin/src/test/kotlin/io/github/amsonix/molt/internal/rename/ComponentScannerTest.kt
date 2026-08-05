package io.github.amsonix.molt.internal.rename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComponentScannerTest {

    @Test
    fun manifestScanIgnoresMetaDataAndPermission() {
        val manifest = File.createTempFile("test-manifest", ".xml").apply {
            writeText(
                """
                <manifest package="com.example.app">
                    <uses-permission android:name="com.google.android.gms.permission.AD_ID" />
                    <application android:name=".App">
                        <meta-data android:name="com.facebook.sdk.ApplicationId" android:value="123" />
                        <activity android:name=".MainActivity" android:process=":remote">
                            <intent-filter>
                                <action android:name="com.example.app.OPEN_MAIN" />
                            </intent-filter>
                        </activity>
                        <service android:name=".SyncService" />
                        <provider
                            android:name=".AppProvider"
                            android:authorities="x.y.c.x" />
                    </application>
                </manifest>
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val scanned = ComponentScanner.scanModule(
            namespace = "com.example.app",
            sourceRoots = emptyList(),
            manifestFiles = listOf(manifest),
        )

        assertTrue(scanned.contains("com.example.app.MainActivity"))
        assertTrue(scanned.contains("com.example.app.SyncService"))
        assertTrue(scanned.contains("com.example.app.App"))
        assertTrue(scanned.contains("com.example.app.AppProvider"))
        assertFalse(scanned.contains("com.google.android.gms.permission.AD_ID"))
        assertFalse(scanned.contains("com.facebook.sdk.ApplicationId"))
        assertFalse(scanned.contains("com.example.app.OPEN_MAIN"))
        assertFalse(scanned.contains("x.y.c.x"))
        assertFalse(scanned.contains(":remote"))
    }

    @Test
    fun sourceScanIgnoresCustomViewSubclasses() {
        val sourceRoot = File.createTempFile("source-root", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        File(sourceRoot, "CustomView.java").writeText(
            """
            package com.example.widgets;
            public class CustomView extends com.example.ui.layout.constraintLayout.PLConstraintLayout {
            }
            """.trimIndent(),
        )

        val scanned = ComponentScanner.scanModule(
            namespace = "com.example.widgets",
            sourceRoots = listOf(sourceRoot),
            manifestFiles = emptyList(),
        )

        assertFalse(scanned.contains("com.example.widgets.CustomView"))
    }

    @Test
    fun sourceScanDoesNotPromoteClassFromActivityKeyword() {
        val sourceRoot = File.createTempFile("source-root", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        File(sourceRoot, "MainActivity.kt").writeText(
            """
            package com.example.app
            class MainActivity : androidx.appcompat.app.AppCompatActivity()
            """.trimIndent(),
        )

        val scanned = ComponentScanner.scanModule(
            namespace = "com.example.app",
            sourceRoots = listOf(sourceRoot),
            manifestFiles = emptyList(),
        )

        assertFalse(scanned.contains("com.example.app.MainActivity"))
    }

    @Test
    fun projectScopeKeepsLocalComponentsAndExcludesThirdPartyManifestComponents() {
        val sourceRoot = File.createTempFile("source-root", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        File(sourceRoot, "LocalSdkActivity.kt").writeText(
            """
            package lib.stat.polaris
            class LocalSdkActivity : android.app.Activity()
            """.trimIndent(),
        )
        File(sourceRoot, "MainActivity.kt").writeText(
            """
            package com.example.app
            class MainActivity : android.app.Activity()
            """.trimIndent(),
        )

        val projectClasses = ComponentScanner.collectProjectClasses(
            namespace = "com.example.app",
            sourceRoots = listOf(sourceRoot),
        )
        val filtered = ComponentScanner.filterProjectClasses(
            candidates = setOf(
                "com.example.app.MainActivity",
                "com.example.app.sdk.ExternalActivity",
                "lib.stat.polaris.LocalSdkActivity",
                "com.facebook.FacebookActivity",
                "com.google.android.gms.ads.AdActivity",
            ),
            projectClasses = projectClasses,
        )

        assertTrue(filtered.contains("com.example.app.MainActivity"))
        assertTrue(filtered.contains("lib.stat.polaris.LocalSdkActivity"))
        assertFalse(filtered.contains("com.example.app.sdk.ExternalActivity"))
        assertFalse(filtered.contains("com.facebook.FacebookActivity"))
        assertFalse(filtered.contains("com.google.android.gms.ads.AdActivity"))
    }

    @Test
    fun collectProjectClassesFindsJavaAndKotlinDeclarations() {
        val sourceRoot = File.createTempFile("source-root", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        File(sourceRoot, "AnimatedTickerView.java").writeText(
            """
            package com.example.app.adpv;
            public class AnimatedTickerView extends android.view.View {
            }
            """.trimIndent(),
        )
        File(sourceRoot, "AnimatedDiscountedPriceView.kt").writeText(
            """
            package com.example.app.adpv
            class AnimatedDiscountedPriceView : android.view.View(null)
            """.trimIndent(),
        )

        val projectClasses = ComponentScanner.collectProjectClasses(
            namespace = null,
            sourceRoots = listOf(sourceRoot),
        )

        assertTrue(projectClasses.contains("com.example.app.adpv.AnimatedTickerView"))
        assertTrue(projectClasses.contains("com.example.app.adpv.AnimatedDiscountedPriceView"))
    }

    @Test
    fun collectProjectClassesIgnoresCommentsStringsAndNestedDeclarations() {
        val sourceRoot = File.createTempFile("source-root", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        File(sourceRoot, "LocalOuter.java").writeText(
            """
            // package com.thirdparty.sdk;
            package com.example.app;
            public class LocalOuter {
                class NestedSdkActivity {}
                String marker = "class StringSdkActivity {}";
            }
            // class CommentedSdkActivity {}
            /* class BlockCommentSdkActivity {} */
            """.trimIndent(),
        )

        val projectClasses = ComponentScanner.collectProjectClasses(
            namespace = null,
            sourceRoots = listOf(sourceRoot),
        )

        assertEquals(setOf("com.example.app.LocalOuter"), projectClasses)
    }

    @Test
    fun sourceIndexCollectsProjectClassesComponentsAndSupertypeAnchorsInOnePass() {
        val sourceRoot = File.createTempFile("source-root", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        File(sourceRoot, "BaseActivity.kt").writeText(
            """
            package com.example.base
            open class BaseActivity : androidx.appcompat.app.AppCompatActivity()
            """.trimIndent(),
        )
        File(sourceRoot, "MainActivity.kt").writeText(
            """
            package com.example.app
            class MainActivity : com.example.base.BaseActivity()
            val ignored = "class StringActivity : android.app.Activity()"
            // class CommentActivity : android.app.Activity()
            """.trimIndent(),
        )
        File(sourceRoot, "SyncService.java").writeText(
            """
            package com.example.app;
            public class SyncService extends android.app.Service {
            }
            """.trimIndent(),
        )

        val index = ComponentScanner.indexSources(
            namespace = null,
            sourceRoots = listOf(sourceRoot),
        )

        assertEquals(
            setOf(
                "com.example.base.BaseActivity",
                "com.example.app.MainActivity",
                "com.example.app.SyncService",
            ),
            index.declaredClasses,
        )
        assertEquals(
            emptySet<String>(),
            index.componentCandidates,
        )
        assertTrue(index.referencedSuperTypes.contains("com.example.base.BaseActivity"))
        assertFalse(index.declaredClasses.any { it.contains("StringActivity") || it.contains("CommentActivity") })

        val renameCandidates = ComponentScanner.filterSupertypeAnchors(
            candidates = index.componentCandidates,
            sourceIndex = index,
        )
        assertEquals(
            emptySet<String>(),
            renameCandidates,
        )
    }

    @Test
    fun scanRuntimeResourceXmlFindsQualifierLayoutAndNavigationComponents() {
        val resRoot = File.createTempFile("runtime-xml-root", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        File(resRoot, "layout-land").mkdirs()
        File(resRoot, "navigation-w600dp").mkdirs()
        File(resRoot, "layout-land/only_land.xml").writeText(
            """
            <androidx.fragment.app.FragmentContainerView
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:name=".feature.LandFragment" />
            """.trimIndent(),
        )
        File(resRoot, "navigation-w600dp/main.xml").writeText(
            """
            <navigation xmlns:android="http://schemas.android.com/apk/res/android">
                <fragment android:name="com.example.app.nav.DetailFragment" />
                <!-- <fragment android:name="com.example.app.nav.CommentFragment" /> -->
            </navigation>
            """.trimIndent(),
        )

        val scanned = ComponentScanner.scanRuntimeResourceXml(
            namespace = "com.example.app",
            resourceRoots = listOf(resRoot),
        )

        assertEquals(
            setOf(
                "com.example.app.feature.LandFragment",
                "com.example.app.nav.DetailFragment",
            ),
            scanned,
        )
    }

    @Test
    fun filterSupertypeAnchors_excludesBaseClassExtendedInSource() {
        val sourceRoot = File.createTempFile("source-root", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        File(sourceRoot, "PLBaseActivity.kt").writeText(
            """
            package com.example.base
            open class PLBaseActivity : androidx.appcompat.app.AppCompatActivity()
            """.trimIndent(),
        )
        File(sourceRoot, "MainActivity.kt").writeText(
            """
            package com.example.app
            class MainActivity : com.example.base.PLBaseActivity()
            """.trimIndent(),
        )

        val candidates = setOf(
            "com.example.base.PLBaseActivity",
            "com.example.app.MainActivity",
        )
        val filtered = ComponentScanner.filterSupertypeAnchors(candidates, listOf(sourceRoot))

        assertFalse(filtered.contains("com.example.base.PLBaseActivity"))
        assertTrue(filtered.contains("com.example.app.MainActivity"))
    }
}
