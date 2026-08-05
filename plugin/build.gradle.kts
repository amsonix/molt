plugins {
    `kotlin-dsl`
    java
    `maven-publish`
}

import java.util.Properties

group = "io.github.amsonix.molt"
version = providers.gradleProperty("moltVersion").orElse("1.0.0").get()

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
    dependsOn("compileKotlin")
}

dependencies {
    implementation(project(":resource-keep"))
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation("com.google.code.gson:gson:2.13.0")
    implementation("com.android.tools.build:bundletool:1.17.2")
    implementation("com.android.tools.build:aapt2-proto:8.13.2-14304508")
    implementation("com.google.guava:guava:32.1.3-jre")
    implementation("commons-io:commons-io:2.15.1")
    implementation("commons-codec:commons-codec:1.16.0")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("org.smali:dexlib2:2.5.2")
    implementation(libs.firebase.crashlytics.gradle.plugin)
    compileOnly("org.jetbrains:annotations:24.1.0")
    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
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
    "quickDexVerify",
    "dexComponentRenameIntegrationTest",
    "dexMappingRewriteApkGeneratorTest",
)

tasks.register("publishMoltObfuscatePlugin") {
    description = "Publish resource-keep + Molt plugin to Nexus"
    dependsOn(
        project(":resource-keep").tasks.named("publish"),
        tasks.named("publish"),
    )
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

tasks.register<Test>("moltObfuscateTransformE2eTest") {
    description = "TestKit APK Transform E2E (requires Android SDK; skips when SDK missing)"
    dependsOn("testClasses")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(
        "io.github.amsonix.molt.MoltObfuscatePluginFunctionalTest.assembleRelease_runsApkTransformWhenEnabled",
    )
    systemProperty("RUN_SHELL_TRANSFORM_E2E", "1")
}

tasks.register<Test>("moltObfuscateTransformBundleE2eTest") {
    description = "TestKit AAB Transform E2E (requires Android SDK; skips when SDK missing)"
    dependsOn("testClasses")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(
        "io.github.amsonix.molt.MoltObfuscatePluginFunctionalTest.bundleRelease_runsAabTransformWhenEnabled",
    )
    systemProperty("RUN_SHELL_TRANSFORM_E2E", "1")
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

tasks.register<Test>("moltObfuscateTransformRenameE2eTest") {
    description = "TestKit APK Transform E2E with component/view rename enabled"
    dependsOn("testClasses")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(
        "io.github.amsonix.molt.MoltObfuscatePluginFunctionalTest.assembleRelease_runsApkTransformWithRenameWhenEnabled",
    )
    systemProperty("RUN_SHELL_TRANSFORM_E2E", "1")
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
    description = "Nightly: unit tests + E2E + sample (+ optional host DEX/parity)"
    dependsOn(
        "test",
        "moltObfuscateTransformE2eTest",
        "moltObfuscateTransformRenameE2eTest",
        "moltObfuscateTransformBundleE2eTest",
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
