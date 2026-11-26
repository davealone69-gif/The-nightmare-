package com.thenightmare.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class DemoLibrarySeederTest {
    private val expectedText = """# Demo garden guide: tree fern light

A tree fern grows best in bright, indirect light. Avoid harsh direct afternoon sun.
Keep the potting mix evenly moist, but do not leave the roots standing in water.

This sample text exists only to exercise local retrieval and is not horticultural advice.
"""

    @Test
    fun createsExpectedCorpusManifestPinAndReadableFrozenLibrary() {
        val parent = Files.createTempDirectory("nightmare-demo-seeder").toFile()
        val root = File(parent, "library")
        try {
            val info = DemoLibrarySeeder.createDemoLibrary(root)
            val sourceBytes = expectedText.toByteArray(StandardCharsets.UTF_8)
            val source = File(root, "demo-fern-guide.md")
            assertTrue(info.available)
            assertEquals(1, info.sourceCount)
            assertEquals(expectedText, source.readText(StandardCharsets.UTF_8))

            val sourceMetadata = JSONObject()
                .put("id", "DOC-DEMO-FERN-GUIDE")
                .put("title", "Demo garden guide: tree fern light")
                .put("path", "demo-fern-guide.md")
                .put("sha256", JsonCanonical.sha256(sourceBytes))
            val expectedPin = "snapshot-${JsonCanonical.sha256(
                JsonCanonical.encode(org.json.JSONArray().put(sourceMetadata))
                    .toByteArray(StandardCharsets.UTF_8),
            ).take(16)}"
            assertEquals("snapshot-1ac053659830ac1d", expectedPin)
            assertEquals(expectedPin, info.snapshotId)

            val manifest = JSONObject(File(root, "manifest.json").readText(StandardCharsets.UTF_8))
            assertEquals("1.0", manifest.getString("schema_version"))
            assertEquals(expectedPin, manifest.getString("snapshot_id"))
            assertEquals(JsonCanonical.encode(org.json.JSONArray().put(sourceMetadata)),
                JsonCanonical.encode(manifest.getJSONArray("sources")))

            val retrieved = FrozenLibrary(root, expectedPin).search("tree fern light")
            assertEquals(expectedPin, retrieved.snapshotId)
            assertEquals(listOf("DOC-DEMO-FERN-GUIDE"),
                retrieved.passages.map { it.source.sourceId })
            assertFalse(root.canWrite())
            assertFalse(source.canWrite())
        } finally {
            removeFixture(parent)
        }
    }

    @Test
    fun refusesExistingDirectoryWithoutChangingIt() {
        val parent = Files.createTempDirectory("nightmare-demo-existing").toFile()
        val root = File(parent, "library")
        try {
            assertTrue(root.mkdir())
            val marker = File(root, "keep.txt")
            marker.writeText("do not replace")
            val before = marker.readBytes()
            try {
                DemoLibrarySeeder.createDemoLibrary(root)
                throw AssertionError("expected existing target to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected refusal occurs before staging.
            }
            assertTrue(root.isDirectory)
            assertTrue(marker.exists())
            assertTrue(before.contentEquals(marker.readBytes()))
        } finally {
            removeFixture(parent)
        }
    }

    @Test
    fun refusesExistingSymlinkWithoutChangingLinkOrTarget() {
        val parent = Files.createTempDirectory("nightmare-demo-symlink").toFile()
        val target = File(parent, "outside")
        val root = File(parent, "library")
        try {
            target.writeText("outside content")
            Files.createSymbolicLink(root.toPath(), target.toPath())
            try {
                DemoLibrarySeeder.createDemoLibrary(root)
                throw AssertionError("expected existing symlink to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected refusal occurs before staging.
            }
            assertTrue(Files.isSymbolicLink(root.toPath()))
            assertEquals("outside content", target.readText(StandardCharsets.UTF_8))
        } finally {
            removeFixture(parent)
        }
    }

    private fun removeFixture(parent: File) {
        parent.walkBottomUp().forEach { it.setWritable(true, false) }
        assertTrue(parent.deleteRecursively())
    }
}
