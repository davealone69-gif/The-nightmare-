package com.thenightmare.local

import org.json.JSONArray
import org.json.JSONObject

data class SpecialistProfile(val id: String, val name: String, val model: String = "", val instructions: String = "")

object SpecialistProfiles {
    const val MAX_PROFILES = 20
    const val MAX_BYTES = 256 * 1024
    fun validate(profile: SpecialistProfile) {
        require(profile.id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid specialist ID." }
        require(profile.name.isNotBlank() && profile.name.length <= 80) { "Enter a name of at most 80 characters." }
        require(profile.model.length <= 256) { "Model name must be at most 256 characters." }
        require(profile.instructions.length <= 4000) { "Instructions must be at most 4000 characters." }
    }

    fun encode(profiles: List<SpecialistProfile>): String {
        require(profiles.size <= MAX_PROFILES) { "At most $MAX_PROFILES specialists are supported." }
        require(profiles.map { it.id }.toSet().size == profiles.size) { "Duplicate specialist ID." }
        val list = JSONArray()
        profiles.forEach {
            validate(it)
            list.put(JSONObject().put("id", it.id).put("name", it.name).put("model", it.model).put("instructions", it.instructions))
        }
        val result = JSONObject().put("version", 1).put("profiles", list).toString()
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Specialist collection is too large." }
        return result
    }

    fun decode(value: String): List<SpecialistProfile> {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Specialist collection is too large." }
        val root = JSONObject(value)
        require(root.get("version") == 1) { "Unsupported specialist version." }
        val rows = root.getJSONArray("profiles")
        require(rows.length() <= MAX_PROFILES) { "Too many specialists." }
        val profiles = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            fun str(key: String) = row.get(key) as? String ?: error("Invalid specialist $key.")
            SpecialistProfile(str("id"), str("name"), str("model"), str("instructions")).also(::validate)
        }
        require(profiles.map { it.id }.toSet().size == profiles.size) { "Duplicate specialist ID." }
        return profiles
    }
}
