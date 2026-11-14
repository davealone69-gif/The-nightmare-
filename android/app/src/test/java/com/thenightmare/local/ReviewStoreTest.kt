package com.thenightmare.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReviewStoreTest {
    private fun observation(
        streamId: String = "mandela",
        observationId: String = "observation-1",
        episodeId: String = "episode-1",
    ) = JSONObject()
        .put("event_schema_version", "1.0")
        .put("event_id", "event-$observationId")
        .put("event_type", "MONITOR_OBSERVATION")
        .put("stream_id", streamId)
        .put("observation_id", observationId)
        .put("episode_id", episodeId)
        .put("occurred_at", "2026-11-14T00:00:00Z")
        .put("source_provenance", JSONObject()
            .put("authorship", "machine")
            .put("component", if (streamId == "mandela") "Mandela semantic monitor" else "Matrix mechanism monitor")
            .put("component_version", "1.0"))
        .put("signal", JSONObject()
            .put("code", "TEST_FINDING")
            .put("severity", "P2")
            .put("confidence", 0.88)
            .put("summary", "A test monitor finding")
            .put("coverage", JSONObject.NULL))
        .put("evidence_refs", JSONArray())

    private fun journal(event: JSONObject): ByteArray =
        (JsonCanonical.encode(event) + "\n").toByteArray(StandardCharsets.UTF_8)

    @Test
    fun importedObservationCanBeReviewedAndPreparedContextIsIdempotent() {
        val parent = Files.createTempDirectory("nightmare-review-workflow").toFile()
        try {
            val store = ReviewStore(File(parent, "phase5-review"))
            val bytes = journal(observation())
            assertEquals(1, store.importMonitorJournal("mandela", ByteArrayInputStream(bytes)))
            assertEquals("observation-1", store.listQueue().single().observationId)
            assertThrows(IllegalArgumentException::class.java) {
                store.importMonitorJournal("mandela", ByteArrayInputStream(bytes))
            }

            val decision = store.decide("observation-1", "approve", "Death")
            assertEquals("approve", decision.getString("action"))
            assertTrue(store.listQueue().isEmpty())
            assertEquals("APPROVE", store.listQueue(includeReviewed = true).single().state)

            val prepared = store.prepareNextEpisode("episode-1", "episode-2")
            assertEquals(1, prepared.getJSONArray("guidance").length())
            assertTrue(store.buildNextEpisodeTask("Continue the story", prepared).contains("human_approved_machine_finding"))
            val repeated = store.prepareNextEpisode("episode-1", "episode-2")
            assertEquals(prepared.getString("event_id"), repeated.getString("event_id"))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun concurrentStoreInstancesRecordAtMostOneDecisionForAnObservation() {
        val parent = Files.createTempDirectory("nightmare-review-concurrent").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val root = File(parent, "phase5-review")
            val first = ReviewStore(root)
            val second = ReviewStore(root)
            first.importMonitorJournal("mandela", ByteArrayInputStream(journal(observation())))
            val start = CountDownLatch(1)
            val tasks = listOf(first, second).mapIndexed { index, store ->
                executor.submit<Boolean> {
                    start.await()
                    try {
                        store.decide("observation-1", "approve", "Reviewer-$index")
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            start.countDown()
            val successes = tasks.count { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, successes)
            assertEquals(1, first.listQueue(includeReviewed = true).count { it.decision != null })
            assertFalse(first.listQueue(includeReviewed = true).single().decision!!.optString("action").isBlank())
        } finally {
            executor.shutdownNow()
            parent.deleteRecursively()
        }
    }
}
