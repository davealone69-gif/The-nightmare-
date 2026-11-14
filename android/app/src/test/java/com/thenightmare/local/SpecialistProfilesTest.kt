package com.thenightmare.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpecialistProfilesTest {
    private val profile = SpecialistProfile("12345678-1234-1234-1234-123456789abc", "Shader", "model-a", "Be precise")

    @Test fun profileRoundTripAndBlankDefaults() {
        assertEquals(listOf(profile), SpecialistProfiles.decode(SpecialistProfiles.encode(listOf(profile))))
        val blank = profile.copy(model = "", instructions = "")
        assertEquals(listOf(blank), SpecialistProfiles.decode(SpecialistProfiles.encode(listOf(blank))))
    }

    @Test fun rejectsInvalidAndDuplicateProfiles() {
        assertThrows(IllegalArgumentException::class.java) { SpecialistProfiles.encode(listOf(profile.copy(name = " "))) }
        assertThrows(IllegalArgumentException::class.java) { SpecialistProfiles.encode(listOf(profile, profile)) }
        assertThrows(IllegalArgumentException::class.java) { SpecialistProfiles.encode(listOf(profile.copy(instructions = "x".repeat(4001)))) }
        assertThrows(IllegalArgumentException::class.java) { SpecialistProfiles.decode("{\"version\":2,\"profiles\":[]}") }
    }
}
