package com.thenightmare.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class FrozenLibraryTest {
    private fun removeFixture(parent: File) { parent.walkBottomUp().forEach { it.setWritable(true, false) }; check(parent.deleteRecursively()) }

    private fun createFixture(root: File, sourceText: String = "Orchid moss grows in shade. The verified guide discusses the orchid."): String {
        check(root.mkdirs())
        val sourceBytes = sourceText.toByteArray(StandardCharsets.UTF_8)
        File(root, "guide.md").writeBytes(sourceBytes)
        val sources = JSONArray().put(JSONObject().put("id", "DOC-1").put("title", "Garden guide").put("path", "guide.md").put("sha256", JsonCanonical.sha256(sourceBytes)))
        val pin = "snapshot-${JsonCanonical.sha256(JsonCanonical.encode(sources).toByteArray(StandardCharsets.UTF_8)).take(16)}"
        File(root, "manifest.json").writeText(JSONObject().put("schema_version", "1.0").put("snapshot_id", pin).put("sources", sources).toString(), StandardCharsets.UTF_8)
        root.walkBottomUp().forEach { check(it.setWritable(false, false)) }
        return pin
    }

    @Test fun validPinnedReadOnlyLibrarySearchesDeterministicallyWithoutMutation() {
        val parent = Files.createTempDirectory("nightmare-library-valid").toFile(); val root = File(parent, "library")
        try { val pin = createFixture(root); val library = FrozenLibrary(root, pin); val info = library.info(); assertTrue(info.available); assertEquals(pin, info.snapshotId); assertEquals(1, info.sourceCount); assertFalse(root.canWrite()); assertFalse(File(root, "guide.md").canWrite()); assertEquals(listOf("DOC-1"), library.search("orchid shade").passages.map { it.source.sourceId }) } finally { removeFixture(parent) }
    }

    @Test fun writableRootOrFileIsRejectedWithoutPermissionMutation() {
        val parent = Files.createTempDirectory("nightmare-library-writable").toFile(); val root = File(parent, "library")
        try { val pin = createFixture(root); root.walkBottomUp().forEach { it.setWritable(true, false) }; assertTrue(root.canWrite()); val info = FrozenLibrary(root, pin).info(); assertFalse(info.available); assertEquals(LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE, info.failureReason); assertTrue(root.canWrite()) } finally { removeFixture(parent) }
    }

    @Test fun validPinlessRecoveryReturnsOnlySnapshotIdAfterFullValidation() {
        val parent = Files.createTempDirectory("nightmare-library-recovery").toFile(); val root = File(parent, "library")
        try { val pin = createFixture(root); assertEquals(pin, FrozenLibrary(root, "").validateForPinRecovery()); val info = FrozenLibrary(root, "").info(); assertFalse(info.available); assertEquals(LibraryFailureReason.LIBRARY_UNPINNED, info.failureReason) } finally { removeFixture(parent) }
    }

    @Test fun corruptedPinlessLibraryIsNotClassifiedAsRecoverable() {
        val parent = Files.createTempDirectory("nightmare-library-pinless-corrupt").toFile(); val root = File(parent, "library")
        try { createFixture(root); root.walkBottomUp().forEach { it.setWritable(true, false) }; File(root, "guide.md").writeText("corrupted text", StandardCharsets.UTF_8); root.walkBottomUp().forEach { it.setWritable(false, false) }; val info = FrozenLibrary(root, "").info(); assertFalse(info.available); assertEquals(LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE, info.failureReason) } finally { removeFixture(parent) }
    }

    @Test fun preparedInstallRequiresReplaceAndRepinThenCommitsNewPin() {
        val parent = Files.createTempDirectory("nightmare-library-install").toFile()
        val current = File(parent, "library")
        val staging = File(parent, ".new-staging")
        try {
            val oldPin = createFixture(current)
            val newPin = createFixture(staging, "Orchid moss likes filtered shade; new frozen content.")
            var persistedPin = oldPin
            fun install(allowReplace: Boolean, allowRepin: Boolean): Boolean = try {
                FrozenLibrary.installPreparedTree(staging, current, parent, newPin, oldPin, allowReplace, allowRepin,
                    persistPin = { persistedPin = it }, restorePin = { persistedPin = oldPin })
                true
            } catch (_: IllegalArgumentException) { false }
            assertFalse(install(allowReplace = false, allowRepin = false))
            assertFalse(staging.exists())
            assertTrue(FrozenLibrary(current, oldPin).info().available)
            assertEquals(oldPin, persistedPin)
            createFixture(staging, "Orchid moss likes filtered shade; new frozen content.")
            assertFalse(install(allowReplace = true, allowRepin = false))
            assertFalse(staging.exists())
            assertTrue(FrozenLibrary(current, oldPin).info().available)
            assertEquals(oldPin, persistedPin)

            createFixture(staging, "Orchid moss likes filtered shade; new frozen content.")
            val result = FrozenLibrary.installPreparedTree(staging, current, parent, newPin, oldPin, true, true,
                persistPin = { persistedPin = it }, restorePin = { persistedPin = oldPin })
            assertTrue(result.available)
            assertEquals(newPin, result.snapshotId)
            assertEquals(newPin, persistedPin)
            assertFalse(staging.exists())
        } finally { removeFixture(parent) }
    }

    @Test fun abandonedStagingCleanupIsScopedAndDoesNotFollowSymlinks() {
        val parent = Files.createTempDirectory("nightmare-library-stale-stage").toFile()
        val staging = File(parent, ".library-staging-abandoned")
        val backup = File(parent, ".library-backup-preserve")
        val outside = File(parent.parentFile, "nightmare-outside-${System.nanoTime()}.txt")
        try {
            check(File(staging, "nested").mkdirs())
            File(staging, "nested/source.md").writeText("staged")
            check(backup.mkdirs())
            File(backup, "previous.md").writeText("preserve backup")
            outside.writeText("outside")
            Files.createSymbolicLink(File(staging, "outside-link").toPath(), outside.toPath())
            staging.walkBottomUp().forEach { it.setWritable(false, false) }

            FrozenLibrary.cleanupAbandonedPreparations(parent)

            assertFalse(staging.exists())
            assertTrue(backup.isDirectory)
            assertTrue(File(backup, "previous.md").isFile)
            assertTrue("cleanup must not follow the staging symlink", outside.isFile)
        } finally {
            parent.walkBottomUp().forEach { it.setWritable(true, false) }
            parent.deleteRecursively()
            outside.delete()
        }
    }

    @Test fun failedFirstAtomicMovePreservesOldLibraryAndCleansStaging() {
        val parent = Files.createTempDirectory("nightmare-library-first-move-failure").toFile()
        val current = File(parent, "library")
        val staging = File(parent, ".new-staging")
        try {
            val oldPin = createFixture(current)
            val newPin = createFixture(staging, "A different but valid frozen source.")
            val failed = try {
                FrozenLibrary.installPreparedTree(staging, current, parent, newPin, oldPin, true, true,
                    persistPin = {}, restorePin = {},
                    atomicMove = { _, _ -> throw IllegalStateException("simulated atomic rename failure") })
                false
            } catch (_: IllegalStateException) { true }
            assertTrue(failed)
            assertTrue("failed first rename must not delete the old library", FrozenLibrary(current, oldPin).info().available)
            assertFalse("failed transaction must clean its staging tree", staging.exists())
        } finally { removeFixture(parent) }
    }

    @Test fun ambiguousFirstAtomicMovePreservesAndReportsRecoveryBackup() {
        val parent = Files.createTempDirectory("nightmare-library-ambiguous-first-move").toFile()
        val current = File(parent, "library")
        val staging = File(parent, ".new-staging")
        try {
            val oldPin = createFixture(current)
            val newPin = createFixture(staging, "A different but valid frozen source.")
            var failure: Exception? = null
            try {
                FrozenLibrary.installPreparedTree(staging, current, parent, newPin, oldPin, true, true,
                    persistPin = {}, restorePin = {},
                    atomicMove = { _, target ->
                        check(target.toFile().mkdir())
                        throw IllegalStateException("simulated ambiguous rename")
                    })
            } catch (exception: Exception) { failure = exception }

            val backup = parent.listFiles()?.firstOrNull { it.name.startsWith(".library-backup-") }
            assertTrue("ambiguous move must report its preserved recovery backup", failure?.message?.contains("recovery backup") == true)
            assertTrue("old library remains usable", FrozenLibrary(current, oldPin).info().available)
            assertTrue("backup is retained for recovery", backup?.isDirectory == true)
            assertFalse("staging is still cleaned", staging.exists())
        } finally { removeFixture(parent) }
    }

    @Test fun failedPinCommitRestoresPreviousLibraryAndPin() {
        val parent = Files.createTempDirectory("nightmare-library-install-rollback").toFile()
        val current = File(parent, "library")
        val staging = File(parent, ".new-staging")
        try {
            val oldPin = createFixture(current)
            val newPin = createFixture(staging, "A different but valid frozen source.")
            var persistedPin = oldPin
            val failed = try {
                FrozenLibrary.installPreparedTree(staging, current, parent, newPin, oldPin, true, true,
                    persistPin = { persistedPin = it; throw IllegalStateException("simulated durable settings failure") },
                    restorePin = { persistedPin = oldPin })
                false
            } catch (_: IllegalStateException) { true }
            assertTrue(failed)
            assertEquals(oldPin, persistedPin)
            assertTrue(FrozenLibrary(current, oldPin).info().available)
        } finally { removeFixture(parent) }
    }

    @Test fun modifiedSourceFailsItsManifestDigestCheck() {
        val parent = Files.createTempDirectory("nightmare-library-tamper").toFile(); val root = File(parent, "library")
        try { val pin = createFixture(root); root.walkBottomUp().forEach { it.setWritable(true, false) }; File(root, "guide.md").writeText("Tampered text.", StandardCharsets.UTF_8); val info = FrozenLibrary(root, pin).info(); assertFalse(info.available); assertEquals(LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE, info.failureReason) } finally { removeFixture(parent) }
    }

    @Test fun symlinkedLibraryEntryIsRejectedWithoutReadingItsTarget() {
        val parent = Files.createTempDirectory("nightmare-library-symlink").toFile(); val root = File(parent, "library")
        try { val pin = createFixture(root); val outside = File(parent, "outside-secret.md").apply { writeText("outside") }; root.walkBottomUp().forEach { it.setWritable(true, false) }; Files.createSymbolicLink(File(root, "extra.md").toPath(), outside.toPath()); root.walkBottomUp().forEach { if (!Files.isSymbolicLink(it.toPath())) it.setWritable(false, false) }; val info = FrozenLibrary(root, pin).info(); assertFalse(info.available); assertEquals(LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE, info.failureReason) } finally { removeFixture(parent) }
    }
}
