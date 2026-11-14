package com.thenightmare.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingExamplesTest {
    private val example = TrainingExample(
        "12345678-1234-1234-1234-123456789abc", "Explain shader roughness", "Review the material settings.",
        "My own example; permission granted", true,
    )

    @Test fun examplesRoundTripWithoutLosingApprovalOrUnicode() {
        val input = listOf(example, example.copy(id = "abcdefab-1234-1234-1234-123456789abc", reference = "Lumière", approved = false))
        assertEquals(input, TrainingExamples.decode(TrainingExamples.encode(input)))
    }

    @Test fun rejectsEmptyOversizeAndDuplicateExamples() {
        assertThrows(IllegalArgumentException::class.java) { TrainingExamples.encode(listOf(example.copy(instruction = " "))) }
        assertThrows(IllegalArgumentException::class.java) { TrainingExamples.encode(listOf(example.copy(reference = "a".repeat(TrainingExamples.MAX_FIELD + 1)))) }
        assertThrows(IllegalArgumentException::class.java) { TrainingExamples.encode(listOf(example.copy(source = ""))) }
        assertThrows(IllegalArgumentException::class.java) { TrainingExamples.encode(listOf(example, example)) }
        assertThrows(IllegalArgumentException::class.java) { TrainingExamples.decode(ByteArray(TrainingExamples.MAX_BYTES + 1)) }
    }

    @Test fun rejectsUnsupportedVersionsAndInvalidApprovalTypes() {
        assertThrows(IllegalArgumentException::class.java) { TrainingExamples.decode("{\"version\":2,\"examples\":[]}".toByteArray()) }
        val json = String(TrainingExamples.encode(listOf(example))).replace("\"approved\":true", "\"approved\":\"true\"")
        assertThrows(IllegalStateException::class.java) { TrainingExamples.decode(json.toByteArray()) }
    }

    @Test fun curriculumCoversTheOwners3dGoals() {
        val titles = TrainingCurriculum.lessons.map { it.first }
        assertTrue(titles.any { it.contains("Shaders") })
        assertTrue(titles.any { it.contains("avatars", ignoreCase = true) })
        assertTrue(titles.any { it.contains("Rendering") })
    }
}
