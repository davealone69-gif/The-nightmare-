package com.thenightmare.local

data class AppSettings(
    val baseUrl: String = "http://127.0.0.1:11434/v1",
    val model: String = "",
    val timeoutSeconds: Double = 90.0,
    val librarySnapshotId: String = "",
    val reviewerId: String = "",
)

data class SourceReference(
    val sourceId: String,
    val title: String,
    val path: String,
    val sha256: String,
) {
    val citation: String get() = "[$sourceId]"
}

data class RetrievedPassage(
    val source: SourceReference,
    val excerpt: String,
)

enum class WorkerStatus { COMPLETED, ABSTAINED }

enum class EpisodeStatus { COMPLETED, ABSTAINED }

enum class CitationStatus { NOT_REQUIRED, CITED, UNCITED, INVALID }

data class WorkerResult(
    val role: String,
    val status: WorkerStatus,
    val confidence: Double,
    val content: String = "",
    val reason: String = "",
    val sourceRefs: List<SourceReference> = emptyList(),
    val passages: List<RetrievedPassage> = emptyList(),
    val extractiveFallback: Boolean = false,
) {
    init {
        require(role.isNotBlank()) { "worker role must be a non-empty string" }
        LocalHarness.validateConfidence(confidence)
        require(sourceRefs.map { it.sourceId }.toSet().size == sourceRefs.size) {
            "worker source references must have unique ids"
        }
        require(passages.all { it.excerpt.isNotBlank() && sourceRefs.any { source -> source == it.source } }) {
            "worker passages must be non-empty and refer to a verified source reference"
        }
        if (extractiveFallback) {
            require(role in setOf("Analyst", "Formatter") && status == WorkerStatus.COMPLETED &&
                passages.size == 1 && sourceRefs == listOf(passages.single().source)) {
                "extractive fallback must be a completed, sourced Analyst or Formatter result"
            }
            val passage = passages.single()
            require(content == "The retrieved local excerpt says: “${passage.excerpt}” ${passage.source.citation}") {
                "extractive fallback must quote its verified passage and citation exactly"
            }
        }
        if (status == WorkerStatus.COMPLETED) {
            require(content.isNotBlank()) { "a completed worker result must include content" }
            require(confidence > 0.0) { "a completed worker result must have positive confidence" }
        } else {
            require(reason.isNotBlank()) { "an abstained worker result must include a reason" }
            require(sourceRefs.isEmpty() && passages.isEmpty()) { "an abstained worker result cannot include retrieved sources" }
        }
    }
}

data class EpisodeResult(
    val status: EpisodeStatus,
    val confidence: Double,
    val workers: List<WorkerResult>,
    val answer: String = "",
    val abstainingWorker: String = "",
    val reason: String = "",
    val auditStateSha256: String? = null,
    val resetAuditPassed: Boolean? = null,
    val workerOutputFingerprintsEqual: Boolean? = null,
) {
    init {
        LocalHarness.validateConfidence(confidence)
        require(workers.isNotEmpty()) { "episode result must include at least one worker result" }
        if (status == EpisodeStatus.COMPLETED) {
            require(answer.isNotBlank()) { "a completed episode must include an answer" }
            require(abstainingWorker.isEmpty() && reason.isEmpty()) { "a completed episode cannot have abstention details" }
            require(workers.last().role == "Formatter" && workers.none { it.status == WorkerStatus.ABSTAINED }) {
                "a completed episode must finish with Formatter and cannot include an abstention"
            }
        } else {
            val last = workers.last()
            require(last.status == WorkerStatus.ABSTAINED) { "an abstained episode must end with an abstaining worker" }
            require(abstainingWorker == last.role && reason == last.reason) {
                "episode abstention details must match the worker result"
            }
            require(answer.isEmpty()) { "an abstained episode cannot include a final answer" }
        }
    }

    val sourceRefs: List<SourceReference>
        get() = workers.firstOrNull { it.role == "Retriever" }?.sourceRefs.orEmpty()

    val citations: List<String>
        get() = Regex("\\[([A-Za-z0-9][A-Za-z0-9_.:-]*)]")
            .findAll(answer).map { it.groupValues[1] }.distinct().toList()

    val citationStatus: CitationStatus
        get() = when {
            sourceRefs.isEmpty() -> if (citations.isEmpty()) CitationStatus.NOT_REQUIRED else CitationStatus.INVALID
            citations.isEmpty() -> CitationStatus.UNCITED
            citations.any { id -> sourceRefs.none { it.sourceId == id } } -> CitationStatus.INVALID
            else -> CitationStatus.CITED
        }
}

data class QueueItem(
    val observation: org.json.JSONObject,
    val decision: org.json.JSONObject?,
) {
    val state: String get() = decision?.optString("action")?.uppercase() ?: "PENDING"
    val observationId: String get() = observation.optString("observation_id")
    val episodeId: String get() = observation.optString("episode_id")
    val summary: String get() = observation.optJSONObject("signal")?.optString("summary").orEmpty()
    val component: String get() = observation.optJSONObject("source_provenance")?.optString("component").orEmpty()
}

enum class LibraryFailureReason { LIBRARY_UNAVAILABLE, LIBRARY_UNPINNED, LIBRARY_INTEGRITY_FAILURE }

data class ResetAuditReport(
    val stage: String,
    val clearedChannels: List<String>,
    val remainingChannels: List<String>,
) {
    val passed: Boolean get() = remainingChannels.isEmpty()
}

data class LibraryInfo(
    val available: Boolean,
    val snapshotId: String = "",
    val sourceCount: Int = 0,
    val message: String = "No frozen library imported.",
    val failureReason: LibraryFailureReason? = null,
)
