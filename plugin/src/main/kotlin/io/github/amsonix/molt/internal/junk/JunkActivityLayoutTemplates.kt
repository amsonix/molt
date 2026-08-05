package io.github.amsonix.molt.internal.junk

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal object JunkActivityLayoutTemplates {

    fun layoutXml(rootId: String): String =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/$rootId"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />
        """.trimIndent()
}

internal object JunkManifestMerger {

    data class MergeResult(
        val manifest: String,
        val merged: Boolean,
        val failureReason: String? = null,
    )

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val TAG_APPLICATION = "application"
    private const val TAG_ACTIVITY = "activity"

    fun buildManifestSnippet(activityClassNames: List<String>): String {
        if (activityClassNames.isEmpty()) return ""
        return buildString {
            appendLine("""<manifest xmlns:android="$ANDROID_NS">""")
            appendLine("    <$TAG_APPLICATION>")
            activityClassNames.forEach { fqcn ->
                appendLine("""        <$TAG_ACTIVITY android:name="$fqcn" />""")
            }
            appendLine("    </$TAG_APPLICATION>")
            appendLine("</manifest>")
        }
    }

    fun mergeIntoManifest(mergedManifest: String, junkManifestSnippet: String): MergeResult {
        if (junkManifestSnippet.isBlank()) {
            return MergeResult(mergedManifest, merged = true)
        }
        return runCatching { mergeWithDom(mergedManifest, junkManifestSnippet) }
            .getOrElse { error ->
                MergeResult(
                    manifest = mergedManifest,
                    merged = false,
                    failureReason = "manifest parse/merge failed: ${error.message}",
                )
            }
    }

    private fun mergeWithDom(mergedManifest: String, junkManifestSnippet: String): MergeResult {
        val documentBuilder = newDocumentBuilder()
        val mergedDoc = documentBuilder.parse(InputSource(StringReader(mergedManifest)))
        val junkDoc = documentBuilder.parse(InputSource(StringReader(junkManifestSnippet)))

        val mergedApplication = mergedDoc.getElementsByTagName(TAG_APPLICATION).item(0) as? Element
            ?: return MergeResult(
                manifest = mergedManifest,
                merged = false,
                failureReason = "merged manifest missing <application> tag",
            )

        val junkApplication = junkDoc.getElementsByTagName(TAG_APPLICATION).item(0) as? Element
            ?: return MergeResult(
                manifest = mergedManifest,
                merged = false,
                failureReason = "junk manifest snippet missing <application> block",
            )

        val activityNodes = junkApplication.childNodes.toElementList()
            .filter { it.tagName == TAG_ACTIVITY }
        if (activityNodes.isEmpty()) {
            return MergeResult(
                manifest = mergedManifest,
                merged = false,
                failureReason = "junk manifest snippet has no activity entries",
            )
        }

        activityNodes.forEach { activity ->
            mergedApplication.appendChild(mergedDoc.importNode(activity, true))
        }

        val mergedXml = writeDocument(mergedDoc)
        val inserted = activityNodes.all { activity ->
            val className = activity.getAttributeNS(ANDROID_NS, "name")
            className.isNotBlank() && mergedXml.contains(className)
        }
        if (!inserted) {
            return MergeResult(
                manifest = mergedManifest,
                merged = false,
                failureReason = "activity entries were not inserted into merged manifest",
            )
        }
        return MergeResult(manifest = mergedXml, merged = true)
    }

    private fun newDocumentBuilder() =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()

    private fun org.w3c.dom.NodeList.toElementList(): List<Element> =
        buildList {
            for (index in 0 until length) {
                (item(index) as? Element)?.let(::add)
            }
        }

    private fun writeDocument(document: org.w3c.dom.Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString().trimEnd() + "\n"
    }
}
