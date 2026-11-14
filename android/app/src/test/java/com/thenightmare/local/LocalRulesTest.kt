package com.thenightmare.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalRulesTest {
    @Test
    fun acceptsOnlyHttpLoopbackEndpoints() {
        assertEquals("http://127.0.0.1:11434/v1", LocalHarness.validateBaseUrl("http://127.0.0.1:11434/v1"))
        assertEquals("http://localhost:11434/v1", LocalHarness.validateBaseUrl("http://localhost:11434/v1/"))
        assertEquals("http://[::1]:11434/v1", LocalHarness.validateBaseUrl("http://[::1]:11434/v1"))
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateBaseUrl("https://127.0.0.1:11434/v1") }
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateBaseUrl("http://example.com/v1") }
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateBaseUrl("http://localhost.attacker.invalid/v1") }
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateBaseUrl("http://192.168.1.12:11434/v1") }
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateBaseUrl("http://127.0.0.2:11434/v1") }
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateBaseUrl("http://127.0.0.1/v1?redirect=1") }
    }

    @Test
    fun confidenceMustBeFiniteAndBounded() {
        assertEquals(0.5, LocalHarness.validateConfidence(0.5), 0.0)
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateConfidence(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateConfidence(1.01) }
    }

    @Test
    fun citationsDistinguishValidMissingAndUnknownIds() {
        val source = SourceReference("DOC-1", "Guide", "guide.md", "a".repeat(64))
        val retriever = WorkerResult("Retriever", WorkerStatus.COMPLETED, 0.8, "verified", sourceRefs = listOf(source))
        val formatter = WorkerResult("Formatter", WorkerStatus.COMPLETED, 0.7, "Fern [DOC-1]")
        assertEquals(CitationStatus.CITED, EpisodeResult(EpisodeStatus.COMPLETED, 0.7, listOf(retriever, formatter), "Fern [DOC-1]").citationStatus)
        assertEquals(CitationStatus.UNCITED, EpisodeResult(EpisodeStatus.COMPLETED, 0.7, listOf(retriever, formatter), "Fern").citationStatus)
        assertEquals(CitationStatus.INVALID, EpisodeResult(EpisodeStatus.COMPLETED, 0.7, listOf(retriever, formatter), "Fern [UNKNOWN]").citationStatus)
        assertEquals(CitationStatus.NOT_REQUIRED, EpisodeResult(EpisodeStatus.COMPLETED, 0.7, listOf(formatter), "Fern").citationStatus)
    }

    @Test
    fun canonicalJsonSortsObjectKeysAndMatchesSha256Vectors() {
        assertEquals("{\"a\":\"é\",\"z\":[true,2]}", JsonCanonical.encode(mapOf("z" to listOf(true, 2), "a" to "é")))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", JsonCanonical.sha256("abc"))
    }

    @Test
    fun finiteFractionalTimeoutsAndLargeTermuxCompatibleValuesAreAccepted() {
        assertEquals(0.25, LocalHarness.validateTimeout(0.25), 0.0)
        assertEquals(900.5, LocalHarness.validateTimeout(900.5), 0.0)
        assertEquals(LocalHarness.MAX_TIMEOUT_SECONDS, LocalHarness.validateTimeout(LocalHarness.MAX_TIMEOUT_SECONDS), 0.0)
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateTimeout(0.0) }
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateTimeout(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { LocalHarness.validateTimeout(LocalHarness.MAX_TIMEOUT_SECONDS + 1.0) }
    }

    @Test
    fun workerAndEpisodeModelsRejectInconsistentOutcomes() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkerResult("Retriever", WorkerStatus.COMPLETED, 0.0, "empty confidence")
        }
        val abstained = WorkerResult("Formatter", WorkerStatus.ABSTAINED, 0.0, reason = "INVALID_RESPONSE")
        assertThrows(IllegalArgumentException::class.java) {
            EpisodeResult(EpisodeStatus.ABSTAINED, 0.0, listOf(abstained), abstainingWorker = "Analyst", reason = abstained.reason)
        }
    }
}
