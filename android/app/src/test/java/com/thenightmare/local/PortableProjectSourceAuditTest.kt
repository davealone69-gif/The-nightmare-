package com.thenightmare.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PortableProjectSourceAuditTest {
    @Test
    fun currentRepositoryCanBeCapturedWithoutExportingBuildCachesOrGitMetadata() {
        var root = File(System.getProperty("user.dir")).canonicalFile
        while (root.parentFile != null && !(File(root, "config.json").isFile &&
                    File(root, "run.sh").isFile && File(root, "android/README.md").isFile)) {
            root = root.parentFile!!
        }
        check(File(root, "config.json").isFile && File(root, "android/README.md").isFile) {
            "could not locate the repository source root from the Android unit-test working directory"
        }

        val snapshot = PortableProjectExporter.captureFromLocalDirectory(root)
        val paths = snapshot.files.map { it.relativePath }.toSet()
        assertTrue(paths.containsAll(setOf("README.md", "requirements.txt", "config.json", "run.sh", "android/README.md")))
        assertTrue(paths.none { it.startsWith(".git/") || it.startsWith("android/.gradle/") || it.startsWith("android/app/build/") })
    }
}
