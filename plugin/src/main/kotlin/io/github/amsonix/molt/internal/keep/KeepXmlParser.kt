package io.github.amsonix.molt.internal.keep

import io.github.amsonix.molt.ResourceKeepMerger
import io.github.amsonix.molt.ResourceKeepParser
import io.github.amsonix.molt.ResourceKeepResource
import java.io.File

/** shell-obfuscate 侧 keep 解析；实现见 [ResourceKeepParser]。 */
internal object KeepXmlParser {

    data class KeepResource(
        val type: String,
        val name: String,
    ) {
        constructor(resource: ResourceKeepResource) : this(resource.type, resource.name)

        fun toResourceKeep(): ResourceKeepResource = ResourceKeepResource(type, name)
    }

    fun parseKeepXml(xml: String): List<KeepResource> =
        ResourceKeepParser.parseKeepXml(xml).map(::KeepResource)

    /** keep.xml 声明条目（不含静态 baseline / 内置通配），供验包范围解析。 */
    fun parseDeclaredKeepXmlFiles(files: Iterable<File>): List<KeepResource> =
        ResourceKeepParser.mergeKeepXmlFiles(files).map(::KeepResource)

    fun mergeKeepXmlFiles(files: Iterable<File>): List<KeepResource> =
        ResourceKeepMerger.mergeShellKeepEntries(
            declared = ResourceKeepParser.mergeKeepXmlFiles(files),
        ).map(::KeepResource)
}

internal fun List<KeepXmlParser.KeepResource>.toResourceKeepList(): List<ResourceKeepResource> =
    map { it.toResourceKeep() }
