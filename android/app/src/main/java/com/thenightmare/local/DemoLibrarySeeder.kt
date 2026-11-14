package com.thenightmare.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Creates the deterministic, app-private demo corpus without replacing a library. */
object DemoLibrarySeeder {
    private const val SOURCE_ID = "DOC-DEMO-FERN-GUIDE"
    private const val SOURCE_PATH = "demo-fern-guide.md"
    private const val SOURCE_TITLE = "Demo garden guide: tree fern light"
    private val DEMO_TEXT = """# Demo garden guide: tree fern light

A tree fern grows best in bright, indirect light. Avoid harsh direct afternoon sun.
Keep the potting mix evenly moist, but do not leave the roots standing in water.

This sample text exists only to exercise local retrieval and is not horticultural advice.
"""

    fun createDemoLibrary(libraryRoot: File): LibraryInfo {
        require(!existsWithoutFollowingLinks(libraryRoot)) {
            "${libraryRoot.path} already exists; demo setup will not overwrite a library"
        }
        val parent = libraryRoot.absoluteFile.parentFile
            ?: error("library root must have a parent directory")
        require(parent.isDirectory && !isSymbolicLink(parent)) {
            "library root parent directory is unavailable"
        }

        val staging = File(parent, ".${libraryRoot.name}.demo-staging-${UUID.randomUUID()}")
        check(staging.mkdir()) { "cannot create demo library staging directory" }
        var installed = false
        try {
            val source = File(staging, SOURCE_PATH)
            source.writeText(DEMO_TEXT, Charsets.UTF_8)
            val sourceBytes = source.readBytes()
            val sources = JSONArray().put(
                JSONObject()
                    .put("id", SOURCE_ID)
                    .put("title", SOURCE_TITLE)
                    .put("path", SOURCE_PATH)
                    .put("sha256", JsonCanonical.sha256(sourceBytes)),
            )
            val snapshotId = "snapshot-${JsonCanonical.sha256(
                JsonCanonical.encode(sources).toByteArray(Charsets.UTF_8),
            ).take(16)}"
            File(staging, FrozenLibrary.MANIFEST_NAME).writeText(
                JSONObject()
                    .put("schema_version", "1.0")
                    .put("snapshot_id", snapshotId)
                    .put("sources", sources)
                    .toString(),
                Charsets.UTF_8,
            )

            makeReadOnlyTree(staging)
            val verified = FrozenLibrary(staging, snapshotId).info()
            check(verified.available && verified.snapshotId == snapshotId) {
                "generated demo library failed frozen-library validation: ${verified.message}"
            }
            // Some NIO providers copy/delete rather than rename a directory; make only this
            // private, already-validated staging copy writable for that move, then freeze it again.
            makeWritableTree(staging)
            check(moveWithoutReplacing(staging, libraryRoot)) {
                "could not install demo library"
            }
            installed = true
            makeReadOnlyTree(libraryRoot)
            return FrozenLibrary(libraryRoot, snapshotId).info().also { installed ->
                check(installed.available && installed.snapshotId == snapshotId) {
                    "installed demo library failed frozen-library validation: ${installed.message}"
                }
            }
        } catch (failure: Exception) {
            if (installed) {
                makeWritableTree(libraryRoot)
                libraryRoot.deleteRecursively()
            }
            makeWritableTree(staging)
            staging.deleteRecursively()
            throw failure
        }
    }

    private fun moveWithoutReplacing(source: File, target: File): Boolean {
        // Deliberately omit ATOMIC_MOVE: its target-replacement behavior is implementation-specific.
        Files.move(source.toPath(), target.toPath())
        return true
    }

    private fun existsWithoutFollowingLinks(file: File): Boolean = try {
        Files.readAttributes(
            file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
        true
    } catch (_: java.nio.file.NoSuchFileException) {
        false
    }

    private fun isSymbolicLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    private fun makeReadOnlyTree(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::makeReadOnlyTree)
            ?: error("cannot list demo library staging directory")
        check(file.setWritable(false, false) && !file.canWrite()) {
            "demo library staging entries could not be made read-only"
        }
    }

    private fun makeWritableTree(file: File) {
        if (!existsWithoutFollowingLinks(file) || isSymbolicLink(file)) return
        if (file.isDirectory) file.listFiles()?.forEach(::makeWritableTree)
        file.setWritable(true, false)
    }
}
