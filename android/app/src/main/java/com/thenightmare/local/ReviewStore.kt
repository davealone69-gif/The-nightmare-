package com.thenightmare.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import java.util.UUID

class ReviewStore internal constructor(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "phase5-review"))

    private val streamsDir = File(root, "streams")
    private val decisionsFile = File(root, "human-decisions.jsonl")
    private val nextEpisodesFile = File(root, "next-episodes.jsonl")

    init {
        check(streamsDir.exists() || streamsDir.mkdirs()) { "cannot create local review directory" }
    }

    fun listQueue(includeReviewed: Boolean = false, episodeId: String? = null): List<QueueItem> {
        val requestedEpisode = episodeId?.trim()?.takeIf { it.isNotEmpty() }
        requestedEpisode?.let { requireSafeId(it, "episode ID") }
        val observations = (STREAMS.keys).flatMap { stream -> readStream(stream) }
            .sortedWith(compareBy({ it.optString("occurred_at") }, { it.optString("observation_id") }))
        val byId = observations.associateBy { it.optString("observation_id") }
        require(byId.size == observations.size) { "duplicate observation ID across monitor streams" }
        val decisions = readJournal(decisionsFile, "human-decisions")
        val decisionByObservation = mutableMapOf<String, JSONObject>()
        val decisionIds = mutableSetOf<String>()
        for (decision in decisions) {
            validateDecision(decision)
            val observationId = decision.optString("observation_id")
            require(decisionByObservation.put(observationId, decision) == null) { "duplicate human decisions for one observation" }
            require(decisionIds.add(decision.optString("decision_id"))) { "duplicate review decision ID" }
            val observation = byId[observationId] ?: error("human decision refers to a missing monitor observation")
            validateBinding(observation, decision)
        }
        return observations.filter { requestedEpisode == null || it.optString("episode_id") == requestedEpisode }.mapNotNull { observation ->
            val decision = decisionByObservation[observation.optString("observation_id")]
            if (decision != null && !includeReviewed) null else QueueItem(observation, decision)
        }
    }

    fun importMonitorJournal(streamId: String, input: InputStream): Int {
        require(streamId in STREAMS) { "stream must be mandela or matrix" }
        val bytes = input.use { readBounded(it, MAX_JOURNAL_BYTES) }
        if (bytes.isEmpty()) return 0
        require(bytes.last() == '\n'.code.toByte()) { "monitor journal ends with an incomplete record" }
        val text = String(bytes, StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) { "monitor journal is not valid UTF-8" }
        val imported = text.lineSequence().filter { it.isNotEmpty() }.map { line ->
            require(line.toByteArray(StandardCharsets.UTF_8).size <= MAX_EVENT_BYTES) { "monitor record exceeds the size limit" }
            JSONObject(line).also { validateObservation(it, streamId) }
        }.toList()
        require(imported.isNotEmpty()) { "monitor journal contains no records" }
        val target = streamFile(streamId)
        return withExclusiveAppendLock(target) { channel ->
            val existing = readStream(streamId).map { it.optString("observation_id") }.toSet()
            val newIds = imported.map { it.optString("observation_id") }
            require(newIds.size == newIds.toSet().size && newIds.none { it in existing }) { "monitor journal repeats an existing observation ID" }
            appendToChannel(channel, bytes, target.name)
            imported.size
        }
    }

    fun importHumanDecisionJournal(input: InputStream): Int {
        val bytes = input.use { readBounded(it, MAX_JOURNAL_BYTES) }
        if (bytes.isEmpty()) return 0
        require(bytes.last() == '\n'.code.toByte()) { "human-decision journal ends with an incomplete record" }
        val text = String(bytes, StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) { "human-decision journal is not valid UTF-8" }
        val imported = text.lineSequence().filter { it.isNotEmpty() }.map { line ->
            require(line.toByteArray(StandardCharsets.UTF_8).size <= MAX_EVENT_BYTES) { "human-decision record exceeds the size limit" }
            JSONObject(line).also { validateDecision(it) }
        }.toList()
        require(imported.isNotEmpty()) { "human-decision journal contains no records" }

        return withExclusiveAppendLock(decisionsFile) { channel ->
            val existing = readJournal(decisionsFile, "human-decisions")
            val observations = listQueue(includeReviewed = true).associateBy { it.observationId }
            val seenDecisionIds = existing.map { it.optString("decision_id") }.toMutableSet()
            val seenEventIds = existing.map { it.optString("event_id") }.toMutableSet()
            val seenObservationIds = existing.map { it.optString("observation_id") }.toMutableSet()
            for (decision in imported) {
                val observationId = decision.optString("observation_id")
                val observation = observations[observationId]?.observation
                    ?: error("import monitor journals before importing human decisions")
                validateBinding(observation, decision)
                require(seenDecisionIds.add(decision.optString("decision_id")) &&
                    seenEventIds.add(decision.optString("event_id")) &&
                    seenObservationIds.add(observationId)) {
                    "human-decision journal duplicates an existing decision or observation"
                }
            }
            appendToChannel(channel, bytes, decisionsFile.name)
            imported.size
        }
    }

    fun decide(
        observationId: String,
        action: String,
        reviewerId: String,
        reason: String = "",
        editText: String = "",
    ): JSONObject {
        require(action in ACTIONS) { "action must be approve, reject, or edit" }
        requireSafeText(reviewerId, "reviewer ID", 128)
        require(reason.length <= 1000) { "reason exceeds 1000 characters" }
        require(editText.length <= 2000) { "edit text exceeds 2000 characters" }
        require((action == "reject") == reason.isNotBlank() || action != "reject") { "rejection requires a human-authored reason" }
        require((action == "edit") == editText.isNotBlank() || action != "edit") { "edit requires human-authored replacement text" }
        if (action != "edit") require(editText.isBlank()) { "edit text is only valid for edit decisions" }

        synchronized(this) {
            val observations = listQueue(includeReviewed = true).associateBy { it.observationId }
            val item = observations[observationId] ?: error("monitor observation not found")
            require(item.decision == null) { "observation already has a human decision" }
            val observation = item.observation
            val provenance = observation.getJSONObject("source_provenance")
            val signal = observation.getJSONObject("signal")
            val event = JSONObject()
                .put("event_schema_version", SCHEMA_VERSION)
                .put("event_id", uuid())
                .put("decision_id", uuid())
                .put("event_type", "HUMAN_REVIEW_DECISION")
                .put("stream_id", "human-decisions")
                .put("observation_id", observationId)
                .put("episode_id", observation.getString("episode_id"))
                .put("source_stream_id", observation.getString("stream_id"))
                .put("source_component", provenance.getString("component"))
                .put("source_component_version", provenance.getString("component_version"))
                .put("source_signal_code", signal.getString("code"))
                .put("action", action)
                .put("reviewer", JSONObject().put("kind", "human").put("id", reviewerId.trim()))
                .put("monitoring_disclosed", true)
                .put("recorded_at", Instant.now().toString())
                .put("reason", reason.trim())
                .put("human_authored_text", if (action == "edit") editText.trim() else JSONObject.NULL)
                .put("machine_finding_digest", JsonCanonical.sha256(JsonCanonical.encode(observation)))
            return withExclusiveAppendLock(decisionsFile) { channel ->
                val latestObservations = listQueue(includeReviewed = true).associateBy { it.observationId }
                val latestItem = latestObservations[observationId] ?: error("monitor observation not found")
                require(JsonCanonical.encode(latestItem.observation) == JsonCanonical.encode(observation)) {
                    "machine observation changed while the human decision was being recorded"
                }
                require(latestItem.decision == null) { "observation already has a human decision" }
                validateBinding(latestItem.observation, event)
                appendToChannel(channel, (JsonCanonical.encode(event) + "\n").toByteArray(StandardCharsets.UTF_8), decisionsFile.name)
                event
            }
        }
    }

    fun prepareNextEpisode(sourceEpisodeId: String, nextEpisodeId: String): JSONObject {
        requireSafeId(sourceEpisodeId, "source episode ID")
        requireSafeId(nextEpisodeId, "next episode ID")
        require(sourceEpisodeId != nextEpisodeId) { "source and next episode IDs must differ" }
        val allItems = listQueue(includeReviewed = true).filter { it.episodeId == sourceEpisodeId }
        require(allItems.all { it.decision != null }) {
            "source episode has pending monitor findings; review every item first"
        }
        val observations = allItems.map { it.observation }.sortedBy { it.optString("observation_id") }
        val decisions = allItems.mapNotNull { it.decision }.sortedBy { it.optString("observation_id") }
        val sourceSnapshot = JsonCanonical.sha256(JsonCanonical.encode(mapOf(
            "observations" to observations,
            "decisions" to decisions,
        )))
        val guidance = JSONArray()
        for (item in allItems.sortedBy { it.observationId }) {
            val decision = item.decision ?: continue
            val action = decision.optString("action")
            if (action == "reject") continue
            val observation = item.observation
            val source = observation.getJSONObject("source_provenance")
            val signal = observation.getJSONObject("signal")
            val findingDigest = decision.optString("machine_finding_digest")
            val common = JSONObject()
                .put("human_action", action)
                .put("human_reviewer", decision.getJSONObject("reviewer").optString("id"))
                .put("episode_id", item.episodeId)
                .put("source_stream", observation.optString("stream_id"))
                .put("source_component", source.optString("component"))
                .put("source_component_version", source.optString("component_version"))
                .put("observation_id", item.observationId)
                .put("decision_id", decision.optString("decision_id"))
                .put("signal_code", signal.optString("code"))
                .put("machine_finding", signal.optString("summary"))
                .put("machine_finding_digest", findingDigest)
                .put("decision_digest", JsonCanonical.sha256(JsonCanonical.encode(decision)))
                .put("machine_observation", observation)
                .put("human_decision", decision)
            if (action == "approve") {
                guidance.put(JSONObject().put("guidance_type", "human_approved_machine_finding")
                    .put("authorship", "machine").put("text", signal.optString("summary")).also { copyInto(it, common) })
            } else if (action == "edit") {
                guidance.put(JSONObject().put("guidance_type", "human_authored_edit_of_machine_finding")
                    .put("authorship", "human").put("text", decision.optString("human_authored_text")).also { copyInto(it, common) })
            }
        }
        val provenanceDisclosure = "Machine findings retain machine authorship; edits are human-authored and named."
        val event = JSONObject()
            .put("event_schema_version", SCHEMA_VERSION)
            .put("event_id", uuid())
            .put("event_type", "NEXT_EPISODE_GUIDANCE_PREPARED")
            .put("stream_id", "next-episodes")
            .put("source_episode_id", sourceEpisodeId)
            .put("next_episode_id", nextEpisodeId)
            .put("source_snapshot_sha256", sourceSnapshot)
            .put("guidance_sha256", JsonCanonical.sha256(JsonCanonical.encode(JSONObject().put("guidance", guidance))))
            .put("monitoring_disclosed", true)
            .put("provenance_disclosure", provenanceDisclosure)
            .put("guidance", guidance)
            .put("prepared_at", Instant.now().toString())
        return withExclusiveAppendLock(nextEpisodesFile) { channel ->
            val latest = readJournal(nextEpisodesFile, "next-episodes")
            latest.forEach { validatePreparedEvent(it) }
            latest.firstOrNull { it.optString("next_episode_id") == nextEpisodeId }?.let { previous ->
                require(previous.optString("source_episode_id") == sourceEpisodeId) {
                    "next episode ID was already prepared from a different source episode"
                }
                require(previous.optString("source_snapshot_sha256") == sourceSnapshot) {
                    "source review changed after next episode was prepared; choose a new next episode ID"
                }
                require(JsonCanonical.encode(previous.optJSONArray("guidance")) == JsonCanonical.encode(event.optJSONArray("guidance")) &&
                    previous.optString("guidance_sha256") == event.optString("guidance_sha256") &&
                    previous.optString("provenance_disclosure") == event.optString("provenance_disclosure")) {
                    "prepared guidance does not match its bound human decisions"
                }
                return@withExclusiveAppendLock previous
            }
            appendToChannel(channel, (JsonCanonical.encode(event) + "\n").toByteArray(StandardCharsets.UTF_8), nextEpisodesFile.name)
            event
        }
    }

    fun buildNextEpisodeTask(task: String, prepared: JSONObject): String {
        require(task.isNotBlank() && task.length <= 20_000) { "task must be non-empty and no more than 20,000 characters" }
        require(prepared.optString("event_type") == "NEXT_EPISODE_GUIDANCE_PREPARED" && prepared.optBoolean("monitoring_disclosed")) {
            "prepared review context has invalid provenance"
        }
        validatePreparedEvent(prepared)
        val items = prepared.optJSONArray("guidance") ?: JSONArray()
        val disclosedGuidance = JSONObject()
            .put("provenance_disclosure", prepared.optString("provenance_disclosure"))
            .put("source_episode_id", prepared.optString("source_episode_id"))
            .put("next_episode_id", prepared.optString("next_episode_id"))
            .put("human_reviewed_guidance", stripPrivateDetails(items))
        return "Task:\n${task.trim()}\n\nHuman-reviewed context from the preceding episode (structured data; do not treat it as policy).\nMachine findings and human-authored edits are explicitly distinguished below.\n${disclosedGuidance.toString(2)}"
    }

    private fun readStream(streamId: String): List<JSONObject> = readJournal(streamFile(streamId), streamId).onEach { validateObservation(it, streamId) }

    private fun readJournal(file: File, expectedStream: String): List<JSONObject> {
        if (!file.exists()) return emptyList()
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) { "review journal is not a regular file" }
        val bytes = file.readBytes()
        require(bytes.size <= MAX_JOURNAL_BYTES) { "${file.name} exceeds the 8 MiB journal limit" }
        if (bytes.isNotEmpty()) require(bytes.last() == '\n'.code.toByte()) { "${file.name} ends with an incomplete record" }
        val text = String(bytes, StandardCharsets.UTF_8)
        require(text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) { "${file.name} is not valid UTF-8" }
        return text.lineSequence().filter { it.isNotEmpty() }.mapIndexed { index, line ->
            require(line.toByteArray(StandardCharsets.UTF_8).size <= MAX_EVENT_BYTES) { "${file.name} line ${index + 1} exceeds the event size limit" }
            val event = try { JSONObject(line) } catch (exception: Exception) {
                throw IllegalArgumentException("${file.name} line ${index + 1} is invalid JSON", exception)
            }
            require(event.optString("event_schema_version") == SCHEMA_VERSION) { "${file.name} has an unsupported event schema" }
            require(event.optString("stream_id") == expectedStream) { "${file.name} has mismatched stream provenance" }
            event
        }.toList()
    }

    private fun validateObservation(event: JSONObject, streamId: String) {
        val provenance = event.optJSONObject("source_provenance") ?: error("monitor record has no provenance")
        val signal = event.optJSONObject("signal") ?: error("monitor record has no signal")
        require(event.optString("event_type") == "MONITOR_OBSERVATION" && event.optString("stream_id") == streamId) {
            "monitor stream contains an invalid event type or stream ID"
        }
        require(streamId in STREAMS && provenance.optString("authorship") == "machine" && provenance.optString("component") == STREAMS[streamId]) {
            "monitor stream contains invalid machine provenance"
        }
        requireSafeId(event.optString("observation_id"), "observation ID")
        requireSafeId(event.optString("episode_id"), "episode ID")
        require(provenance.optString("component_version").isNotBlank()) { "monitor component version is missing" }
        require(signal.optString("code").isNotBlank() && signal.optString("summary").isNotBlank()) { "monitor signal is incomplete" }
        require(signal.optString("severity") in SEVERITIES) { "monitor severity is invalid" }
        val confidence = signal.optDouble("confidence", Double.NaN)
        require(confidence.isFinite() && confidence in 0.0..1.0) { "monitor confidence is invalid" }
        if (!signal.isNull("coverage")) {
            val coverage = signal.optDouble("coverage", Double.NaN)
            require(coverage.isFinite() && coverage in 0.0..1.0) { "monitor coverage is invalid" }
        }
    }

    private fun validateDecision(event: JSONObject) {
        val reviewer = event.optJSONObject("reviewer") ?: error("human decision has no reviewer")
        val action = event.optString("action")
        require(event.optString("event_type") == "HUMAN_REVIEW_DECISION" && event.optString("stream_id") == "human-decisions") {
            "human decision event type or stream ID is invalid"
        }
        require(action in ACTIONS && reviewer.optString("kind") == "human" && reviewer.optString("id").isNotBlank()) {
            "human decision has invalid action or reviewer provenance"
        }
        require(event.optBoolean("monitoring_disclosed")) { "human decision does not disclose monitoring provenance" }
        requireSafeId(event.optString("event_id"), "event ID")
        requireSafeId(event.optString("decision_id"), "decision ID")
        requireSafeId(event.optString("observation_id"), "observation ID")
        require(event.optString("machine_finding_digest").matches(Regex("[0-9a-f]{64}"))) { "human decision finding digest is invalid" }
        require(event.has("reason") && !event.isNull("reason") && event.get("reason") is String) {
            "human decision reason must be text"
        }
        val hasHumanText = event.has("human_authored_text") && !event.isNull("human_authored_text")
        require(!hasHumanText || event.get("human_authored_text") is String) {
            "human-authored text must be text or null"
        }
        require(action != "reject" || event.getString("reason").isNotBlank()) { "rejection is missing a human-authored reason" }
        require(action != "edit" || (hasHumanText && event.getString("human_authored_text").isNotBlank())) {
            "edit is missing human-authored text"
        }
        require(action == "edit" || !hasHumanText) { "human-authored text is only valid for edit decisions" }
        require(event.getString("reason").length <= 1000 && (!hasHumanText || event.getString("human_authored_text").length <= 2000)) {
            "human decision text exceeds its size limit"
        }
    }

    private fun validateBinding(observation: JSONObject, decision: JSONObject) {
        require(decision.optString("observation_id") == observation.optString("observation_id") &&
            decision.optString("episode_id") == observation.optString("episode_id") &&
            decision.optString("source_stream_id") == observation.optString("stream_id")) {
            "human decision provenance does not match its monitor observation"
        }
        val provenance = observation.getJSONObject("source_provenance")
        val signal = observation.getJSONObject("signal")
        require(decision.optString("source_component") == provenance.optString("component") &&
            decision.optString("source_component_version") == provenance.optString("component_version") &&
            decision.optString("source_signal_code") == signal.optString("code")) {
            "human decision source fields do not match the monitor observation"
        }
        require(decision.optString("machine_finding_digest") == JsonCanonical.sha256(JsonCanonical.encode(observation))) {
            "human decision digest does not match the machine observation"
        }
    }

    private fun appendBytes(file: File, bytes: ByteArray) {
        require(bytes.size <= MAX_EVENT_BYTES || bytes.count { it == '\n'.code.toByte() } > 1) { "review event exceeds the 64 KiB limit" }
        check(file.parentFile?.exists() == true || file.parentFile?.mkdirs() == true) { "cannot create review journal directory" }
        FileOutputStream(file, true).channel.use { channel ->
            channel.lock().use {
                appendToChannel(channel, bytes, file.name)
            }
        }
    }

    private fun <T> withExclusiveAppendLock(file: File, block: (FileChannel) -> T): T {
        check(file.parentFile?.exists() == true || file.parentFile?.mkdirs() == true) { "cannot create review journal directory" }
        JOURNAL_PROCESS_LOCK.lock()
        return try {
            FileOutputStream(file, true).channel.use { channel ->
                channel.lock().use { block(channel) }
            }
        } finally {
            JOURNAL_PROCESS_LOCK.unlock()
        }
    }

    private fun appendToChannel(channel: FileChannel, bytes: ByteArray, name: String) {
        require(bytes.size <= MAX_EVENT_BYTES) { "$name event exceeds the 64 KiB limit" }
        require(channel.size() + bytes.size <= MAX_JOURNAL_BYTES) { "$name reached its 8 MiB journal limit" }
        var buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }

    private fun validatePreparedEvent(event: JSONObject) {
        require(event.optString("event_schema_version") == SCHEMA_VERSION &&
            event.optString("stream_id") == "next-episodes" &&
            event.optString("event_type") == "NEXT_EPISODE_GUIDANCE_PREPARED" &&
            event.optBoolean("monitoring_disclosed") && event.has("provenance_disclosure") &&
            !event.isNull("provenance_disclosure") && event.get("provenance_disclosure") is String &&
            event.optString("source_snapshot_sha256").matches(Regex("[0-9a-f]{64}")) &&
            event.optString("guidance_sha256").matches(Regex("[0-9a-f]{64}"))) {
            "prepared next-episode event has invalid provenance"
        }
        requireSafeId(event.optString("source_episode_id"), "source episode ID")
        requireSafeId(event.optString("next_episode_id"), "next episode ID")
        require(event.optString("source_episode_id") != event.optString("next_episode_id")) {
            "source and next episode IDs must differ"
        }
        val guidance = event.optJSONArray("guidance") ?: error("prepared next-episode event has no guidance")
        val observationIds = mutableSetOf<String>()
        val decisionIds = mutableSetOf<String>()
        for (index in 0 until guidance.length()) {
            val item = guidance.getJSONObject(index)
            validateGuidanceItem(item)
            require(item.optString("episode_id") == event.optString("source_episode_id")) {
                "prepared next-episode guidance is not bound to its source episode"
            }
            require(observationIds.add(item.optString("observation_id")) && decisionIds.add(item.optString("decision_id"))) {
                "prepared next-episode guidance contains duplicate provenance IDs"
            }
        }
        require(event.optString("guidance_sha256") == JsonCanonical.sha256(JsonCanonical.encode(JSONObject().put("guidance", guidance)))) {
            "prepared next-episode guidance digest does not match its content"
        }
    }

    private fun validateGuidanceItem(item: JSONObject) {
        val action = item.optString("human_action")
        require(action == "approve" || action == "edit") { "prepared guidance has an invalid human action" }
        val expectedType = if (action == "approve") "human_approved_machine_finding" else "human_authored_edit_of_machine_finding"
        val expectedAuthorship = if (action == "approve") "machine" else "human"
        require(item.optString("guidance_type") == expectedType &&
            item.optString("authorship") == expectedAuthorship &&
            item.optString("human_reviewer").isNotBlank() && item.optString("machine_finding").isNotBlank() &&
            item.optString("text").isNotBlank() && item.optString("source_component").isNotBlank() &&
            item.optString("source_component_version").isNotBlank() && item.optString("signal_code").isNotBlank()) {
            "prepared guidance has incomplete provenance"
        }
        requireSafeId(item.optString("observation_id"), "observation ID")
        requireSafeId(item.optString("decision_id"), "decision ID")
        requireSafeId(item.optString("episode_id"), "episode ID")
        val stream = item.optString("source_stream")
        require(stream in STREAMS && item.optString("source_component") == STREAMS[stream]) { "prepared guidance has invalid source stream" }
        require(item.optString("machine_finding_digest").matches(Regex("[0-9a-f]{64}")) && item.optString("decision_digest").matches(Regex("[0-9a-f]{64}"))) {
            "prepared guidance has invalid digests"
        }
        val observation = item.optJSONObject("machine_observation") ?: error("prepared guidance has no machine observation")
        val decision = item.optJSONObject("human_decision") ?: error("prepared guidance has no human decision")
        require(item.optString("machine_finding_digest") == JsonCanonical.sha256(JsonCanonical.encode(observation)) &&
            item.optString("decision_digest") == JsonCanonical.sha256(JsonCanonical.encode(decision)) &&
            observation.optString("observation_id") == item.optString("observation_id") &&
            observation.optString("episode_id") == item.optString("episode_id") &&
            observation.optString("stream_id") == stream &&
            observation.optJSONObject("source_provenance")?.optString("component") == item.optString("source_component") &&
            observation.optJSONObject("source_provenance")?.optString("component_version") == item.optString("source_component_version") &&
            observation.optJSONObject("signal")?.optString("code") == item.optString("signal_code") &&
            observation.optJSONObject("signal")?.optString("summary") == item.optString("machine_finding") &&
            decision.optString("decision_id") == item.optString("decision_id") && decision.optString("observation_id") == item.optString("observation_id") &&
            decision.optString("action") == action && decision.optString("machine_finding_digest") == item.optString("machine_finding_digest")) {
            "prepared guidance source or decision digest does not match nested records"
        }
        if (action == "approve") {
            require(item.optString("text") == item.optString("machine_finding") && (!decision.has("human_authored_text") || decision.isNull("human_authored_text"))) {
                "approved guidance has unexpected human-authored text"
            }
        } else {
            require(decision.has("human_authored_text") && !decision.isNull("human_authored_text") &&
                decision.get("human_authored_text") is String && decision.getString("human_authored_text") == item.optString("text")) {
                "edited guidance has invalid human-authored text"
            }
        }
    }

    private fun stripPrivateDetails(source: JSONArray): JSONArray {
        val excluded = setOf("machine_observation", "human_decision")
        val output = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.getJSONObject(index)
            val clean = JSONObject()
            val keys = item.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in excluded) clean.put(key, item.get(key))
            }
            output.put(clean)
        }
        return output
    }

    private fun copyInto(target: JSONObject, source: JSONObject) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            target.put(key, source.get(key))
        }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            require(output.size() + size <= limit) { "selected review journal exceeds 8 MiB" }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }

    private fun streamFile(stream: String) = File(streamsDir, "$stream.jsonl")

    private fun requireSafeId(value: String, label: String) {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "$label must be 1–128 safe identifier characters" }
    }

    private fun requireSafeText(value: String, label: String, limit: Int) {
        require(value.isNotBlank() && value.trim().length <= limit) { "$label must be non-empty and no more than $limit characters" }
    }

    private fun uuid() = UUID.randomUUID().toString()

    companion object {
        private const val SCHEMA_VERSION = "1.0"
        private const val MAX_EVENT_BYTES = 64 * 1024
        private const val MAX_JOURNAL_BYTES = 8 * 1024 * 1024
        private val STREAMS = mapOf(
            "mandela" to "Mandela semantic monitor",
            "matrix" to "Matrix mechanism monitor",
        )
        private val SEVERITIES = setOf("P0", "P1", "P2", "P3")
        private val ACTIONS = setOf("approve", "reject", "edit")
        private val JOURNAL_PROCESS_LOCK = ReentrantLock()
    }
}
