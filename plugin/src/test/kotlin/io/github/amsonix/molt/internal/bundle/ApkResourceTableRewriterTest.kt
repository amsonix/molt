package io.github.amsonix.molt.internal.bundle

import com.android.aapt.Resources
import io.github.amsonix.molt.internal.keep.KeepXmlParser
import io.github.amsonix.molt.internal.reschiper.bundle.ResourceMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ApkResourceTableRewriterTest {

    @Test
    fun createPlan_fileModeKeepsDirectoryAndRenamesEntryAndFile() {
        val sourcePath = "res/drawable-hdpi/icon.png"
        val plan = createPlan(
            table(EntrySpec("icon", sourcePath)),
            mode = "file",
        )

        val mappedEntry = plan.entryNameMap.getValue("drawable/icon")
        val targetPath = ApkResourcePath.parse(plan.filePathMap.getValue(sourcePath))

        assertTrue(plan.directoryNameMap.isEmpty())
        assertEquals("res/drawable-hdpi", targetPath.directory)
        assertEquals(mappedEntry, targetPath.baseName)
        assertNotEquals("icon", mappedEntry)
    }

    @Test
    fun createPlan_dirModeRenamesDirectoryButKeepsFileName() {
        val sourcePath = "res/drawable-hdpi/icon.png"
        val plan = createPlan(
            table(EntrySpec("icon", sourcePath)),
            mode = "dir",
        )

        val targetPath = ApkResourcePath.parse(plan.filePathMap.getValue(sourcePath))

        assertNotEquals("icon", plan.entryNameMap.getValue("drawable/icon"))
        assertNotEquals("res/drawable-hdpi", targetPath.directory)
        assertEquals("icon", targetPath.baseName)
    }

    @Test
    fun createPlan_keepRulePreservesEntryNameAndPhysicalPath() {
        val keptPath = "res/drawable-hdpi/kept_icon.png"
        val otherPath = "res/drawable-hdpi/other_icon.png"
        val keepRules = listOf(KeepXmlParser.KeepResource("drawable", "kept_icon"))
        val plan = createPlan(
            table(
                EntrySpec("kept_icon", keptPath),
                EntrySpec("other_icon", otherPath),
            ),
            keepRules = keepRules,
        )

        assertFalse(plan.entryNameMap.containsKey("drawable/kept_icon"))
        assertFalse(plan.filePathMap.containsKey(keptPath))
        assertTrue(plan.entryNameMap.containsKey("drawable/other_icon"))
        assertTrue(plan.filePathMap.containsKey(otherPath))
    }

    @Test
    fun createPlan_incrementalSeedReservesObfuscatedNamesWithinType() {
        val incremental = ResourceMapping()
        incremental.resourceMapping["dimen/existing"] = "dmx9"
        val table = Resources.ResourceTable.newBuilder()
            .addPackage(
                Resources.Package.newBuilder()
                    .setPackageName("com.example")
                    .addType(
                        Resources.Type.newBuilder()
                            .setName("dimen")
                            .addEntry(resourceEntry(EntrySpec("existing", "res/values/dimens.xml")))
                            .addEntry(resourceEntry(EntrySpec("other", "res/values/dimens.xml"))),
                    ),
            )
            .build()
        val plan = ApkResourceTableRewriter.createPlan(
            table,
            emptyList(),
            "file",
            Random(7),
            incremental,
        )
        assertEquals("dmx9", plan.entryNameMap["dimen/existing"])
        assertNotEquals("dmx9", plan.entryNameMap["dimen/other"])
    }

    private fun createPlan(
        table: Resources.ResourceTable,
        mode: String = "default",
        keepRules: List<KeepXmlParser.KeepResource> = emptyList(),
    ): ApkResourceTableRewriter.Plan = ApkResourceTableRewriter.createPlan(
        table,
        keepRules,
        mode,
        Random(7),
    )

    private fun table(vararg entries: EntrySpec): Resources.ResourceTable =
        Resources.ResourceTable.newBuilder()
            .addPackage(
                Resources.Package.newBuilder()
                    .setPackageName("com.example")
                    .addType(
                        Resources.Type.newBuilder()
                            .setName("drawable")
                            .addAllEntry(entries.map(::resourceEntry)),
                    ),
            )
            .build()

    private fun resourceEntry(spec: EntrySpec): Resources.Entry =
        Resources.Entry.newBuilder()
            .setName(spec.name)
            .addConfigValue(
                Resources.ConfigValue.newBuilder()
                    .setValue(
                        Resources.Value.newBuilder()
                            .setItem(
                                Resources.Item.newBuilder()
                                    .setFile(
                                        Resources.FileReference.newBuilder()
                                            .setPath(spec.path),
                                    ),
                            ),
                    ),
            )
            .build()

    private data class EntrySpec(
        val name: String,
        val path: String,
    )
}
