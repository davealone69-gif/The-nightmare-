package com.thenightmare.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortableProjectExporterTest {
    private fun write(root: File, relative: String, text: String): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(text, StandardCharsets.UTF_8)
        return file
    }

    private fun makeProject(root: File, dataDir: String = "runtime-data") {
        check(root.mkdirs())
        write(root, "README.md", "# Portable test project\nLocal fixture.\n")
        write(root, "requirements.txt", "# standard library only\n")
        write(root, "config.json", "{\"data_dir\":\"$dataDir\"}\n")
        write(root, "run.sh", "#!/bin/sh\nset -eu\n")
            .setExecutable(true, false)
        write(root, "agent_harness/core.py", "VALUE = 'included source'\n")
        write(root, "runtime-data/library/private.md", "must stay private\n")
        write(root, "tests/test_core.py", "assert False\n")
        write(root, "build/test-results/result.xml", "generated\n")
        write(root, "android/.gradle/9/cache.bin", "generated\n")
        write(root, "android/.kotlin/state.bin", "generated\n")
        write(root, ".env.local", "not for export\n")
        write(root, ".aws/credentials", "aws_access_key_id = EXAMPLE_INVALID_VALUE\n")
        write(root, "credentials-backup.txt", "not for export\n")
    }

    @Test
    fun exportExcludesRuntimeAndSecretPathsAndVerifiesFolderZipManifestPair() {
        val parent = Files.createTempDirectory("nightmare-portable-export").toFile()
        val source = File(parent, "source")
        val output = File(parent, "exports")
        try {
            makeProject(source)
            val result = PortableProjectExporter.export(source, output)
            val verified = PortableProjectExporter.verifyExportPair(result.directory)
            assertEquals(result.treeSha256, verified.treeSha256)
            assertEquals(result.archiveSha256, verified.archiveSha256)
            assertEquals(result.fileCount, verified.fileCount)

            val included = result.folder.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(result.folder).invariantSeparatorsPath }.toSet()
            assertTrue(setOf("README.md", "requirements.txt", "config.json", "run.sh", "agent_harness/core.py").all(included::contains))
            assertFalse(included.any { it.startsWith("runtime-data/") })
            assertFalse(included.any { it.startsWith("tests/") })
            assertFalse(included.any { it.startsWith("build/") })
            assertFalse(included.any { it.startsWith("android/.gradle/") || it.startsWith("android/.kotlin/") })
            assertFalse(included.any { it.startsWith(".aws/") || it.startsWith(".env") || "credentials" in it })
            assertEquals(included.size, result.fileCount)

            ZipFile(result.archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertEquals(included, names)
                assertEquals("#!/bin/sh\nset -eu\n", zip.getInputStream(zip.getEntry("run.sh")).bufferedReader().readText())
            }
            val runPermissions = Files.getPosixFilePermissions(File(result.folder, "run.sh").toPath(), LinkOption.NOFOLLOW_LINKS)
            assertTrue(PosixFilePermission.OWNER_EXECUTE in runPermissions)
            assertTrue(result.manifest.isFile)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun matchingInputCreatesReproducibleTreeAndZipHashesWithoutOverwritingEarlierRun() {
        val parent = Files.createTempDirectory("nightmare-portable-repro").toFile()
        val source = File(parent, "source")
        val output = File(parent, "exports")
        try {
            makeProject(source, dataDir = "not-present")
            val first = PortableProjectExporter.export(source, output)
            val second = PortableProjectExporter.export(source, output)
            assertNotEquals(first.exportId, second.exportId)
            assertEquals(first.treeSha256, second.treeSha256)
            assertEquals(first.archiveSha256, second.archiveSha256)
            assertEquals(2, PortableProjectExporter.findVerifiedExports(output).size)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun recognizableSecretMaterialAbortsBeforePublishingAnyPair() {
        val parent = Files.createTempDirectory("nightmare-portable-secret").toFile()
        val source = File(parent, "source")
        val output = File(parent, "exports")
        try {
            makeProject(source, dataDir = "not-present")
            write(source, "app/settings.yaml", "aws_secret_access_key: placeholder-value\n")
            val error = try {
                PortableProjectExporter.export(source, output)
                null
            } catch (exception: IllegalArgumentException) { exception }
            assertTrue(error?.message?.contains("potential secret material") == true)
            assertFalse(output.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun sensitivePathNamesAreSkippedButRequiredConfigSecretFailsClosed() {
        val parent = Files.createTempDirectory("nightmare-portable-sensitive").toFile()
        val source = File(parent, "source")
        try {
            makeProject(source, dataDir = "not-present")
            val result = PortableProjectExporter.export(source, File(parent, "exports"))
            assertTrue(PortableProjectExporter.verifyExportPair(result.directory).fileCount > 0)

            val config = File(source, "config.json")
            config.writeText("{\"api_key\":\"placeholder\"}\n", StandardCharsets.UTF_8)
            val error = try {
                PortableProjectExporter.export(source, File(parent, "other-exports"))
                null
            } catch (exception: IllegalArgumentException) { exception }
            assertTrue(error?.message?.contains("potential secret material") == true)
            assertFalse(File(parent, "other-exports").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun symlinkedOptionalFilesAreNeverReadAndPairTamperingIsRejected() {
        val parent = Files.createTempDirectory("nightmare-portable-tamper").toFile()
        val source = File(parent, "source")
        try {
            makeProject(source, dataDir = "not-present")
            val outside = write(File(parent, "outside"), "credential.txt", "secret outside project")
            Files.createSymbolicLink(File(source, "outside.txt").toPath(), outside.toPath())
            val result = PortableProjectExporter.export(source, File(parent, "exports"))
            assertFalse(File(result.folder, "outside.txt").exists())
            PortableProjectExporter.verifyExportPair(result.directory)
            File(result.folder, "agent_harness/core.py").appendText("# changed\n")
            val rejected = try {
                PortableProjectExporter.verifyExportPair(result.directory)
                false
            } catch (_: IllegalArgumentException) { true }
            assertTrue(rejected)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun groupOnlyExecutableSourceRemainsExecutableInPortableOutput() {
        val parent = Files.createTempDirectory("nightmare-portable-group-exec").toFile()
        val source = File(parent, "source")
        try {
            makeProject(source, dataDir = "not-present")
            val runScript = File(source, "run.sh").toPath()
            Files.setPosixFilePermissions(runScript, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
            ))
            val result = PortableProjectExporter.export(source, File(parent, "exports"))
            val outputPermissions = Files.getPosixFilePermissions(File(result.folder, "run.sh").toPath(), LinkOption.NOFOLLOW_LINKS)
            assertTrue("group-only executable source must remain executable", PosixFilePermission.OWNER_EXECUTE in outputPermissions)
            PortableProjectExporter.verifyExportPair(result.directory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun executableModeTamperingBreaksFolderZipPairVerification() {
        val parent = Files.createTempDirectory("nightmare-portable-mode").toFile()
        val source = File(parent, "source")
        try {
            makeProject(source, dataDir = "not-present")
            val result = PortableProjectExporter.export(source, File(parent, "exports"))
            val runScript = File(result.folder, "run.sh").toPath()
            val original = Files.getPosixFilePermissions(runScript, LinkOption.NOFOLLOW_LINKS)
            Files.setPosixFilePermissions(runScript, original - PosixFilePermission.GROUP_EXECUTE)
            val rejected = try {
                PortableProjectExporter.verifyExportPair(result.directory)
                false
            } catch (_: IllegalArgumentException) { true }
            assertTrue("folder executable-mode changes must invalidate the pair", rejected)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun completionManifestIsRequiredAndMalformedZipPathsAreRejected() {
        val parent = Files.createTempDirectory("nightmare-portable-manifest").toFile()
        val source = File(parent, "source")
        try {
            makeProject(source, dataDir = "not-present")
            val result = PortableProjectExporter.export(source, File(parent, "exports"))
            val originalManifest = result.manifest.readBytes()
            result.manifest.delete()
            val noManifest = try {
                PortableProjectExporter.verifyExportPair(result.directory)
                false
            } catch (_: IllegalArgumentException) { true }
            assertTrue(noManifest)
            result.manifest.writeBytes(originalManifest)
            PortableProjectExporter.verifyExportPair(result.directory)

            val maliciousArchive = File(parent, "malicious.zip")
            ZipOutputStream(FileOutputStream(maliciousArchive)).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.txt"))
                zip.write("outside".toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            maliciousArchive.copyTo(result.archive, overwrite = true)
            val manifest = JSONObject(result.manifest.readText(StandardCharsets.UTF_8))
            val maliciousHash = MessageDigest.getInstance("SHA-256").digest(result.archive.readBytes())
                .joinToString("") { "%02x".format(it) }
            manifest.put("archive_sha256", maliciousHash)
            result.manifest.writeText(JsonCanonical.encode(manifest) + "\n", StandardCharsets.UTF_8)
            val traversalRejected = try {
                PortableProjectExporter.verifyExportPair(result.directory)
                false
            } catch (_: IllegalArgumentException) { true }
            assertTrue(traversalRejected)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun selectedFolderDataDirIsNormalizedOrFailsClosedWhenAmbiguous() {
        fun parse(path: String): String? = PortableProjectExporter.configuredRuntimeDataRelative(
            JSONObject().put("data_dir", path).toString().toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals("private-data/library", parse("runtime-data/../private-data/./library/"))
        assertEquals(null, parse("../external-nightmare-data"))
        for (path in listOf("/storage/emulated/0/Android/data", "C:\\private\\data", ".", "")) {
            val rejected = try {
                parse(path)
                false
            } catch (_: IllegalArgumentException) { true }
            assertTrue("ambiguous data_dir should be rejected: $path", rejected)
        }
        val malformed = try {
            PortableProjectExporter.configuredRuntimeDataRelative("not json".toByteArray(StandardCharsets.UTF_8))
            false
        } catch (_: IllegalArgumentException) { true }
        assertTrue(malformed)
    }

    @Test
    fun localExportRejectsRootWideRuntimeDataDirBeforePublishing() {
        val parent = Files.createTempDirectory("nightmare-portable-root-data").toFile()
        try {
            for ((index, dataDir) in listOf(".", "runtime-data/..").withIndex()) {
                val source = File(parent, "source-$index")
                val output = File(parent, "exports-$index")
                makeProject(source, dataDir = dataDir)
                val rejected = try {
                    PortableProjectExporter.export(source, output)
                    false
                } catch (_: IllegalArgumentException) { true }
                assertTrue("root-wide data_dir must be rejected: $dataDir", rejected)
                assertFalse("rejected export must not create output", output.exists())
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
