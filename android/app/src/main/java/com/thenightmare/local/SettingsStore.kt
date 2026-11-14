package com.thenightmare.local

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("local_settings", Context.MODE_PRIVATE)
    var loadWarning: String? = null
        private set

    fun load(): AppSettings = synchronized(PREFERENCES_LOCK) { loadLocked() }

    private fun loadLocked(): AppSettings {
        val values = preferences.all
        val warnings = mutableListOf<String>()
        var needsRepair = false

        fun string(key: String, fallback: String): String {
            val raw = values[key] ?: return fallback
            if (raw is String) return raw
            warnings += "Invalid saved $key was reset."
            needsRepair = true
            return fallback
        }

        val rawBase = string("base_url", DEFAULT_BASE_URL)
        val base = try {
            LocalHarness.validateBaseUrl(rawBase)
        } catch (_: Exception) {
            warnings += "Invalid saved loopback URL was reset."
            needsRepair = true
            DEFAULT_BASE_URL
        }

        val model = string("model", "").trim().takeIf { it.length <= MAX_MODEL_LENGTH } ?: run {
            warnings += "Invalid saved model identifier was reset."
            needsRepair = true
            ""
        }

        val rawTimeout = values["timeout_seconds"]
        val timeout = when (rawTimeout) {
            null -> 90.0
            is Number -> rawTimeout.toDouble().also { needsRepair = true }
            is String -> rawTimeout.toDoubleOrNull()
            else -> null
        }
        val validTimeout = timeout?.takeIf { it.isFinite() && it > 0.0 && it <= LocalHarness.MAX_TIMEOUT_SECONDS }
        if (rawTimeout != null && validTimeout == null) {
            warnings += "Invalid saved timeout was reset."
            needsRepair = true
        }

        val rawPin = string("library_snapshot_id", "").trim()
        val pin = rawPin.takeIf { it.isEmpty() || it.matches(SNAPSHOT_PIN) } ?: run {
            warnings += "Invalid saved library pin was reset."
            needsRepair = true
            ""
        }
        val rawReviewer = string("reviewer_id", "").trim()
        val reviewer = rawReviewer.takeIf { it.length <= MAX_REVIEWER_LENGTH } ?: run {
            warnings += "Invalid saved reviewer label was reset."
            needsRepair = true
            ""
        }

        val result = AppSettings(
            baseUrl = base,
            model = model,
            timeoutSeconds = validTimeout ?: 90.0,
            librarySnapshotId = pin,
            reviewerId = reviewer,
        )
        loadWarning = warnings.distinct().joinToString(" ").ifBlank { null }
        if (needsRepair) saveLocked(result)
        return result
    }

    fun save(settings: AppSettings) = synchronized(PREFERENCES_LOCK) { saveLocked(settings) }

    private fun saveLocked(settings: AppSettings) {
        val base = LocalHarness.validateBaseUrl(settings.baseUrl)
        val model = settings.model.trim()
        require(model.length <= MAX_MODEL_LENGTH) { "model identifier must be at most $MAX_MODEL_LENGTH characters" }
        val timeout = LocalHarness.validateTimeout(settings.timeoutSeconds)
        val pin = settings.librarySnapshotId.trim()
        require(pin.isEmpty() || pin.matches(SNAPSHOT_PIN)) {
            "snapshot pin must look like snapshot- followed by 16 lowercase hex characters"
        }
        val reviewer = settings.reviewerId.trim()
        require(reviewer.length <= MAX_REVIEWER_LENGTH) { "reviewer label is too long" }

        check(preferences.edit()
            .putString("base_url", base)
            .putString("model", model)
            .putString("timeout_seconds", timeout.toString())
            .putString("library_snapshot_id", pin)
            .putString("reviewer_id", reviewer)
            .commit()) { "settings could not be saved to app-private storage" }
    }

    fun saveMerged(edited: AppSettings, baseline: AppSettings): AppSettings = synchronized(PREFERENCES_LOCK) {
        val current = loadLocked()
        val merged = mergeSettings(edited, baseline, current)
        saveLocked(merged)
        merged
    }

    fun updateLibrarySnapshotId(snapshotId: String, expectedCurrentPin: String): AppSettings = synchronized(PREFERENCES_LOCK) {
        val current = loadLocked()
        require(current.librarySnapshotId == expectedCurrentPin) { "library pin changed after confirmation; review the library action again" }
        val updated = current.copy(librarySnapshotId = snapshotId)
        saveLocked(updated)
        updated
    }

    fun restoreLibrarySnapshotId(previousPin: String, attemptedPin: String) = synchronized(PREFERENCES_LOCK) {
        val current = loadLocked()
        when (current.librarySnapshotId) {
            previousPin -> Unit
            attemptedPin -> saveLocked(current.copy(librarySnapshotId = previousPin))
            else -> error("library pin changed during rollback; refusing to overwrite newer settings")
        }
    }

    companion object {
        private val PREFERENCES_LOCK = Any()
        const val DEFAULT_BASE_URL = "http://127.0.0.1:11434/v1"
        const val MAX_MODEL_LENGTH = 256
        const val MAX_REVIEWER_LENGTH = 128
        private const val DEFAULT_DATA_DIRECTORY = "../the-nightmare-data"
        private val SNAPSHOT_PIN = Regex("snapshot-[0-9a-f]{16}")

        internal fun mergeSettings(edited: AppSettings, baseline: AppSettings, current: AppSettings): AppSettings {
            if (edited.librarySnapshotId != baseline.librarySnapshotId &&
                current.librarySnapshotId != baseline.librarySnapshotId &&
                current.librarySnapshotId != edited.librarySnapshotId) {
                error("library pin changed while settings were being edited; review the current pin before saving")
            }
            return AppSettings(
                baseUrl = if (edited.baseUrl != baseline.baseUrl) edited.baseUrl else current.baseUrl,
                model = if (edited.model != baseline.model) edited.model else current.model,
                timeoutSeconds = if (edited.timeoutSeconds != baseline.timeoutSeconds) edited.timeoutSeconds else current.timeoutSeconds,
                librarySnapshotId = if (edited.librarySnapshotId != baseline.librarySnapshotId) edited.librarySnapshotId else current.librarySnapshotId,
                reviewerId = if (edited.reviewerId != baseline.reviewerId) edited.reviewerId else current.reviewerId,
            )
        }

        fun parseTermuxConfig(jsonText: String): ImportedTermuxConfig {
            val config = try {
                JSONObject(jsonText)
            } catch (exception: JSONException) {
                throw IllegalArgumentException("Termux config must be a JSON object", exception)
            }
            val allowedKeys = setOf("base_url", "model", "timeout_seconds", "data_dir", "library_snapshot_id")
            val unknown = config.keys().asSequence().filterNot(allowedKeys::contains).toList().sorted()
            require(unknown.isEmpty()) { "unknown config setting(s): ${unknown.joinToString(", ")}" }

            val baseUrl = config.valueOrDefault("base_url", DEFAULT_BASE_URL).requireString("base_url")
            val validatedBaseUrl = try {
                LocalHarness.validateBaseUrl(baseUrl)
            } catch (exception: Exception) {
                throw IllegalArgumentException("invalid base_url", exception)
            }

            val model = config.valueOrDefault("model", "").requireString("model").trim()
            require(model.length <= MAX_MODEL_LENGTH) {
                "model identifier must be at most $MAX_MODEL_LENGTH characters"
            }

            val timeoutValue = config.valueOrDefault("timeout_seconds", 90)
            require(timeoutValue is Number) {
                "timeout_seconds must be a positive number"
            }
            val timeout = timeoutValue.toDouble()
            LocalHarness.validateTimeout(timeout)

            val dataDirectory = config.valueOrDefault("data_dir", DEFAULT_DATA_DIRECTORY)
            require(dataDirectory is String && dataDirectory.trim().isNotEmpty() &&
                !java.io.File(dataDirectory).isAbsolute) {
                "data_dir must be a non-empty relative path"
            }

            val snapshotValue = if (config.has("library_snapshot_id")) config.opt("library_snapshot_id") else ""
            val snapshot = when (snapshotValue) {
                JSONObject.NULL, null -> ""
                is String -> snapshotValue
                else -> throw IllegalArgumentException("library_snapshot_id must be a string")
            }
            require(snapshot.isEmpty() || snapshot.matches(SNAPSHOT_PIN)) {
                "library_snapshot_id must be a valid pinned snapshot id"
            }

            return ImportedTermuxConfig(
                baseUrl = validatedBaseUrl,
                model = model,
                timeoutSeconds = timeout,
                librarySnapshotId = snapshot,
                dataDirectoryIgnored = config.has("data_dir"),
            )
        }

        private fun JSONObject.valueOrDefault(key: String, default: Any): Any =
            if (has(key)) opt(key) else default

        private fun Any?.requireString(key: String): String =
            this as? String ?: throw IllegalArgumentException("$key must be a string")
    }
}

data class ImportedTermuxConfig(
    val baseUrl: String,
    val model: String,
    val timeoutSeconds: Double,
    val librarySnapshotId: String,
    val dataDirectoryIgnored: Boolean,
)
