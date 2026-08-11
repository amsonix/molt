package io.github.amsonix.molt

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File
import java.nio.file.Files

/** TestKit 宿主工程：支持按 AGP / Gradle 版本参数化。 */
object AgpTestFixture {

    data class Config(
        val agpVersion: String,
        val gradleVersion: String,
    )

    data class RunContext(
        val projectDir: File,
        val gradleUserHome: File,
        val config: Config,
    )

    const val DEFAULT_AGP = "8.13.2"
    const val DEFAULT_GRADLE = "8.13"

    fun configFromEnvironment(): Config = Config(
        agpVersion = sequenceOf(
            System.getProperty("MOLT_TEST_AGP"),
            System.getenv("MOLT_TEST_AGP"),
        ).firstOrNull { !it.isNullOrBlank() } ?: DEFAULT_AGP,
        gradleVersion = sequenceOf(
            System.getProperty("MOLT_TEST_GRADLE"),
            System.getenv("MOLT_TEST_GRADLE"),
        ).firstOrNull { !it.isNullOrBlank() } ?: DEFAULT_GRADLE,
    )

    fun androidSdk(): File =
        sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "${System.getProperty("user.home")}/Library/Android/sdk",
        ).firstOrNull { !it.isNullOrBlank() && File(it).isDirectory }
            ?.let(::File)
            ?: File("")

    /** rename E2E 后断言 Crashlytics upload 指向合成 mapping。 */
    const val CRASHLYTICS_ASSERT_AFTER_MERGE = ":app:assertCrashlyticsShellMappingAfterMerge"

    const val CRASHLYTICS_ASSERT_SMOKE = ":app:assertCrashlyticsShellMapping"

    fun transformE2eEnabled(): Boolean =
        System.getenv("RUN_SHELL_TRANSFORM_E2E") == "1" ||
            System.getProperty("RUN_SHELL_TRANSFORM_E2E") == "1"

    /** AGP 可编译的最高 API；避免 fixture 使用 SDK 最新 platform 导致旧 AGP 秒失败。 */
    fun maxCompileSdkForAgp(agpVersion: String): Int {
        val parts = agpVersion.split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 8 }
        val minor = parts.getOrElse(1) { 0 }
        return when {
            major >= 9 && minor >= 1 -> 37
            major >= 9 -> 36
            major > 8 || minor >= 12 -> 36
            minor >= 10 -> 35
            minor >= 9 -> 35
            minor >= 3 -> 34
            minor >= 2 -> 34
            else -> 33
        }
    }

    /** Crashlytics Gradle 3.x 需 AGP 8.8+；更早 AGP 用 2.9.9。 */
    fun crashlyticsGradlePluginForAgp(agpVersion: String): String {
        val parts = agpVersion.split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 8 }
        val minor = parts.getOrElse(1) { 0 }
        return if (major > 8 || minor >= 8) "3.0.3" else "2.9.9"
    }

    /** UploadMappingFileTask.mergedMappingFile 仅 Crashlytics Gradle 3.x 提供。 */
    fun supportsCrashlyticsMergedMappingFile(agpVersion: String): Boolean =
        crashlyticsGradlePluginForAgp(agpVersion).startsWith("3.")

    private fun crashlyticsAssertTaskBlock(): String =
        """
            tasks.register('assertCrashlyticsShellMapping') {
                doLast {
                    def upload = tasks.named('uploadCrashlyticsMappingFileGoogleRelease').get()
                    def merge = tasks.named('moltObfuscateMergeMappingGoogleRelease').get()
                    assert upload.taskDependencies.getDependencies(upload).contains(merge) :
                        'uploadCrashlyticsMappingFileGoogleRelease must depend on moltObfuscateMergeMappingGoogleRelease'
                    def hasHook = upload.metaClass.respondsTo(upload, 'getMergedMappingFile') ||
                        upload.metaClass.respondsTo(upload, 'getMappingFileProvider')
                    assert hasHook :
                        'Crashlytics upload task must expose mergedMappingFile or mappingFileProvider'
                }
            }
            ${crashlyticsAssertAfterMergeTaskBlock()}
            """.trimIndent()

    private fun crashlyticsAssertAfterMergeTaskBlock(): String =
        """
            tasks.register('assertCrashlyticsShellMappingAfterMerge') {
                dependsOn 'moltObfuscateMergeMappingGoogleRelease'
                doLast {
                    def shellMapping = file('build/outputs/mapping/googleRelease/shell-obfuscate-mapping.txt')
                    assert shellMapping.isFile() : "shell-obfuscate-mapping.txt missing at ${'$'}shellMapping"
                    def upload = tasks.named('uploadCrashlyticsMappingFileGoogleRelease').get()
                    def merge = tasks.named('moltObfuscateMergeMappingGoogleRelease').get()
                    def wired = false
                    if (upload.metaClass.respondsTo(upload, 'getMergedMappingFile') && upload.mergedMappingFile != null) {
                        wired = true
                        assert upload.mergedMappingFile.get().asFile.absolutePath == shellMapping.absolutePath
                    } else if (upload.metaClass.respondsTo(upload, 'getMappingFileProvider') && upload.mappingFileProvider != null) {
                        wired = true
                        assert upload.mappingFileProvider.get().files.any {
                            it.absolutePath == shellMapping.absolutePath
                        }
                    }
                    assert wired :
                        'Crashlytics upload task must expose mergedMappingFile or mappingFileProvider'
                    assert upload.taskDependencies.getDependencies(upload).contains(merge)
                }
            }
            """.trimIndent()

    private fun crashlyticsUploadTaskRegistration(): String {
        val taskType = "com.google.firebase.crashlytics.buildtools.gradle.tasks.UploadMappingFileTask"
        return """
            tasks.register(
                'uploadCrashlyticsMappingFileGoogleRelease',
                $taskType
            )
            """.trimIndent()
    }

    fun findReleaseApk(projectDir: File, agpVersion: String, variantName: String = "googleRelease"): File? {
        fun isReleaseApk(file: File): Boolean =
            file.isFile && file.extension == "apk" && !file.name.startsWith("mapping-")

        val buildDir = File(projectDir, "app/build")
        val buildType = "release"

        apkOutputDirectoryCandidates(buildDir, variantName, buildType, agpVersion).forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown().firstOrNull(::isReleaseApk)?.let { return it }
        }

        listOf(
            File(buildDir, "intermediates/apk"),
            File(buildDir, "intermediates/packaged_apk"),
        ).filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().firstOrNull(::isReleaseApk)?.let { return it }
        }

        File(buildDir, "outputs/apk").takeIf { it.isDirectory }
            ?.walkTopDown()?.firstOrNull(::isReleaseApk)?.let { return it }

        return null
    }

    fun describeApkSearchPaths(projectDir: File, agpVersion: String): String {
        val buildDir = File(projectDir, "app/build")
        val candidates = apkOutputDirectoryCandidates(buildDir, "googleRelease", "release", agpVersion)
        val listing = candidates.joinToString { dir ->
            if (!dir.isDirectory) {
                "$dir (missing)"
            } else {
                val names = dir.listFiles()?.map { it.name }.orEmpty()
                "$dir -> ${names.ifEmpty { listOf("(empty)") }}"
            }
        }
        return "candidates: $listing"
    }

    private fun apkOutputDirectoryCandidates(
        buildDir: File,
        variantName: String,
        buildType: String,
        agpVersion: String,
    ): List<File> {
        val usesSplitOutput = isAgpAtLeast(agpVersion, "8.7.0")
        val flavor = extractFlavorSegment(variantName, buildType)
        return buildList {
            if (usesSplitOutput && flavor != null) {
                add(File(buildDir, "outputs/apk/$flavor/$buildType"))
            }
            add(File(buildDir, "outputs/apk/$variantName"))
            if (!variantName.equals(buildType, ignoreCase = true)) {
                add(File(buildDir, "outputs/apk/$buildType"))
            }
        }.distinctBy { it.absoluteFile.normalize().path }
    }

    private fun extractFlavorSegment(variantName: String, buildType: String): String? {
        if (variantName.equals(buildType, ignoreCase = true)) return null
        if (!variantName.endsWith(buildType, ignoreCase = true)) return null
        return variantName.dropLast(buildType.length).takeIf { it.isNotEmpty() }
    }

    private fun isAgpAtLeast(current: String, minimum: String): Boolean {
        fun parse(version: String): List<Int> =
            version.split('.').map { segment -> segment.toIntOrNull() ?: 0 }
        val currentParts = parse(current)
        val minimumParts = parse(minimum)
        val length = maxOf(currentParts.size, minimumParts.size)
        for (index in 0 until length) {
            val currentPart = currentParts.getOrElse(index) { 0 }
            val minimumPart = minimumParts.getOrElse(index) { 0 }
            if (currentPart != minimumPart) return currentPart > minimumPart
        }
        return true
    }

    fun findReleaseAab(
        projectDir: File,
        agpVersion: String = DEFAULT_AGP,
        variantName: String = "googleRelease",
    ): File? {
        fun isReleaseAab(file: File): Boolean =
            file.isFile && file.extension == "aab"

        val buildDir = File(projectDir, "app/build")
        val buildType = "release"

        aabOutputDirectoryCandidates(buildDir, variantName, buildType, agpVersion).forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown().firstOrNull(::isReleaseAab)?.let { return it }
        }

        listOf(
            File(buildDir, "intermediates/intermediary_bundle"),
            File(buildDir, "intermediates/bundle"),
        ).filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().firstOrNull(::isReleaseAab)?.let { return it }
        }

        File(buildDir, "outputs/bundle").takeIf { it.isDirectory }
            ?.walkTopDown()?.firstOrNull(::isReleaseAab)?.let { return it }

        return buildDir.walkTopDown()
            .filter { file ->
                file.isFile && file.extension == "aab" &&
                    !file.path.contains("${File.separator}cache${File.separator}")
            }
            .maxByOrNull { it.lastModified() }
    }

    fun describeAabSearchPaths(projectDir: File, agpVersion: String): String {
        val buildDir = File(projectDir, "app/build")
        val candidates = aabOutputDirectoryCandidates(buildDir, "googleRelease", "release", agpVersion)
        val listing = candidates.joinToString { dir ->
            if (!dir.isDirectory) {
                "$dir (missing)"
            } else {
                val names = dir.listFiles()?.map { it.name }.orEmpty()
                "$dir -> ${names.ifEmpty { listOf("(empty)") }}"
            }
        }
        return "candidates: $listing"
    }

    private fun aabOutputDirectoryCandidates(
        buildDir: File,
        variantName: String,
        buildType: String,
        agpVersion: String,
    ): List<File> {
        val usesSplitOutput = isAgpAtLeast(agpVersion, "8.7.0")
        val flavor = extractFlavorSegment(variantName, buildType)
        return buildList {
            if (usesSplitOutput && flavor != null) {
                add(File(buildDir, "outputs/bundle/$flavor/$buildType"))
            }
            add(File(buildDir, "outputs/bundle/$variantName"))
            if (!variantName.equals(buildType, ignoreCase = true)) {
                add(File(buildDir, "outputs/bundle/$buildType"))
            }
        }.distinctBy { it.absoluteFile.normalize().path }
    }

    fun resolveCompileSdk(sdk: File, agpVersion: String): Int {
        val installedMax = File(sdk, "platforms").listFiles()
            .orEmpty()
            .mapNotNull { it.name.removePrefix("android-").toIntOrNull() }
            .maxOrNull() ?: 35
        return minOf(installedMax, maxCompileSdkForAgp(agpVersion))
    }

    /** Gradle 8.5 以下无法以 JDK 21 运行；TestKit 需指定 JDK 17。 */
    fun requiresJdk17ForGradle(gradleVersion: String): Boolean {
        val parts = gradleVersion.split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 8 }
        val minor = parts.getOrElse(1) { 0 }
        return major < 8 || (major == 8 && minor < 5)
    }

    private fun javaMajorVersion(javaHome: File): Int? = runCatching {
        ProcessBuilder(javaHome.resolve("bin/java").absolutePath, "-version")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader()
            .readText()
            .let { Regex("""version "(\d+)""").find(it)?.groupValues?.get(1)?.toInt() }
    }.getOrNull()

    private fun isProbeCompatibleJavaHome(javaHome: File): Boolean {
        if (!javaHome.isDirectory || !javaHome.resolve("bin/java").canExecute()) return false
        val major = javaMajorVersion(javaHome) ?: return false
        return major in 17..21
    }

    /** Gradle 8.0–8.4 的 Groovy 无法加载 Java 21 classfile；探针 JVM 必须锁定 17。 */
    private fun isProbeJavaHomeForGradle(javaHome: File, gradleVersion: String): Boolean {
        if (!isProbeCompatibleJavaHome(javaHome)) return false
        if (!requiresJdk17ForGradle(gradleVersion)) return true
        return javaMajorVersion(javaHome) == 17
    }

    private fun androidStudioJbrHomes(): List<File> =
        listOf(
            "/Applications/Android Studio.app",
            "${System.getProperty("user.home")}/Applications/Android Studio.app",
            "/Applications/Android Studio Preview.app",
        ).flatMap { app ->
            listOf("Contents/jbr/Contents/Home", "Contents/jre/Contents/Home").map { sub ->
                File(app, sub)
            }
        }

    fun probeJavaHome(config: Config): File? {
        if (!requiresJdk17ForGradle(config.gradleVersion)) return null

        sequenceOf(
            System.getenv("MOLT_PROBE_JAVA_HOME"),
            System.getProperty("MOLT_PROBE_JAVA_HOME"),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.let(::File)
            ?.takeIf { isProbeJavaHomeForGradle(it, config.gradleVersion) }
            ?.let { return it }

        // GitHub Actions / CI 通用：标准 JAVA_HOME（setup-java 等）。此前只找 mac 路径导致 ubuntu 上 Gradle 8.0–8.4 探针必挂。
        System.getenv("JAVA_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { isProbeJavaHomeForGradle(it, config.gradleVersion) }
            ?.let { return it }

        androidStudioJbrHomes()
            .firstOrNull { isProbeJavaHomeForGradle(it, config.gradleVersion) }
            ?.let { return it }

        runCatching {
            ProcessBuilder("/usr/libexec/java_home", "-v", "17")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader()
                .readText()
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let(::File)
        }.getOrNull()
            ?.takeIf { isProbeJavaHomeForGradle(it, config.gradleVersion) }
            ?.let { return it }

        val home = System.getProperty("user.home")
        listOf(
            "$home/Library/Java/JavaVirtualMachines",
            "/Library/Java/JavaVirtualMachines",
        ).flatMap { base ->
            File(base).listFiles().orEmpty()
                .filter { it.name.contains("17") }
                .mapNotNull { dir ->
                    listOf(File(dir, "Contents/Home"), dir).firstOrNull { it.isDirectory }
                }
        }.firstOrNull { isProbeJavaHomeForGradle(it, config.gradleVersion) }?.let { return it }

        listOf(
            "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home",
            "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home",
        ).map(::File)
            .firstOrNull { isProbeJavaHomeForGradle(it, config.gradleVersion) }
            ?.let { return it }

        return null
    }

    fun newRunContext(config: Config = configFromEnvironment()): RunContext {
        val suffix = config.agpVersion.replace('.', '-')
        val projectDir = Files.createTempDirectory("molt-agp-$suffix").toFile()
        val gradleUserHome = sequenceOf(
            System.getenv("MOLT_PROBE_GRADLE_USER_HOME"),
            System.getProperty("MOLT_PROBE_GRADLE_USER_HOME"),
        ).firstOrNull { !it.isNullOrBlank() }?.let(::File)
            ?: File(projectDir, ".gradle-user-home").also { it.mkdirs() }
        return RunContext(
            projectDir = projectDir,
            gradleUserHome = gradleUserHome,
            config = config,
        )
    }

    fun defaultProbeBuildArgs(vararg tasks: String): Array<String> =
        arrayOf(*tasks, "--no-build-cache", "--stacktrace", "--console=plain")

    fun createRunner(context: RunContext): GradleRunner {
        // withEnvironment 会整体替换嵌套构建的环境，必须先把系统环境并进来，
        // 否则 ANDROID_HOME / ANDROID_SDK_ROOT / PATH 等全部丢失
        // （Linux CI 上 zipalign 定位失败即源于此）。
        val env = linkedMapOf<String, String>().apply {
            putAll(System.getenv())
            put("GRADLE_USER_HOME", context.gradleUserHome.absolutePath)
            put("GRADLE_OPTS", probeGradleOpts())
        }
        probeJavaHome(context.config)?.let { javaHome ->
            env["JAVA_HOME"] = javaHome.absolutePath
        } ?: check(!requiresJdk17ForGradle(context.config.gradleVersion)) {
            "Gradle ${context.config.gradleVersion} cannot run on JDK 21. " +
                "Set MOLT_PROBE_JAVA_HOME to JDK 17 " +
                "(e.g. export MOLT_PROBE_JAVA_HOME=\$(/usr/libexec/java_home -v 17))"
        }
        chinaMirrorEnv().forEach { (key, value) -> env[key] = value }

        var runner = GradleRunner.create()
            .withProjectDir(context.projectDir)
            .withPluginClasspath(resolvePluginClasspath())
            .withGradleVersion(context.config.gradleVersion)
            .withEnvironment(env)

        probeInitScript()?.let { initScript ->
            runner = runner.withArguments(
                "--init-script",
                initScript.absolutePath,
            )
        }
        return runner
    }

    private fun probeGradleOpts(): String =
        "-Dhttps.protocols=TLSv1.2,TLSv1.3 -Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"

    private fun chinaMirrorEnv(): Map<String, String> {
        if (!chinaMirrorEnabled()) return emptyMap()
        return mapOf("MOLT_PROBE_CHINA_MIRROR" to "1")
    }

    fun chinaMirrorEnabled(): Boolean =
        sequenceOf(
            System.getenv("MOLT_PROBE_CHINA_MIRROR"),
            System.getProperty("MOLT_PROBE_CHINA_MIRROR"),
        ).firstOrNull()?.let { value ->
            value != "0" && !value.equals("false", ignoreCase = true)
        } ?: true

    private fun probeInitScript(): File? {
        sequenceOf(
            System.getenv("MOLT_PROBE_INIT_SCRIPT"),
            System.getProperty("MOLT_PROBE_INIT_SCRIPT"),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { return it }

        System.getProperty("MOLT_REPO_ROOT")?.takeIf { it.isNotBlank() }?.let { root ->
            File(root, "gradle/agp-probe.init.gradle").takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    private fun probeRepositoryLines(includeGradlePluginPortal: Boolean = true): String {
        val lines = mutableListOf<String>()
        sequenceOf(
            System.getenv("MOLT_PROBE_MAVEN_MIRROR"),
            System.getProperty("MOLT_PROBE_MAVEN_MIRROR"),
        ).firstOrNull { !it.isNullOrBlank() }?.let { mirror ->
            lines += """maven { name = 'MoltProbeMirror'; url = uri("$mirror") }"""
        }
        if (chinaMirrorEnabled()) {
            lines += listOf(
                "maven { name = 'AliyunGoogle'; url = uri('https://maven.aliyun.com/repository/google') }",
                "maven { name = 'AliyunGradlePlugin'; url = uri('https://maven.aliyun.com/repository/gradle-plugin') }",
                "maven { name = 'AliyunPublic'; url = uri('https://maven.aliyun.com/repository/public') }",
            )
        }
        lines += listOf("google()", "mavenCentral()")
        if (includeGradlePluginPortal) {
            lines += "gradlePluginPortal()"
        }
        return lines.joinToString("\n                    ")
    }

    private fun resolvePluginClasspath(): List<File> {
        val raw = System.getProperty("MOLT_PLUGIN_CLASSPATH")
            ?: error(
                "MOLT_PLUGIN_CLASSPATH is not set. " +
                    "Ensure the Test task depends on pluginUnderTestMetadata.",
            )
        return raw.split(File.pathSeparatorChar)
            .map { File(it) }
            .filter { it.isFile }
            .also { jars ->
                check(jars.isNotEmpty()) { "Plugin classpath is empty" }
                check(jars.any { isAndroidGradlePluginJar(it) }) {
                    "Plugin classpath must include AGP (-PtestAgp) so molt can load AppExtension"
                }
            }
    }

    private fun isAndroidGradlePluginJar(file: File): Boolean =
        file.path.contains("${File.separator}com.android.tools.build${File.separator}gradle${File.separator}") &&
            file.name.startsWith("gradle-")

    fun build(runner: GradleRunner, config: Config): org.gradle.testkit.runner.BuildResult =
        try {
            runner.build()
        } catch (error: UnexpectedBuildFailure) {
            throw AssertionError(
                "Gradle build failed for AGP ${config.agpVersion} / Gradle ${config.gradleVersion}:\n" +
                    error.buildResult.output,
                error,
            )
        }

    fun cleanup(context: RunContext) {
        context.projectDir.deleteRecursively()
    }

    fun writeFixture(context: RunContext, sdk: File) {
        writeFixture(context.projectDir, sdk, context.config)
    }

    fun writeFixture(root: File, sdk: File, config: Config = configFromEnvironment()) {
        write(
            root,
            "settings.gradle",
            """
            pluginManagement {
                plugins {
                    id 'com.android.application' version '${config.agpVersion}'
                    id 'com.android.library' version '${config.agpVersion}'
                }
                repositories {
                    ${probeRepositoryLines()}
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    ${probeRepositoryLines(includeGradlePluginPortal = false)}
                }
            }
            rootProject.name = 'shell-obfuscate-fixture'
            include ':app', ':library'
            """.trimIndent(),
        )
        write(
            root,
            "build.gradle",
            """
            buildscript {
                repositories {
                    ${probeRepositoryLines(includeGradlePluginPortal = false)}
                }
                dependencies {
                    classpath 'com.google.firebase:firebase-crashlytics-gradle:${crashlyticsGradlePluginForAgp(config.agpVersion)}'
                }
            }
            """.trimIndent(),
        )
        write(root, "local.properties", "sdk.dir=${sdk.invariantSeparatorsPath}\n")
        probeJavaHome(config)?.let { javaHome ->
            write(
                root,
                "gradle.properties",
                "org.gradle.java.home=${javaHome.invariantSeparatorsPath}\n",
            )
        }
        write(root, "app/proguard-rules.pro", "# probe fixture\n")
        val compileSdk = resolveCompileSdk(sdk, config.agpVersion)

        write(
            root,
            "app/build.gradle",
            """
            plugins {
                id 'com.android.application'
                id 'io.github.amsonix.molt'
            }

            android {
                namespace 'fixture.app'
                compileSdk $compileSdk
                defaultConfig {
                    applicationId 'fixture.app'
                    minSdk 23
                    targetSdk $compileSdk
                    versionCode 1
                    versionName '1.0'
                }
                flavorDimensions 'channel'
                productFlavors {
                    google { dimension 'channel' }
                    samsung { dimension 'channel' }
                }
                buildTypes {
                    release {
                        minifyEnabled true
                        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
                    }
                }
            }

            dependencies {
                implementation project(':library')
            }

            molt {
                enabledBuildTypes.set(['release'])
                seed.set(7)
                junkCode.packagePrefix.set('fixture.custom.junk')
                resourceObfuscate.renameXmlFiles.set(false)
                resourceObfuscate.injectXmlJunk.set(false)
                resourceObfuscate.imageAntiDetect.set(false)
                bundleResourceObfuscate.enabled.set(false)
                bundleResourceObfuscate.obfuscateApk.set(false)
            }

            ${crashlyticsUploadTaskRegistration()}
            ${crashlyticsAssertTaskBlock()}
            """.trimIndent(),
        )
        write(
            root,
            "library/build.gradle",
            """
            plugins {
                id 'com.android.library'
            }

            android {
                namespace 'fixture.lib'
                compileSdk $compileSdk
                defaultConfig { minSdk 23 }
                buildTypes {
                    release { minifyEnabled false }
                }
            }
            """.trimIndent(),
        )
        write(root, "app/src/main/AndroidManifest.xml", "<manifest />")
        write(
            root,
            "app/src/main/res/values/crashlytics_probe_values.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="google_app_id" translatable="false">1:000000000000:android:0000000000000000000000</string>
                <string name="gcm_defaultSenderId" translatable="false">000000000000</string>
                <string name="google_api_key" translatable="false">AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</string>
                <string name="google_crash_reporting_api_key" translatable="false">AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</string>
                <string name="project_id" translatable="false">fixture-probe</string>
            </resources>
            """.trimIndent(),
        )
        write(root, "app/src/main/res/layout/base.xml", "<FrameLayout />")
        write(root, "app/src/google/res/layout/google.xml", "<FrameLayout />")
        write(root, "app/src/samsung/res/layout/samsung.xml", "<FrameLayout />")
        write(
            root,
            "library/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <activity android:name=".LibraryActivity" />
                </application>
            </manifest>
            """.trimIndent(),
        )
        write(
            root,
            "library/src/main/java/fixture/lib/LibraryActivity.java",
            """
            package fixture.lib;
            public class LibraryActivity extends android.app.Activity {}
            """.trimIndent(),
        )
    }

    fun write(root: File, relativePath: String, content: String) {
        File(root, relativePath).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    fun writeLauncherActivity(root: File) {
        // 幂等：preset 可能已定制 MainActivity（如 string-fog-assets 的 marker），不得覆盖。
        val mainActivity = File(root, "app/src/main/java/fixture/app/MainActivity.java")
        if (!mainActivity.isFile) {
            write(
                root,
                "app/src/main/java/fixture/app/MainActivity.java",
                """
                package fixture.app;
                public class MainActivity extends android.app.Activity {}
                """.trimIndent(),
            )
        }
        File(root, "app/src/main/AndroidManifest.xml").writeText(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent(),
        )
    }

    private fun ensureReleaseUsesDebugSigning(appGradleText: String): String {
        if (appGradleText.contains("signingConfig signingConfigs.debug")) {
            return appGradleText
        }
        return appGradleText.replace(
            """
                    release {
                        minifyEnabled
            """.trimIndent(),
            """
                    release {
                        signingConfig signingConfigs.debug
                        minifyEnabled
            """.trimIndent(),
        )
    }

    fun configureApkTransformFixture(root: File, enableRename: Boolean) {
        val appGradle = File(root, "app/build.gradle")
        var text = appGradle.readText()
        if (!enableRename) {
            text = text.replace("minifyEnabled true", "minifyEnabled false")
        }
        text = text.replace(
            "bundleResourceObfuscate.obfuscateApk.set(false)",
            "bundleResourceObfuscate.obfuscateApk.set(true)",
        )
        text = ensureReleaseUsesDebugSigning(text)
        val renameBlock = if (enableRename) {
            """
            molt {
                allowUnsignedOutput.set(true)
                syncBaselineProfile.set(false)
            }
            """.trimIndent()
        } else {
            """
            molt {
                componentRename.enabled.set(false)
                viewRename.enabled.set(false)
                allowUnsignedOutput.set(true)
                syncBaselineProfile.set(false)
            }
            """.trimIndent()
        }
        appGradle.writeText("$text\n\n$renameBlock\n")
        writeLauncherActivity(root)
    }

    fun configureAabTransformFixture(root: File, enableRename: Boolean) {
        val appGradle = File(root, "app/build.gradle")
        var text = appGradle.readText()
        if (!enableRename) {
            text = text.replace("minifyEnabled true", "minifyEnabled false")
        }
        text = text.replace(
            "bundleResourceObfuscate.enabled.set(false)",
            "bundleResourceObfuscate.enabled.set(true)",
        )
        text = ensureReleaseUsesDebugSigning(text)
        val renameBlock = if (enableRename) {
            """
            molt {
                allowUnsignedOutput.set(true)
                syncBaselineProfile.set(false)
            }
            """.trimIndent()
        } else {
            """
            molt {
                componentRename.enabled.set(false)
                viewRename.enabled.set(false)
                allowUnsignedOutput.set(true)
                syncBaselineProfile.set(false)
            }
            """.trimIndent()
        }
        appGradle.writeText("$text\n\n$renameBlock\n")
        writeLauncherActivity(root)
    }
}
