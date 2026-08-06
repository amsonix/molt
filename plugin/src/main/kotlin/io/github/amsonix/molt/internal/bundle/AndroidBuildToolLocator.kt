package io.github.amsonix.molt.internal.bundle

import java.io.File

/** 统一定位 Android SDK build-tools，覆盖环境变量与各平台默认安装目录。 */
internal object AndroidBuildToolLocator {

    @JvmStatic
    fun locate(toolName: String): File? = locate(
        toolName = toolName,
        environment = System.getenv(),
        userHome = File(System.getProperty("user.home")),
        osName = System.getProperty("os.name"),
    )

    @JvmStatic
    fun require(toolName: String): File {
        val environment = System.getenv()
        val userHome = File(System.getProperty("user.home"))
        val osName = System.getProperty("os.name")
        return locate(toolName, environment, userHome, osName)
            ?: throw IllegalStateException(missingToolMessage(toolName, environment, userHome, osName))
    }

    internal fun locate(
        toolName: String,
        environment: Map<String, String>,
        userHome: File,
        osName: String,
    ): File? {
        val isWindows = osName.startsWith("Windows", ignoreCase = true)
        val executableNames = executableNames(toolName, isWindows)
        return sdkRoots(environment, userHome, isWindows)
            .asSequence()
            .mapNotNull { root -> locateInSdk(root, toolName, isWindows, executableNames) }
            .firstOrNull()
    }

    /** Scan a single SDK root (e.g. from [com.android.build.api.dsl.SdkComponents.sdkDirectory]). */
    @JvmStatic
    fun locateInSdk(sdkRoot: File, toolName: String): File? {
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        return locateInSdk(sdkRoot, toolName, isWindows, executableNames(toolName, isWindows))
    }

    internal fun locateInSdk(
        sdkRoot: File,
        toolName: String,
        isWindows: Boolean,
        executableNames: List<String>,
    ): File? {
        val buildTools = File(sdkRoot, "build-tools")
        if (!buildTools.isDirectory) return null
        return buildTools.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .sortedWith(compareByDescending(BuildToolsVersionOrder) { it.name })
            .flatMap { directory -> executableNames.asSequence().map { File(directory, it) } }
            .firstOrNull { candidate ->
                candidate.isFile && (isWindows || candidate.canExecute())
            }
    }

    internal fun missingToolMessage(
        toolName: String,
        environment: Map<String, String>,
        userHome: File,
        osName: String,
    ): String {
        val isWindows = osName.startsWith("Windows", ignoreCase = true)
        val roots = sdkRoots(environment, userHome, isWindows)
        val names = executableNames(toolName, isWindows)
        return "$toolName not found in Android SDK build-tools; " +
            "set ANDROID_HOME or ANDROID_SDK_ROOT. " +
            "searchedRoots=${roots.joinToString { it.path }} executableNames=${names.joinToString()}"
    }

    private fun sdkRoots(
        environment: Map<String, String>,
        userHome: File,
        isWindows: Boolean,
    ): List<File> = buildList {
        environment["ANDROID_HOME"]?.takeIf(String::isNotBlank)?.let { add(File(it)) }
        environment["ANDROID_SDK_ROOT"]?.takeIf(String::isNotBlank)?.let { add(File(it)) }
        add(File(userHome, "Library/Android/sdk"))
        add(File(userHome, "Android/Sdk"))
        if (isWindows) {
            environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)
                ?.let { add(File(it, "Android/Sdk")) }
            add(File(userHome, "AppData/Local/Android/Sdk"))
        }
    }.distinctBy { it.absoluteFile.normalize().path }

    private fun executableNames(toolName: String, isWindows: Boolean): List<String> {
        if (!isWindows) return listOf(toolName)
        return if (toolName == "apksigner") {
            listOf("apksigner.bat", "apksigner.exe")
        } else {
            listOf("$toolName.exe", "$toolName.bat")
        }
    }
}

/** 按 major.minor.patch 数值比较，避免 "9.0.0" > "36.1.0" 的字符串排序陷阱。 */
internal object BuildToolsVersionOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val left = parse(a)
        val right = parse(b)
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val leftValue = left.getOrElse(index) { 0 }
            val rightValue = right.getOrElse(index) { 0 }
            if (leftValue != rightValue) return leftValue.compareTo(rightValue)
        }
        val leftPreRelease = preRelease(a)
        val rightPreRelease = preRelease(b)
        return when {
            leftPreRelease == null && rightPreRelease != null -> 1
            leftPreRelease != null && rightPreRelease == null -> -1
            leftPreRelease != null && rightPreRelease != null ->
                leftPreRelease.compareTo(rightPreRelease)
            else -> 0
        }
    }

    fun parse(name: String): List<Int> =
        name.split('.', '-').mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
            .ifEmpty { listOf(0) }

    private fun preRelease(name: String): Int? =
        Regex("""-(?:alpha|beta|rc)(\d*)""", RegexOption.IGNORE_CASE)
            .find(name)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: if (name.contains('-')) 0 else null
}
