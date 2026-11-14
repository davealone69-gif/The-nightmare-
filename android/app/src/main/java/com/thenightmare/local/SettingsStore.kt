package com.thenightmare.local

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("local_settings", Context.MODE_PRIVATE)
    var loadWarning: String? = null
        private set

    fun load(): AppSettings {
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
        if (needsRepair) save(result)
        return result
    }

    fun save(settings: AppSettings) {
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

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:11434/v1"
        const val MAX_MODEL_LENGTH = 256
        const val MAX_REVIEWER_LENGTH = 128
        private val SNAPSHOT_PIN = Regex("snapshot-[0-9a-f]{16}")
    }
}
