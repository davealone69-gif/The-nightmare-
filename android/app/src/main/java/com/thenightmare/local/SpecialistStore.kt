package com.thenightmare.local

import android.content.Context

/** Stores specialist profiles separately from settings and model weights. */
class SpecialistStore(context: Context) {
    private val prefs = context.getSharedPreferences("specialist_profiles", Context.MODE_PRIVATE)

    fun list(): List<SpecialistProfile> = synchronized(LOCK) { readLocked() }

    fun save(profile: SpecialistProfile, create: Boolean) = synchronized(LOCK) {
        SpecialistProfiles.validate(profile)
        val current = readLocked()
        val index = current.indexOfFirst { it.id == profile.id }
        if (create) require(index < 0) { "Specialist already exists." }
        else require(index >= 0) { "Specialist no longer exists." }
        val updated = current.toMutableList()
        if (create) updated.add(profile) else updated[index] = profile
        writeLocked(updated)
    }

    fun delete(id: String) = synchronized(LOCK) {
        val current = readLocked()
        require(current.any { it.id == id }) { "Specialist no longer exists." }
        writeLocked(current.filterNot { it.id == id })
    }

    private fun readLocked(): List<SpecialistProfile> {
        val value = prefs.all["profiles"] ?: return emptyList()
        require(value is String) { "Specialist collection has invalid format." }
        return SpecialistProfiles.decode(value)
    }

    private fun writeLocked(profiles: List<SpecialistProfile>) {
        check(prefs.edit().putString("profiles", SpecialistProfiles.encode(profiles)).commit()) { "Could not save specialists." }
    }

    companion object { private val LOCK = Any() }
}
