plugins {
    `kotlin-dsl`
    `maven-publish`
}

import java.util.Properties

group = "io.github.amsonix.molt"
version = providers.gradleProperty("moltVersion").orElse("0.1.0-SNAPSHOT").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
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
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("resource-keep")
                description.set("Shared keep.xml baseline and parser for Molt Gradle plugin.")
            }
        }
    }
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
