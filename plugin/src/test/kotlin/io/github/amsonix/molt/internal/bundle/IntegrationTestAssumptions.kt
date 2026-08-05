package io.github.amsonix.molt.internal.bundle

import org.junit.Assume
import java.io.File

/** 集成 / probe 测试：缺产物时 skipped，禁止 silent pass。 */
internal object IntegrationTestAssumptions {

    fun projectRoot(): File {
        System.getenv("MOLT_INTEGRATION_ROOT")?.takeIf { it.isNotBlank() }?.let { envRoot ->
            val root = File(envRoot)
            Assume.assumeTrue("MOLT_INTEGRATION_ROOT invalid: ${root.path}", File(root, "app").isDirectory)
            return root
        }
        val cwd = File(System.getProperty("user.dir"))
        val candidates = buildList {
            add(cwd)
            cwd.parentFile?.let { add(it) }
            cwd.parentFile?.parentFile?.let { add(it) }
        }
        for (root in candidates) {
            if (File(root, "app").isDirectory && File(root, "settings.gradle.kts").isFile) {
                return root
            }
            if (File(root, "sample/app").isDirectory) {
                return root
            }
        }
        Assume.assumeTrue(
            "integration root not found (expected molt/ or host app/ under ${cwd.path})",
            false,
        )
        return cwd
    }

    fun sampleAppRoot(root: File = projectRoot()): File =
        when {
            File(root, "sample/app").isDirectory -> File(root, "sample")
            File(root, "sample/app").isDirectory -> File(root, "sample")
            else -> root
        }

    fun assumeIntegrationApk(root: File = projectRoot(), explicitPath: String? = null): File {
        val apk = DexIntegrationFixture.apkCandidate(root, explicitPath)
        Assume.assumeTrue("integration APK missing: ${apk.path}", apk.isFile)
        return apk
    }

    fun assumeRepoFile(relativePath: String, root: File = projectRoot()): File {
        val file = File(root, relativePath)
        Assume.assumeTrue("repo fixture missing: ${file.path}", file.isFile)
        return file
    }

    fun assumeComponentMapping(root: File = projectRoot()): File {
        val mapping = DexIntegrationFixture.componentMapping(root)
        Assume.assumeTrue("component-mapping missing: ${mapping.path}", mapping.isFile)
        return mapping
    }

    fun assumeViewMapping(root: File = projectRoot()): File {
        val mapping = DexIntegrationFixture.viewMapping(root)
        Assume.assumeTrue("view-mapping missing: ${mapping.path}", mapping.isFile)
        return mapping
    }

    fun assumeFixtureFile(file: File, label: String): File {
        Assume.assumeTrue("$label missing: ${file.path}", file.isFile)
        return file
    }
}
