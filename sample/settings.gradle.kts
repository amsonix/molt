// AGP 8.0.x + Gradle 8.0 wrapper。本地开发推荐 composite：pluginManagement { includeBuild("..") }；
// 亦可用 mavenLocal（先在仓库根目录 :plugin:publishToMavenLocal）。
pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "molt-sample"
include(":app", ":library")
