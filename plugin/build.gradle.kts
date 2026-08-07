plugins {
    `kotlin-dsl`
    java
    `maven-publish`
}

import groovy.util.Node
import groovy.util.NodeList
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata
import java.util.Properties

group = "io.github.amsonix.molt"
version = providers.gradleProperty("moltVersion").orElse("1.1.0").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "2g"
    dependsOn("compileKotlin", "pluginUnderTestMetadata")
    doFirst {
        val metadata = layout.buildDirectory
            .file("pluginUnderTestMetadata/plugin-under-test-metadata.properties")
            .get().asFile
        check(metadata.isFile) {
            "Missing $metadata — pluginUnderTestMetadata must run before TestKit probes"
        }
        val props = Properties().apply { metadata.inputStream().use { load(it) } }
        val agpVersion = providers.gradleProperty("testAgp").orElse("8.13.2").get()
        systemProperty("MOLT_TEST_AGP", agpVersion)
        systemProperty("MOLT_TEST_GRADLE", providers.gradleProperty("testGradle").orElse("8.13").get())
        systemProperty(
            "MOLT_PLUGIN_CLASSPATH",
            probePluginClasspath(
                moltClasspath = filterPluginUnderTestClasspath(props),
                agpVersion = agpVersion,
            ),
        )
        systemProperty("MOLT_REPO_ROOT", rootProject.projectDir.absolutePath)
        providers.environmentVariable("MOLT_PROBE_JAVA_HOME").orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { javaHome -> systemProperty("MOLT_PROBE_JAVA_HOME", javaHome) }
        providers.gradleProperty("moltFeature").orNull?.let { featureId ->
            systemProperty("MOLT_FEATURE_PROBE", featureId)
        }
        if (providers.environmentVariable("MOLT_PROBE_CHINA_MIRROR").orNull != "0") {
            systemProperty("MOLT_PROBE_CHINA_MIRROR", "1")
        }
    }
}

fun isHostGradleToolchainJar(path: String): Boolean {
    val normalized = path.replace('\\', '/')
    if (normalized.contains("generated-gradle-jars")) return true
    if (Regex("""/\.gradle/(caches|wrapper/dists)/8\.1[3-9]/""").containsMatchIn(normalized)) return true
    if (Regex("""/gradle-8\.1[3-9]-""").containsMatchIn(normalized)) return true
    val name = File(path).name
    return name.startsWith("gradle-api-") ||
        name.startsWith("gradle-kotlin-dsl-") ||
        name.startsWith("gradle-installation-beacon-") ||
        name.startsWith("gradle-worker-services-") ||
        name.startsWith("gradle-runtime-api-info-")
}

/** 宿主 AGP 8.13 的 aapt2-proto 为 Java 21；Gradle 8.0–8.4 TestKit 无法加载，改由 probe AGP 传递依赖提供。 */
fun isHostPinnedAapt2Proto(path: String): Boolean =
    path.replace('\\', '/').contains("aapt2-proto")

fun filterPluginUnderTestClasspath(props: Properties): String =
    props.getProperty("implementation-classpath")
        .split(File.pathSeparatorChar)
        .filter { path ->
            path.isNotBlank() &&
                !path.contains("${File.separator}com.android.tools.build${File.separator}gradle${File.separator}") &&
                !path.contains("firebase-crashlytics-gradle") &&
                !isHostGradleToolchainJar(path) &&
                !isHostPinnedAapt2Proto(path)
        }
        .joinToString(File.pathSeparator)

/** Molt jar + runtime deps + AGP version under probe (must match -PtestAgp for TestKit to load AppExtension). */
fun probePluginClasspath(moltClasspath: String, agpVersion: String): String {
    val agpArtifacts = configurations.detachedConfiguration(
        dependencies.create("com.android.tools.build:gradle:$agpVersion"),
    ).apply { isTransitive = true }.resolve().map { it.absolutePath }
    return (moltClasspath.split(File.pathSeparatorChar).filter { it.isNotBlank() } + agpArtifacts)
        .distinct()
        .joinToString(File.pathSeparator)
}

tasks.withType<PluginUnderTestMetadata>().configureEach {
    // Jar + runtime only — never compileOnly AGP (would break multi-AGP matrix probes).
    pluginClasspath.setFrom(
        tasks.named<Jar>("jar").flatMap { it.archiveFile },
        configurations.runtimeClasspath,
    )
    doLast {
        val propsFile = outputDirectory.file("plugin-under-test-metadata.properties").get().asFile
        val props = Properties().apply { propsFile.inputStream().use { load(it) } }
        val filtered = filterPluginUnderTestClasspath(props)
        props.setProperty("implementation-classpath", filtered)
        propsFile.outputStream().use { props.store(it, "molt plugin under test — AGP added per probe row") }
    }
}

dependencies {
    implementation(project(":resource-keep"))
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.firebase.crashlytics.gradle.plugin)
    implementation("com.google.code.gson:gson:2.13.0")
    implementation("com.android.tools.build:bundletool:1.17.2")
    // compileOnly：避免 Java 21 的 8.13 proto 进入发布 classpath（Gradle 8.0 / AGP 8.0 宿主无法加载）。
    // 运行时由宿主 AGP 传递依赖提供；bundle/apk transform 需 AGP ≥ 8.0.2。
    compileOnly("com.android.tools.build:aapt2-proto:8.13.2-14304508")
    // 单元测试直接构建 com.android.aapt.Resources；不进 pluginUnderTestMetadata runtime。
    testImplementation("com.android.tools.build:aapt2-proto:8.13.2-14304508")
    implementation("com.google.guava:guava:32.1.3-jre")
    implementation("commons-io:commons-io:2.15.1")
    implementation("commons-codec:commons-codec:1.16.0")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("org.smali:dexlib2:2.5.2")
    compileOnly("org.jetbrains:annotations:24.1.0")
    testImplementation(libs.android.gradle.plugin)
    testImplementation(libs.firebase.crashlytics.gradle.plugin)
    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
}

// TestKit pluginClasspath = molt jar + runtime deps + matching AGP (-PtestAgp).
// Strip host Gradle 8.13 API jars so Gradle 8.0–8.2 TestKit can instrument the classpath.

tasks.named<Jar>("jar") {
    dependsOn(":resource-keep:classes")
    from(project(":resource-keep").sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

gradlePlugin {
    plugins {
        create("molt") {
            id = "io.github.amsonix.molt"
            implementationClass = "io.github.amsonix.molt.MoltObfuscatePlugin"
            displayName = "Molt"
            description =
                "Android vest-pack obfuscation: junk code, resource overlay, arsc, post-R8 component/view rename."
            tags.set(listOf("android", "obfuscation", "vest", "molt"))
        }
    }
}

val rootGradleProperties = Properties().apply {
    file("../gradle.properties").takeIf { it.isFile }?.inputStream()?.use { stream ->
        load(stream)
    }
}

fun nexusCredential(name: String): String? =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orNull
        ?: rootGradleProperties.getProperty(name)

publishing {
    repositories {
        val nexusUser = nexusCredential("NEXUS_USERNAME")
        val nexusPass = nexusCredential("NEXUS_PASSWORD")
        if (nexusUser != null && nexusPass != null) {
            maven {
                val isSnapshot = version.toString().endsWith("SNAPSHOT")
                url = uri(
                    if (isSnapshot) {
                        "https://nexus-vywrajy.micoworld.net/repository/gradle-snapshots/"
                    } else {
                        "https://nexus-vywrajy.micoworld.net/repository/gradle/"
                    },
                )
                credentials {
                    username = nexusUser
                    password = nexusPass
                }
                isAllowInsecureProtocol = true
            }
        }
    }
    publications.withType<MavenPublication>().configureEach {
        if (name != "pluginMaven") return@configureEach
        pom.withXml {
            val deps = asNode().get("dependencies") as? NodeList ?: return@withXml
            val depsNode = deps.firstOrNull() as? Node ?: return@withXml
            depsNode.children()
                .filterIsInstance<Node>()
                .filter { dep ->
                    (dep.get("artifactId") as? NodeList)?.text() == "resource-keep"
                }
                .forEach { depsNode.remove(it) }
        }
    }
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    if (name != "generateMetadataFileForPluginMavenPublication") return@configureEach
    doLast {
        val moduleFile = outputFile.asFile.get()
        @Suppress("UNCHECKED_CAST")
        val module = groovy.json.JsonSlurper().parseText(moduleFile.readText()) as MutableMap<String, Any>
        @Suppress("UNCHECKED_CAST")
        val variants = module["variants"] as? MutableList<MutableMap<String, Any>> ?: return@doLast
        variants.forEach { variant ->
            @Suppress("UNCHECKED_CAST")
            val deps = variant["dependencies"] as? MutableList<MutableMap<String, Any>> ?: return@forEach
            deps.removeIf { it["module"] == "resource-keep" }
            if (deps.isEmpty()) variant.remove("dependencies")
        }
        moduleFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(module)))
    }
}

fun integrationVariant(): String =
    providers.gradleProperty("integrationVariant").orElse("googleRelease").get()

fun sampleProjectDir(): File = rootProject.projectDir.resolve("sample")

fun optionalIntegrationRoot(): File? {
    providers.gradleProperty("integrationRoot").orNull?.let { return File(it) }
    System.getenv("MOLT_INTEGRATION_ROOT")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    return null
}

fun integrationApkReleaseDir(root: File): File =
    File(root, "app/build/outputs/apk/google/release")

fun hasIntegrationReleaseApk(root: File): Boolean =
    integrationApkReleaseDir(root).listFiles()
        .orEmpty()
        .any { it.isFile && it.name.endsWith(".apk") && !it.name.startsWith("mapping-rewrite-") }

/** 插件模块自定义任务名（非 moltObfuscate* 前缀）也归入 molt 分组。 */
private val moltPluginTaskNames = setOf(
    "publishMoltObfuscatePlugin",
    "publishMoltToGradlePortal",
    "quickDexVerify",
    "dexComponentRenameIntegrationTest",
    "dexMappingRewriteApkGeneratorTest",
    "dexIdentityRoundTripApkGeneratorTest",
    "moltObfuscateAgpCompatTest",
    "moltObfuscateAgpCompatE2eTest",
    "moltObfuscateAgpCompatBundleE2eTest",
    "moltObfuscateAgpCompatRenameApkE2eTest",
    "moltObfuscateAgpCompatRenameAabE2eTest",
    "moltObfuscateAgpCompatMatrix",
    "moltObfuscateFeatureProbeTest",
    "moltObfuscateFeatureProbeMatrix",
    "moltObfuscateFeatureProbeNightly",
)

fun registerAgpCompatTest(
    name: String,
    description: String,
    testMethod: String,
    requireE2e: Boolean = false,
) {
    tasks.register<Test>(name) {
        this.description = description
        group = "molt"
        dependsOn("testClasses", "pluginUnderTestMetadata")
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("io.github.amsonix.molt.AgpCompatibilityTest.$testMethod")
        doFirst {
            if (requireE2e) {
                systemProperty("RUN_SHELL_TRANSFORM_E2E", "1")
            }
        }
    }
}

registerAgpCompatTest(
    name = "moltObfuscateAgpCompatTest",
    description = "AGP matrix smoke: molt prepare/resources/junk (-PtestAgp -PtestGradle)",
    testMethod = "smoke_moltTasksRunAgainstConfiguredAgp",
)

registerAgpCompatTest(
    name = "moltObfuscateAgpCompatE2eTest",
    description = "AGP probe E2E: assemble + APK transform (-PtestAgp -PtestGradle)",
    testMethod = "smoke_assembleReleaseApkTransformAgainstConfiguredAgp",
    requireE2e = true,
)

registerAgpCompatTest(
    name = "moltObfuscateAgpCompatBundleE2eTest",
    description = "AGP probe E2E: bundle + AAB transform (-PtestAgp -PtestGradle)",
    testMethod = "smoke_bundleReleaseAabTransformAgainstConfiguredAgp",
    requireE2e = true,
)

registerAgpCompatTest(
    name = "moltObfuscateAgpCompatRenameApkE2eTest",
    description = "AGP probe E2E: assemble + APK transform with rename (-PtestAgp -PtestGradle)",
    testMethod = "smoke_assembleReleaseApkTransformWithRenameAgainstConfiguredAgp",
    requireE2e = true,
)

registerAgpCompatTest(
    name = "moltObfuscateAgpCompatRenameAabE2eTest",
    description = "AGP probe E2E: bundle + AAB transform with rename (-PtestAgp -PtestGradle)",
    testMethod = "smoke_bundleReleaseAabTransformWithRenameAgainstConfiguredAgp",
    requireE2e = true,
)

tasks.register<Exec>("moltObfuscateAgpCompatMatrix") {
    description = "Probe AGP support range (tools/agp-compat.sh → build/reports/agp-compat/report.md)"
    group = "molt"
    workingDir = rootProject.projectDir
    commandLine(rootProject.projectDir.resolve("tools/agp-compat.sh").absolutePath)
}

fun registerFeatureProbeTest(
    name: String,
    description: String,
    featureId: String,
) {
    tasks.register<Test>(name) {
        this.description = description
        group = "molt"
        dependsOn("testClasses", "pluginUnderTestMetadata")
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("io.github.amsonix.molt.FeatureProbeTest.featureProbeRunsConfiguredRow")
        doFirst {
            systemProperty("MOLT_FEATURE_PROBE", featureId)
            systemProperty("MOLT_TEST_AGP", providers.gradleProperty("testAgp").orElse("8.13.2").get())
            systemProperty("MOLT_TEST_GRADLE", providers.gradleProperty("testGradle").orElse("8.13").get())
            systemProperty("RUN_SHELL_TRANSFORM_E2E", "1")
        }
    }
}

tasks.register<Test>("moltObfuscateFeatureProbeTest") {
    description = "Feature matrix probe (-PmoltFeature=F01-overlay-rename -PtestAgp -PtestGradle)"
    group = "molt"
    dependsOn("testClasses", "pluginUnderTestMetadata")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("io.github.amsonix.molt.FeatureProbeTest.featureProbeRunsConfiguredRow")
    doFirst {
        val feature = providers.gradleProperty("moltFeature")
            .orElse(providers.environmentVariable("MOLT_FEATURE_PROBE"))
            .orNull
            ?: error("Set -PmoltFeature=<feature_id> or MOLT_FEATURE_PROBE")
        systemProperty("MOLT_FEATURE_PROBE", feature)
        systemProperty("MOLT_TEST_AGP", providers.gradleProperty("testAgp").orElse("8.13.2").get())
        systemProperty("MOLT_TEST_GRADLE", providers.gradleProperty("testGradle").orElse("8.13").get())
        systemProperty("RUN_SHELL_TRANSFORM_E2E", "1")
    }
}

registerFeatureProbeTest(
    name = "moltObfuscateTransformE2eTest",
    description = "Alias → feature probe F05-arsc-apk (APK transform E2E)",
    featureId = "F05-arsc-apk",
)

registerFeatureProbeTest(
    name = "moltObfuscateTransformBundleE2eTest",
    description = "Alias → feature probe F06-arsc-aab (AAB transform E2E)",
    featureId = "F06-arsc-aab",
)

registerFeatureProbeTest(
    name = "moltObfuscateTransformRenameE2eTest",
    description = "Alias → feature probe F09-rename-apk (APK transform + rename E2E)",
    featureId = "F09-rename-apk",
)

tasks.register<Exec>("moltObfuscateFeatureProbeMatrix") {
    description = "Probe molt feature presets (tools/feature-probe.sh → build/reports/feature-probe/report.md)"
    group = "molt"
    workingDir = rootProject.projectDir
    commandLine(rootProject.projectDir.resolve("tools/feature-probe.sh").absolutePath)
    environment(
        "FEATURE_PROBE_TIER" to providers.gradleProperty("featureProbeTier").orElse("all").get(),
    )
}

tasks.register<Exec>("moltObfuscateFeatureProbeNightly") {
    description = "Feature probe nightly tier (tools/feature-probe.sh FEATURE_PROBE_TIER=nightly)"
    group = "molt"
    workingDir = rootProject.projectDir
    commandLine(rootProject.projectDir.resolve("tools/feature-probe.sh").absolutePath)
    environment("FEATURE_PROBE_TIER" to "nightly")
}

tasks.register("publishMoltObfuscatePlugin") {
    description = "Publish resource-keep + Molt plugin to Nexus"
    dependsOn(
        project(":resource-keep").tasks.named("publish"),
        tasks.named("publish"),
    )
}

tasks.register<Exec>("publishMoltToGradlePortal") {
    description = "Publish Molt plugin to Gradle Plugin Portal (uses gradle/portal-publish.init.gradle)"
    group = "molt"
    workingDir = rootProject.projectDir
    val initScript = rootProject.file("gradle/portal-publish.init.gradle")
    val publishArgs = mutableListOf(
        rootProject.projectDir.resolve("gradlew").absolutePath,
        "-I",
        initScript.absolutePath,
        ":plugin:publishPlugins",
        "--no-daemon",
    )
    listOf("gradle.publish.key", "gradle.publish.secret").forEach { propertyName ->
        providers.gradleProperty(propertyName).orNull?.let { value ->
            publishArgs += listOf("-P$propertyName=$value")
        }
    }
    commandLine(publishArgs)
}

tasks.register<JavaExec>("quickDexVerify") {
    description = "Fast verify: patch classes.dex only + dexdump"
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.amsonix.molt.internal.bundle.QuickDexVerifyKt")
    standardOutput = System.out
    errorOutput = System.err
    val dexFilter = providers.gradleProperty("quickDex").orElse("classes.dex").get()
    args(dexFilter)
    providers.gradleProperty("integrationApk").orNull?.let { args(it) }
    systemProperty("molt.integrationVariant", integrationVariant())
    providers.gradleProperty("integrationApk").orNull?.let { apkPath ->
        systemProperty("molt.integrationApk", apkPath)
    }
    onlyIf {
        val explicit = providers.gradleProperty("integrationApk").orNull
        if (explicit != null) return@onlyIf true
        optionalIntegrationRoot()?.let(::hasIntegrationReleaseApk) == true ||
            hasIntegrationReleaseApk(sampleProjectDir())
    }
}

tasks.named<Test>("test") {
    exclude("**/DexComponentRenameIntegrationTest.class")
    exclude("**/DexMappingRewriteApkGeneratorTest.class")
    exclude("**/DexIdentityRoundTripApkGeneratorTest.class")
}

tasks.register<Test>("dexComponentRenameIntegrationTest") {
    description = "Real-APK DEX component rename integration (requires integration APK)"
    dependsOn("testClasses")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(
        "io.github.amsonix.molt.internal.bundle.DexComponentRenameIntegrationTest",
    )
    systemProperty("molt.integrationVariant", integrationVariant())
    providers.gradleProperty("integrationApk").orNull?.let { apkPath ->
        systemProperty("molt.integrationApk", apkPath)
    }
}

tasks.register<JavaExec>("moltObfuscateApkSpotCheck") {
    description = "Spot-check Firebase/ad SDK keep resources in a built APK (-PspotCheckApk=...)"
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.amsonix.molt.internal.keep.ApkSpotCheckKt")
    val apkPath = providers.gradleProperty("spotCheckApk")
    doFirst {
        require(apkPath.isPresent) { "Set -PspotCheckApk=/path/to/app.apk" }
    }
    args(apkPath)
}

tasks.register<Test>("dexMappingRewriteApkGeneratorTest") {
    description = "Generate mapping-rewrite APK for device verification (requires integration APK)"
    dependsOn("testClasses")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(
        "io.github.amsonix.molt.internal.bundle.DexMappingRewriteApkGeneratorTest",
    )
    systemProperty("molt.integrationVariant", integrationVariant())
}

tasks.register<Test>("dexIdentityRoundTripApkGeneratorTest") {
    description = "Generate identity round-trip APK for device verification (requires integration APK)"
    dependsOn("testClasses")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(
        "io.github.amsonix.molt.internal.bundle.DexIdentityRoundTripApkGeneratorTest",
    )
    systemProperty("molt.integrationVariant", integrationVariant())
}

tasks.register<Exec>("moltObfuscateSampleAssemble") {
    description = "Build sample app (googleRelease); requires Android SDK"
    workingDir = rootProject.projectDir
    commandLine(
        rootProject.projectDir.resolve("gradlew").absolutePath,
        "-p",
        "sample",
        ":app:assembleGoogleRelease",
        "--no-daemon",
    )
}

tasks.register<Exec>("moltObfuscateIntegrationPrepare") {
    description = "Optional: build host app googleRelease APK/AAB (-PintegrationRoot= or MOLT_INTEGRATION_ROOT)"
    onlyIf {
        optionalIntegrationRoot()?.let { File(it, "app/build.gradle.kts").isFile } == true
    }
    doFirst {
        val root = optionalIntegrationRoot() ?: error("Integration root not found")
        workingDir = root
        commandLine(
            root.resolve("gradlew").absolutePath,
            ":app:assembleGoogleRelease",
            ":app:bundleGoogleRelease",
            "--no-daemon",
        )
    }
}

tasks.register("moltObfuscateNightlyDexIntegration") {
    description = "DEX integration probes (skipped when integration APK missing)"
    dependsOn(
        "moltObfuscateSampleAssemble",
        "dexComponentRenameIntegrationTest",
        "dexMappingRewriteApkGeneratorTest",
    )
    if (optionalIntegrationRoot() != null || sampleProjectDir().let { File(it, "app/build.gradle.kts").isFile }) {
        dependsOn("quickDexVerify")
    }
}

tasks.register<JavaExec>("moltObfuscateMappingParityCheck") {
    description = "Compare APK/AAB resources-mapping rename counts (-PapkMapping= -PaabMapping=)"
    dependsOn("compileKotlin")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.amsonix.molt.internal.bundle.ResourceMappingParityKt")
    val apkMapping = providers.gradleProperty("apkMapping")
    val aabMapping = providers.gradleProperty("aabMapping")
    val variant = providers.gradleProperty("variant").orElse(integrationVariant())
    doFirst {
        fun resolveMappingPath(raw: String): File {
            val direct = File(raw)
            if (direct.isAbsolute && direct.isFile) return direct
            optionalIntegrationRoot()?.let { integrationRoot ->
                val fromIntegration = File(integrationRoot, raw)
                if (fromIntegration.isFile) return fromIntegration
            }
            if (direct.isFile) return direct.absoluteFile
            error("Mapping not found: $raw")
        }
        val apkPath = when {
            apkMapping.isPresent -> resolveMappingPath(apkMapping.get())
            else -> resolveMappingPath(
                "build/shell-obfuscate/${variant.get()}/apk-resource/resources-mapping.txt",
            )
        }
        val aabPath = when {
            aabMapping.isPresent -> resolveMappingPath(aabMapping.get())
            else -> resolveMappingPath(
                "build/shell-obfuscate/${variant.get()}/bundle-resource/resources-mapping.txt",
            )
        }
        setArgs(listOf(apkPath.absolutePath, aabPath.absolutePath))
    }
}

tasks.register("moltObfuscateMappingParityCheckNightly") {
    description = "Mapping parity after host build (optional; requires moltObfuscateIntegrationPrepare)"
    dependsOn("moltObfuscateIntegrationPrepare", "moltObfuscateMappingParityCheck")
    onlyIf { optionalIntegrationRoot() != null }
}

tasks.register("moltObfuscateNightlyVerify") {
    description = "Nightly: unit tests + feature probe nightly + sample (+ optional host DEX/parity)"
    dependsOn(
        "test",
        "moltObfuscateFeatureProbeNightly",
        "moltObfuscateSampleAssemble",
        "moltObfuscateNightlyDexIntegration",
    )
    if (providers.gradleProperty("runMappingParityCheck").orElse("0").get() == "1") {
        dependsOn("moltObfuscateMappingParityCheckNightly")
    }
}

tasks.register("moltObfuscateCheck") {
    description = "Alias for unit tests only (fast PR gate)"
    dependsOn("test")
}

tasks.named("check") {
    dependsOn("test")
    if (providers.environmentVariable("RUN_QUICK_DEX").orElse("0").get() == "1") {
        dependsOn("quickDexVerify")
    }
}

tasks.matching {
    it.name.startsWith("moltObfuscate") || it.name in moltPluginTaskNames
}.configureEach {
    group = "molt"
}
