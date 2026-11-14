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
    private fun removeFixture(parent: File) {
        parent.walkBottomUp().forEach { it.setWritable(true, false) }
        check(parent.deleteRecursively())
    }

    private fun createFixture(root: File): String {
        check(root.mkdirs())
        val sourceBytes = "Orchid moss grows in shade. The verified guide discusses the orchid.".toByteArray(StandardCharsets.UTF_8)
        val source = File(root, "guide.md")
        source.writeBytes(sourceBytes)
        val sources = JSONArray().put(
            JSONObject()
                .put("id", "DOC-1")
                .put("title", "Garden guide")
                .put("path", "guide.md")
                .put("sha256", JsonCanonical.sha256(sourceBytes)),
        )
        val snapshotId = "snapshot-${JsonCanonical.sha256(JsonCanonical.encode(sources).toByteArray(StandardCharsets.UTF_8)).take(16)}"
        File(root, "manifest.json").writeText(
            JSONObject()
                .put("schema_version", "1.0")
                .put("snapshot_id", snapshotId)
                .put("sources", sources)
                .toString(),
            StandardCharsets.UTF_8,
        )
        return snapshotId
    }

    @Test
    fun validPinnedLibrarySearchesDeterministicallyAndBecomesReadOnly() {
        val parent = Files.createTempDirectory("nightmare-library-valid").toFile()
        val root = File(parent, "library")
        try {
            val pin = createFixture(root)
            val library = FrozenLibrary(root, pin)
            val info = library.info()
            assertTrue(info.available)
            assertEquals(pin, info.snapshotId)
            assertEquals(1, info.sourceCount)
            assertFalse(root.canWrite())
            assertFalse(File(root, "guide.md").canWrite())

            val result = library.search("orchid shade")
            assertEquals(pin, result.snapshotId)
            assertEquals(listOf("DOC-1"), result.passages.map { it.source.sourceId })
        } finally {
            removeFixture(parent)
        }
    }

    @Test
    fun modifiedSourceFailsItsManifestDigestCheck() {
        val parent = Files.createTempDirectory("nightmare-library-tamper").toFile()
        val root = File(parent, "library")
        try {
            val pin = createFixture(root)
            assertTrue(FrozenLibrary(root, pin).info().available)
            val source = File(root, "guide.md")
            assertTrue(source.setWritable(true, false))
            source.writeText("Tampered text that is not in the trusted manifest.", StandardCharsets.UTF_8)

            val info = FrozenLibrary(root, pin).info()
            assertFalse(info.available)
            assertEquals(LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE, info.failureReason)
        } finally {
            removeFixture(parent)
        }
    }

    @Test
    fun symlinkedLibraryEntryIsRejectedWithoutReadingItsTarget() {
        val parent = Files.createTempDirectory("nightmare-library-symlink").toFile()
        val root = File(parent, "library")
        try {
            val pin = createFixture(root)
            val outside = File(parent, "outside-secret.md").apply { writeText("outside") }
            Files.createSymbolicLink(File(root, "extra.md").toPath(), outside.toPath())

            val info = FrozenLibrary(root, pin).info()
            assertFalse(info.available)
            assertEquals(LibraryFailureReason.LIBRARY_INTEGRITY_FAILURE, info.failureReason)
        } finally {
            removeFixture(parent)
        }
    }
}
