package com.thenightmare.local

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsStoreTest {
    @Test
    fun parsesValidImport() {
        val imported = SettingsStore.parseTermuxConfig(
            """
            {
              "base_url": "http://localhost:11434/v1/",
              "model": "  llama3.2  ",
              "timeout_seconds": 12.5,
              "data_dir": "../external-data",
              "library_snapshot_id": "snapshot-0123456789abcdef"
            }
            """.trimIndent(),
        )

        assertEquals("http://localhost:11434/v1", imported.baseUrl)
        assertEquals("llama3.2", imported.model)
        assertEquals(12.5, imported.timeoutSeconds, 0.0)
        assertEquals("snapshot-0123456789abcdef", imported.librarySnapshotId)
        assertEquals(true, imported.dataDirectoryIgnored)
    }

    @Test
    fun reportsDataDirectoryAsIgnoredWithoutInterpretingIt() {
        val imported = SettingsStore.parseTermuxConfig("{\"data_dir\":\"../external-data\"}")

        assertEquals(true, imported.dataDirectoryIgnored)
        assertEquals(SettingsStore.DEFAULT_BASE_URL, imported.baseUrl)
        assertEquals("", imported.model)
        assertEquals(90.0, imported.timeoutSeconds, 0.0)
        assertEquals("", imported.librarySnapshotId)
    }

    @Test
    fun appliesPythonConfigDefaults() {
        val imported = SettingsStore.parseTermuxConfig("{}")

        assertEquals(SettingsStore.DEFAULT_BASE_URL, imported.baseUrl)
        assertEquals("", imported.model)
        assertEquals(90.0, imported.timeoutSeconds, 0.0)
        assertEquals("", imported.librarySnapshotId)
        assertEquals(false, imported.dataDirectoryIgnored)
    }

    @Test
    fun rejectsUnknownKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"reviewer_id\":\"reviewer\"}")
        }
    }

    @Test
    fun rejectsInvalidUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"base_url\":\"https://example.com/v1\"}")
        }
    }

    @Test
    fun rejectsWrongFieldTypes() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"model\":123}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"timeout_seconds\":\"90\"}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"library_snapshot_id\":123}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"data_dir\":123}")
        }
    }

    @Test
    fun rejectsInvalidTimeoutsPinsAndDataDirectories() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"timeout_seconds\":0}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"timeout_seconds\":${LocalHarness.MAX_TIMEOUT_SECONDS + 1.0}}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"library_snapshot_id\":\"snapshot-not-a-pin\"}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"data_dir\":\"   \"}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsStore.parseTermuxConfig("{\"data_dir\":\"/absolute/path\"}")
        }
    }

    @Test
    fun parserHasNoFilesystemSideEffects() {
        val marker = Paths.get("../../termux-parser-must-not-create-${System.nanoTime()}")
        assertFalse(Files.exists(marker))

        SettingsStore.parseTermuxConfig("{\"data_dir\":${org.json.JSONObject.quote(marker.toString())}}")

        assertFalse(Files.exists(marker))
    }
}
