package com.thenightmare.local

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections

class LocalHarnessIntegrationTest {
    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        return server
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun settings(server: HttpServer) = AppSettings(
        baseUrl = "http://127.0.0.1:${server.address.port}/v1",
        model = "local-test-model",
        timeoutSeconds = 3.0,
    )

    @Test
    fun non2xxEndpointAndModelResponsesFailAndExceptionPathStillResetsDeclaredState() {
        val server = startServer { exchange -> respond(exchange, 503, "{\"error\":\"test unavailable\"}") }
        val root = Files.createTempDirectory("nightmare-unavailable-library").toFile()
        try {
            val configured = settings(server)
            val connectionError = assertThrows(IOException::class.java) {
                LocalHarness.testConnection(configured)
            }
            assertTrue(connectionError.message.orEmpty().contains("HTTP 503"))

            val episodeError = assertThrows(IOException::class.java) {
                LocalHarness.run(
                    "Summarize a fictional scene",
                    configured,
                    FrozenLibrary(File(root, "not-imported"), ""),
                )
            }
            assertTrue(episodeError.message.orEmpty().contains("HTTP 503"))
            val audit = LocalHarness.lastResetAuditReport
            assertNotNull(audit)
            assertTrue(audit!!.passed)
            assertEquals(setOf("task_context", "audit_seed", "worker_counters"), audit.clearedChannels.toSet())
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }

    @Test
    fun replayRunsThreeWorkersTwiceAndNeverSendsTheAuditSeedToOllama() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val workerContent = JSONObject()
            .put("status", "COMPLETED")
            .put("confidence", 0.82)
            .put("content", "A concise local answer.")
            .put("reason", "")
            .toString()
        val body = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", workerContent))))
            .toString()
        val server = startServer { exchange ->
            requests.add(exchange.requestBody.use { String(it.readBytes(), StandardCharsets.UTF_8) })
            respond(exchange, 200, body)
        }
        val root = Files.createTempDirectory("nightmare-replay-library").toFile()
        try {
            val result = LocalHarness.run(
                "Summarize a fictional scene",
                settings(server),
                FrozenLibrary(File(root, "not-imported"), ""),
                replayAudit = true,
                seed = 8675309L,
            )
            val captured = synchronized(requests) { requests.toList() }
            assertEquals(6, captured.size)
            assertFalse(captured.any { it.contains("8675309") || it.contains("audit_seed") })
            assertEquals(EpisodeStatus.COMPLETED, result.status)
            assertEquals("A concise local answer.", result.answer)
            assertEquals(CitationStatus.NOT_REQUIRED, result.citationStatus)
            assertTrue(result.resetAuditPassed == true)
            assertTrue(result.workerOutputFingerprintsEqual == true)
            assertTrue(result.auditStateSha256?.matches(Regex("[0-9a-f]{64}")) == true)
            val audit = LocalHarness.lastResetAuditReport
            assertNotNull(audit)
            assertTrue(audit!!.passed)
            assertEquals(setOf("task_context", "audit_seed", "worker_results", "worker_counters"), audit.clearedChannels.toSet())
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidExistingLibraryAbstainsForOrdinaryTaskWithoutOllamaAndPassesReplayResetAudit() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = startServer { exchange ->
            requests.add(exchange.requestBody.use { String(it.readBytes(), StandardCharsets.UTF_8) })
            respond(exchange, 200, "{}")
        }
        val root = Files.createTempDirectory("nightmare-invalid-library").toFile()
        try {
            val result = LocalHarness.run(
                "Summarize a fictional scene",
                settings(server),
                FrozenLibrary(root, "snapshot-0000000000000000"),
                replayAudit = true,
                seed = 8675309L,
            )

            assertEquals(EpisodeStatus.ABSTAINED, result.status)
            assertEquals(1, result.workers.size)
            assertEquals("Retriever", result.workers.single().role)
            assertEquals(WorkerStatus.ABSTAINED, result.workers.single().status)
            assertEquals("LIBRARY_INTEGRITY_FAILURE", result.workers.single().reason)
            assertTrue(requests.isEmpty())
            assertTrue(result.resetAuditPassed == true)
            val audit = LocalHarness.lastResetAuditReport
            assertNotNull(audit)
            assertTrue(audit!!.passed)
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }
}
