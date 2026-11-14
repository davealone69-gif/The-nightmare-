package com.thenightmare.local

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Page { TASK, LIBRARY, REVIEW, SETTINGS }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var settingsStore: SettingsStore
    private var settings = AppSettings()
    private var currentPage = Page.TASK
    private var content: LinearLayout? = null
    private var taskInput: EditText? = null
    private var replayCheck: CheckBox? = null
    private var replaySeedInput: EditText? = null
    private var useReviewContextCheck: CheckBox? = null
    private var sourceEpisodeInput: EditText? = null
    private var nextEpisodeInput: EditText? = null
    private var settingsFields = mutableMapOf<String, EditText>()
    private var pendingMonitorStream: String? = null
    private var reviewItemsContainer: LinearLayout? = null
    private var reviewEpisodeFilterInput: EditText? = null
    private var includeReviewed = false
    private var reviewEpisodeFilter = ""
    private var taskDraft = ""
    private var sourceEpisodeDraft = ""
    private var nextEpisodeDraft = ""
    private var replayDraft = false
    private var replaySeedDraft = "0"
    private var reviewContextDraft = false
    private var isRunInProgress = false
    private var lastEpisodeResult: EpisodeResult? = null
    private var lastRunError: String? = null
    private var runningMessage = "Preparing the local episode…"
    private var runningProgressText: TextView? = null
    private var settingsLoadWarning: String? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        settings = settingsStore.load()
        settingsLoadWarning = settingsStore.loadWarning
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT < 35) window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        render()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 11, 16))
            setPadding(dp(18), dp(14), dp(18), dp(8))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val safeInsets = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(dp(18), dp(14) + safeInsets.top, dp(18), dp(8) + safeInsets.bottom)
                } else {
                    view.setPadding(dp(18), dp(14), dp(18), dp(8))
                }
                insets
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(12))
        }
        header.addView(text("THE NIGHTMARE", 22, Color.WHITE, bold = true))
        header.addView(text("LOCAL MULTI-AGENT HARNESS", 11, 0xFFB7AFC3.toInt(), bold = true).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(3), 0, dp(5))
        })
        header.addView(text(
            if (settings.model.isBlank()) "Local only · configure an Ollama model to begin" else "Local only · ${settings.model}",
            12, 0xFFB9D9C8.toInt(),
        ))
        root.addView(header)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        listOf(Page.TASK to "Run", Page.LIBRARY to "Library", Page.REVIEW to "Review", Page.SETTINGS to "Settings").forEach { (page, title) ->
            val button = smallButton(title, selected = currentPage == page)
            button.setOnClickListener { saveTaskDraft(); currentPage = page; render() }
            nav.addView(button, LinearLayout.LayoutParams(0, dp(43), 1f).apply {
                if (page != Page.SETTINGS) marginEnd = dp(6)
            })
        }
        root.addView(nav)

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(20)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        when (currentPage) {
            Page.TASK -> showTaskPage()
            Page.LIBRARY -> showLibraryPage()
            Page.REVIEW -> showReviewPage()
            Page.SETTINGS -> showSettingsPage()
        }
    }

    private fun showTaskPage() {
        addTitle("Start a local episode", "A task runs Retriever → Analyst → Formatter. Live-data requests and unsupported source requests safely abstain.")
        val taskCard = card()
        taskCard.addView(label("TASK TEXT"))
        taskInput = editBox("Write a self-contained task…", multiline = true).apply {
            minLines = 5
            maxLines = 9
            setText(taskDraft)
        }
        taskCard.addView(taskInput, matchWrap(top = 7))
        replayCheck = CheckBox(this).apply {
            text = "Run twice and compare declared app state"
            setTextColor(0xFFE1DCE8.toInt())
            textSize = 13f
            buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            isChecked = replayDraft
        }
        taskCard.addView(replayCheck, matchWrap(top = 10))
        replaySeedInput = editBox("Replay seed (integer metadata only)", multiline = false, numeric = true, signed = true).apply {
            setText(replaySeedDraft)
        }
        taskCard.addView(replaySeedInput, matchWrap(top = 5))
        taskCard.addView(text("The seed is recorded only in the declared-state audit; it is never sent to Ollama.", 12, MUTED), matchWrap(top = 3))
        useReviewContextCheck = CheckBox(this).apply {
            text = "Include fully reviewed context from a prior episode"
            setTextColor(0xFFE1DCE8.toInt())
            textSize = 13f
            buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            isChecked = reviewContextDraft
        }
        taskCard.addView(useReviewContextCheck, matchWrap(top = 3))
        sourceEpisodeInput = editBox("Source episode ID", multiline = false).apply { setText(sourceEpisodeDraft) }
        taskCard.addView(sourceEpisodeInput, matchWrap(top = 4))
        nextEpisodeInput = editBox("New episode ID", multiline = false).apply { setText(nextEpisodeDraft) }
        taskCard.addView(nextEpisodeInput, matchWrap(top = 7))
        taskCard.addView(text(
            "Only approved findings and explicit human edits are included. Rejected findings are excluded; pending monitor items block preparation.",
            12, MUTED,
        ).apply { setPadding(dp(3), dp(6), 0, 0) }, matchWrap(top = 2))
        content?.addView(taskCard, matchWrap())

        val run = primaryButton(if (isRunInProgress) "Running on this phone…" else "Run locally").apply { isEnabled = !isRunInProgress }
        run.setOnClickListener { runTask() }
        content?.addView(run, matchWrap(top = 12))
        runningProgressText = if (isRunInProgress) {
            infoCard("LOCAL EPISODE", runningMessage).getChildAt(1) as? TextView
        } else null
        if (isRunInProgress) runningProgressText?.parent?.let { parent ->
            (parent as? ViewGroup)?.let { statusPanel -> content?.addView(statusPanel, matchWrap(top = 12)) }
        }
        if (settings.model.isBlank()) {
            content?.addView(infoCard("SETUP NEEDED", "Open Settings and enter the identifier of a model already installed in Ollama. Requests go only to a loopback address on this phone."), matchWrap(top = 10))
        }
        lastEpisodeResult?.let { content?.addView(resultCard(it), matchWrap(top = 14)) }
        lastRunError?.let { content?.addView(infoCard("RUN STOPPED", it), matchWrap(top = 14)) }
    }

    private fun saveTaskDraft() {
        taskDraft = taskInput?.text?.toString().orEmpty()
        sourceEpisodeDraft = sourceEpisodeInput?.text?.toString().orEmpty()
        nextEpisodeDraft = nextEpisodeInput?.text?.toString().orEmpty()
        replayDraft = replayCheck?.isChecked == true
        replaySeedDraft = replaySeedInput?.text?.toString().orEmpty()
        reviewContextDraft = useReviewContextCheck?.isChecked == true
    }

    private fun runTask() {
        saveTaskDraft()
        val task = taskDraft.trim()
        val modelSettings = settings
        if (task.isBlank()) { toast("Enter a task first."); return }
        val seed = if (replayDraft) replaySeedDraft.trim().toLongOrNull() else 0L
        if (seed == null) { toast("Replay seed must be a whole 64-bit integer."); return }
        isRunInProgress = true
        lastEpisodeResult = null
        lastRunError = null
        runningMessage = "Preparing the local episode…"
        render()
        executor.execute {
            try {
                var effectiveTask = task
                if (reviewContextDraft) {
                    require(sourceEpisodeDraft.isNotBlank() && nextEpisodeDraft.isNotBlank()) { "enter both source and new episode IDs" }
                    val store = ReviewStore(this)
                    val prepared = store.prepareNextEpisode(sourceEpisodeDraft.trim(), nextEpisodeDraft.trim())
                    effectiveTask = store.buildNextEpisodeTask(task, prepared)
                }
                val library = FrozenLibrary(File(filesDir, "library"), modelSettings.librarySnapshotId)
                val result = LocalHarness.run(effectiveTask, modelSettings, library, replayAudit = replayDraft, seed = seed) { message ->
                    mainHandler.post {
                        runningMessage = message
                        runningProgressText?.text = message
                    }
                }
                mainHandler.post {
                    lastEpisodeResult = result
                    lastRunError = null
                    isRunInProgress = false
                    if (currentPage == Page.TASK) render()
                }
            } catch (exception: Exception) {
                mainHandler.post {
                    lastEpisodeResult = null
                    lastRunError = exception.message ?: "The local episode could not complete."
                    isRunInProgress = false
                    if (currentPage == Page.TASK) render()
                }
            }
        }
    }

    private fun resultCard(result: EpisodeResult): LinearLayout {
        val root = card()
        val statusColor = if (result.status == EpisodeStatus.COMPLETED) 0xFF9EE3B3.toInt() else 0xFFFFD28B.toInt()
        root.addView(text(if (result.status == EpisodeStatus.COMPLETED) "EPISODE COMPLETED" else "EPISODE ABSTAINED", 12, statusColor, bold = true))
        root.addView(text("Confidence: ${"%.2f".format(result.confidence)} · uncalibrated estimate", 13, MUTED).apply { setPadding(0, dp(4), 0, dp(8)) })
        result.workers.forEach { worker ->
            root.addView(text("${worker.role}: ${worker.status}${if (worker.reason.isNotBlank()) " · ${worker.reason}" else ""}", 12, 0xFFD5CEDD.toInt()).apply { setPadding(0, dp(2), 0, dp(2)) })
        }
        if (result.status == EpisodeStatus.COMPLETED) {
            val citation = result.citationStatus
            root.addView(text("Citation status: $citation", 12, when (citation) {
                CitationStatus.CITED -> 0xFF9EE3B3.toInt()
                CitationStatus.UNCITED, CitationStatus.INVALID -> 0xFFFFB4A8.toInt()
                CitationStatus.NOT_REQUIRED -> MUTED
            }).apply { setPadding(0, dp(8), 0, dp(4)) })
            when (citation) {
                CitationStatus.UNCITED -> root.addView(text(
                    "WARNING: ANSWER_NOT_CITED — the answer contains no citations to the retrieved local sources.",
                    12, 0xFFFFB4A8.toInt(), bold = true,
                ))
                CitationStatus.INVALID -> root.addView(text(
                    "WARNING: INVALID_CITATION — the answer cites an ID absent from the verified source list.",
                    12, 0xFFFFB4A8.toInt(), bold = true,
                ))
                else -> Unit
            }
            if (result.workers.lastOrNull()?.extractiveFallback == true) {
                root.addView(text("Answer mode: extractive fallback (verified local excerpt)", 12, MUTED))
            }
            root.addView(text("ANSWER", 11, 0xFFBBA9E3.toInt(), bold = true).apply { setPadding(0, dp(10), 0, dp(3)) })
            root.addView(text(result.answer, 15, 0xFFF2EEF7.toInt()))
            result.sourceRefs.forEach { source ->
                root.addView(text("${source.citation} ${source.title}\n${source.path}\nsha256:${source.sha256}", 12, MUTED).apply { setPadding(0, dp(8), 0, 0) })
            }
        } else {
            root.addView(text("Reason: ${result.reason}", 14, 0xFFFFD28B.toInt()).apply { setPadding(0, dp(8), 0, 0) })
        }
        if (result.resetAuditPassed == true) {
            root.addView(text("Declared app-state reset audit: PASS · model-server and OS state are not inspected", 12, 0xFF9EE3B3.toInt()).apply { setPadding(0, dp(10), 0, 0) })
            result.auditStateSha256?.let { root.addView(text("State fingerprint: ${it.take(24)}…", 11, MUTED)) }
        }
        result.workerOutputFingerprintsEqual?.let { equal ->
            root.addView(text("Replay worker outputs: ${if (equal) "match" else "differ (informational)"}", 12, MUTED))
        }
        return root
    }

    private fun showLibraryPage() {
        addTitle("Frozen local library", "Imported sources are copied into this app's private storage. The Termux library is never moved or edited.")
        val snapshot = FrozenLibrary(File(filesDir, "library"), settings.librarySnapshotId).info()
        content?.addView(infoCard(if (snapshot.available) "VERIFIED SNAPSHOT" else "NO VERIFIED SNAPSHOT", snapshot.message), matchWrap())
        if (snapshot.available) {
            content?.addView(text("${snapshot.sourceCount} sources · pinned as ${snapshot.snapshotId}", 13, 0xFFB9D9C8.toInt()).apply { setPadding(dp(4), dp(9), 0, dp(12)) }, matchWrap())
        }
        content?.addView(text("The importer checks manifest structure, source hashes, snapshot identity and size limits before replacing an existing Android copy.", 13, MUTED), matchWrap(top = 10))
        val import = primaryButton(if (snapshot.available) "Replace imported Android library" else "Import frozen library folder")
        import.setOnClickListener { launchLibraryPicker() }
        content?.addView(import, matchWrap(top = 12))
        if (!snapshot.available && !File(filesDir, "library").exists()) {
            val demo = smallButton("Create app-private demo library")
            demo.setOnClickListener { confirmDemoLibraryCreation() }
            content?.addView(demo, matchWrap(top = 8))
        }
        content?.addView(infoCard("LIBRARY REQUIREMENTS", "Set the expected snapshot ID in Settings first. It must match the pin in the existing Termux config.json. The selected folder must contain manifest.json and only its declared .md/.txt source files."), matchWrap(top = 12))
    }

    private fun confirmDemoLibraryCreation() {
        AlertDialog.Builder(this)
            .setTitle("Create a local demo library?")
            .setMessage("This creates the same small sample text used by the Termux demo setup, but only inside this app's private storage. It refuses to overwrite any existing Android library and does not touch Termux files. The sample text is for retrieval testing, not horticultural advice.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create demo") { _, _ ->
                executor.execute {
                    try {
                        val result = DemoLibrarySeeder.createDemoLibrary(File(filesDir, "library"))
                        require(result.available && result.snapshotId.isNotBlank()) { "demo snapshot did not verify" }
                        val updated = settings.copy(librarySnapshotId = result.snapshotId)
                        settingsStore.save(updated)
                        mainHandler.post {
                            settings = updated
                            toast("App-private demo library created and pinned: ${result.snapshotId}")
                            if (currentPage == Page.LIBRARY) render()
                        }
                    } catch (exception: Exception) {
                        mainHandler.post {
                            toast("Demo setup stopped: ${exception.message ?: "the library could not be created"}")
                            if (currentPage == Page.LIBRARY) render()
                        }
                    }
                }
            }
            .show()
    }

    private fun launchLibraryPicker() {
        if (!settings.librarySnapshotId.matches(Regex("snapshot-[0-9a-f]{16}"))) {
            toast("Set the expected snapshot ID in Settings before importing.")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        startActivityForResult(intent, REQUEST_LIBRARY)
    }

    private fun importLibrary(uri: Uri, replace: Boolean) {
        val pin = settings.librarySnapshotId
        executor.execute {
            try {
                val result = FrozenLibrary.importFromTree(this, uri, pin, replace)
                mainHandler.post { toast(result.message); if (currentPage == Page.LIBRARY) render() }
            } catch (exception: Exception) {
                mainHandler.post { toast("Import stopped: ${exception.message ?: "invalid library"}"); if (currentPage == Page.LIBRARY) render() }
            }
        }
    }

    private fun showReviewPage() {
        addTitle("Human review queue", "Observations are machine-attributed records; the app does not run a detector or authenticate the source. Decisions are separately recorded as human choices.")
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val filter = smallButton(if (includeReviewed) "Showing all" else "Pending only", selected = true)
        filter.setOnClickListener { includeReviewed = !includeReviewed; render() }
        controls.addView(filter, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(7) })
        val import = smallButton("Import review JSONL")
        import.setOnClickListener { chooseMonitorStream() }
        controls.addView(import, LinearLayout.LayoutParams(0, dp(42), 1f))
        content?.addView(controls, matchWrap())
        val record = smallButton("Record supplied monitor output")
        record.setOnClickListener { chooseMonitorStreamForRecord() }
        content?.addView(record, matchWrap(top = 8))
        val episodeFilter = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        reviewEpisodeFilterInput = editBox("Episode ID filter (blank = all)", multiline = false).apply { setText(reviewEpisodeFilter) }
        episodeFilter.addView(reviewEpisodeFilterInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(7) })
        val applyFilter = smallButton("Apply filter")
        applyFilter.setOnClickListener {
            reviewEpisodeFilter = reviewEpisodeFilterInput?.text?.toString()?.trim().orEmpty()
            reloadReviewQueue()
        }
        episodeFilter.addView(applyFilter, LinearLayout.LayoutParams(dp(112), dp(48)))
        content?.addView(episodeFilter, matchWrap(top = 8))
        val prepare = primaryButton("Prepare next-episode guidance")
        prepare.setOnClickListener { showPrepareNextEpisodeDialog() }
        content?.addView(prepare, matchWrap(top = 8))
        content?.addView(infoCard("LOCAL PROVENANCE", "This form only records an operator-supplied claim from an external monitor; it does not detect or verify signals. Machine attribution is not authenticated. The reviewer ID is also an operator-supplied label, not account authentication."), matchWrap(top = 10))
        reviewItemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content?.addView(reviewItemsContainer, matchWrap(top = 10))
        reloadReviewQueue()
    }

    private fun reloadReviewQueue() {
        val target = reviewItemsContainer ?: return
        target.removeAllViews()
        executor.execute {
            try {
                val items = ReviewStore(this).listQueue(includeReviewed, reviewEpisodeFilter.takeIf { it.isNotBlank() })
                mainHandler.post {
                    if (target.parent == null) return@post
                    if (items.isEmpty()) {
                        target.addView(infoCard("QUEUE EMPTY", "No matching monitor observations are stored in this app. The Android app does not generate detector findings."), matchWrap())
                    } else {
                        items.forEach { item -> target.addView(reviewItemCard(item), matchWrap(bottom = 8)) }
                    }
                }
            } catch (exception: Exception) {
                mainHandler.post {
                    if (target.parent != null) target.addView(infoCard("QUEUE ERROR", exception.message ?: "Could not validate review records."), matchWrap())
                }
            }
        }
    }

    private fun reviewItemCard(item: QueueItem): LinearLayout {
        val panel = card()
        panel.addView(text("${item.component} · ${item.state}", 12, if (item.decision == null) 0xFFFFD28B.toInt() else 0xFF9EE3B3.toInt(), bold = true))
        panel.addView(text("Episode ${item.episodeId} · ${item.observationId}", 11, MUTED).apply { setPadding(0, dp(4), 0, dp(6)) })
        panel.addView(text(item.summary, 14, 0xFFF1EDF6.toInt()))
        val signal = item.observation.optJSONObject("signal")
        panel.addView(text("${signal?.optString("code")} · ${signal?.optString("severity")} · confidence ${signal?.optString("confidence")}", 12, MUTED).apply { setPadding(0, dp(5), 0, dp(3)) })
        val provenance = item.observation.optJSONObject("source_provenance")
        panel.addView(text("Machine-attributed · ${provenance?.optString("component_version")}", 12, MUTED))
        val details = smallButton("Inspect full provenance")
        details.setOnClickListener { showReviewDetails(item) }
        panel.addView(details, matchWrap(top = 7))
        if (item.decision == null) {
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            listOf("Approve", "Reject", "Edit").forEachIndexed { index, name ->
                val button = smallButton(name)
                button.setOnClickListener { showDecisionDialog(item, listOf("approve", "reject", "edit")[index]) }
                actions.addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index < 2) marginEnd = dp(5) })
            }
            panel.addView(actions, matchWrap())
        } else {
            panel.addView(text("Human decision by ${item.decision.optJSONObject("reviewer")?.optString("id")}: ${item.state}", 12, MUTED).apply { setPadding(0, dp(6), 0, 0) })
            val rationale = item.decision.optString("reason")
            val editText = item.decision.optString("human_authored_text")
            if (rationale.isNotBlank()) panel.addView(text("Reason: $rationale", 12, MUTED))
            if (editText.isNotBlank() && editText != "null") panel.addView(text("Human edit: $editText", 12, MUTED))
        }
        return panel
    }

    private fun showReviewDetails(item: QueueItem) {
        val details = buildString {
            append("MACHINE OBSERVATION\n")
            append(item.observation.toString(2))
            append("\n\nHUMAN DECISION\n")
            append(item.decision?.toString(2) ?: "No decision recorded.")
        }
        val scroll = ScrollView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(text(details, 12, 0xFFE7E2ED.toInt()))
        }
        AlertDialog.Builder(this).setTitle("Full provenance · ${item.observationId}")
            .setView(scroll).setPositiveButton("Close", null).show()
    }

    private fun showPrepareNextEpisodeDialog() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        val source = editBox("Source episode ID", multiline = false)
        val next = editBox("New episode ID", multiline = false)
        fields.addView(source, matchWrap(bottom = 8))
        fields.addView(next, matchWrap())
        val dialog = AlertDialog.Builder(this).setTitle("Prepare reviewed context")
            .setMessage("Pending findings block preparation. The prepared event is append-only and can be inspected before it is used by a task.")
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Prepare and inspect", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sourceId = source.text.toString().trim()
                val nextId = next.text.toString().trim()
                if (sourceId.isBlank() || nextId.isBlank()) {
                    toast("Enter both episode IDs.")
                    return@setOnClickListener
                }
                executor.execute {
                    try {
                        val prepared = ReviewStore(this).prepareNextEpisode(sourceId, nextId)
                        mainHandler.post { dialog.dismiss(); showPreparedNextEpisode(prepared) }
                    } catch (exception: Exception) {
                        mainHandler.post { toast("Preparation stopped: ${exception.message}") }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showPreparedNextEpisode(event: org.json.JSONObject) {
        val scroll = ScrollView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(text(event.toString(2), 12, 0xFFE7E2ED.toInt()))
        }
        AlertDialog.Builder(this).setTitle("Prepared guidance · ${event.optString("next_episode_id")}")
            .setView(scroll).setPositiveButton("Close", null).show()
    }

    private fun showDecisionDialog(item: QueueItem, action: String) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), 0) }
        val reviewer = editBox("Reviewer ID (label only)", false).apply { setText(settings.reviewerId) }
        fields.addView(reviewer, matchWrap(bottom = 8))
        val note = when (action) {
            "reject" -> editBox("Human-authored rejection reason", true)
            "edit" -> editBox("Human-authored replacement guidance", true)
            else -> null
        }
        note?.let { fields.addView(it, matchWrap()) }
        val editRationale = if (action == "edit") editBox("Optional human rationale", true) else null
        editRationale?.let { fields.addView(it, matchWrap(top = 8)) }
        val title = when (action) { "approve" -> "Approve machine finding"; "reject" -> "Reject machine finding"; else -> "Edit as a human" }
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setMessage("${item.component} · ${item.observationId}\n\n${item.summary}")
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Record decision", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val reviewerId = reviewer.text.toString().trim()
                    require(reviewerId.isNotBlank()) { "Enter the human reviewer ID label in Settings or here." }
                    if (settings.reviewerId != reviewerId) {
                        settings = settings.copy(reviewerId = reviewerId)
                        settingsStore.save(settings)
                    }
                    val text = note?.text?.toString()?.trim().orEmpty()
                    val rationale = editRationale?.text?.toString()?.trim().orEmpty()
                    executor.execute {
                        try {
                            ReviewStore(this).decide(item.observationId, action, reviewerId,
                                reason = if (action == "reject") text else rationale,
                                editText = if (action == "edit") text else "")
                            mainHandler.post { toast("Human decision appended."); dialog.dismiss(); if (currentPage == Page.REVIEW) render() }
                        } catch (exception: Exception) {
                            mainHandler.post { toast("Decision not recorded: ${exception.message}") }
                        }
                    }
                } catch (exception: Exception) { toast(exception.message ?: "Decision is invalid.") }
            }
        }
        dialog.show()
    }

    private fun chooseMonitorStream() {
        AlertDialog.Builder(this).setTitle("Choose a review journal")
            .setItems(arrayOf("Mandela semantic monitor", "Matrix mechanism monitor", "Existing human decisions (import monitors first)")) { _, which ->
                pendingMonitorStream = when (which) {
                    0 -> "mandela"
                    1 -> "matrix"
                    else -> "human-decisions"
                }
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                startActivityForResult(intent, REQUEST_REVIEW_IMPORT)
            }.show()
    }

    private fun chooseMonitorStreamForRecord() {
        AlertDialog.Builder(this).setTitle("Choose the external monitor")
            .setMessage("The app does not run a detector. This records a signal supplied by a separate monitor; its machine attribution is a claim and is not authenticated.")
            .setItems(arrayOf("Mandela semantic monitor", "Matrix mechanism monitor")) { _, which ->
                showMonitorObservationDialog(if (which == 0) "mandela" else "matrix")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMonitorObservationDialog(streamId: String) {
        val component = if (streamId == "mandela") "Mandela semantic monitor" else "Matrix mechanism monitor"
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val episode = editBox("Episode ID", false)
        val code = editBox("Signal code", false)
        val severity = editBox("Severity: P0, P1, P2, or P3", false).apply { setText("P2") }
        val confidence = editBox("Confidence from 0 to 1", false, numeric = true, decimal = true)
        val coverage = editBox("Optional coverage from 0 to 1", false, numeric = true, decimal = true)
        val summary = editBox("External monitor summary", true)
        val version = editBox("Monitor component version", false)
        val evidence = editBox("Evidence references, one per line (optional)", true)
        listOf(episode, code, severity, confidence, coverage, summary, version, evidence).forEachIndexed { index, input ->
            fields.addView(input, matchWrap(top = if (index == 0) 0 else 6))
        }
        val scroll = ScrollView(this).apply { addView(fields) }
        val dialog = AlertDialog.Builder(this).setTitle("Record supplied $component output")
            .setMessage("This form does not detect a signal or verify its source. It records an operator-supplied, machine-attributed observation for separate human review.")
            .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Append observation", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val confidenceValue = confidence.text.toString().trim().toDoubleOrNull()
                val coverageValue = coverage.text.toString().trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                if (confidenceValue == null || (coverage.text.isNotBlank() && coverageValue == null)) {
                    toast("Confidence and coverage must be numbers from 0 to 1.")
                    return@setOnClickListener
                }
                val evidenceRefs = evidence.text.toString().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                executor.execute {
                    try {
                        ReviewStore(this).recordObservation(
                            streamId = streamId,
                            episodeId = episode.text.toString().trim(),
                            signalCode = code.text.toString().trim(),
                            severity = severity.text.toString().trim(),
                            confidence = confidenceValue,
                            componentVersion = version.text.toString().trim(),
                            summary = summary.text.toString().trim(),
                            evidenceRefs = evidenceRefs,
                            coverage = coverageValue,
                        )
                        mainHandler.post {
                            dialog.dismiss()
                            toast("Machine-attributed observation appended; source was not authenticated.")
                            if (currentPage == Page.REVIEW) render()
                        }
                    } catch (exception: Exception) {
                        mainHandler.post {
                            button.isEnabled = true
                            toast("Observation not recorded: ${exception.message ?: "invalid monitor output"}")
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun importMonitor(uri: Uri, stream: String) {
        executor.execute {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("could not open selected monitor file")
                val store = ReviewStore(this)
                val count = if (stream == "human-decisions") store.importHumanDecisionJournal(input)
                    else store.importMonitorJournal(stream, input)
                val label = if (stream == "human-decisions") "human decision" else "$stream monitor"
                mainHandler.post { toast("Imported $count $label record(s)."); if (currentPage == Page.REVIEW) render() }
            } catch (exception: Exception) {
                mainHandler.post { toast("Review import stopped: ${exception.message}"); if (currentPage == Page.REVIEW) render() }
            }
        }
    }

    @Deprecated("Uses the platform activity-result API for broad API compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQUEST_LIBRARY -> {
                if (File(filesDir, "library").exists()) {
                    AlertDialog.Builder(this).setTitle("Replace Android library copy?")
                        .setMessage("This replaces only the imported copy inside THE NIGHTMARE. The Termux library is not touched.")
                        .setNegativeButton("Keep current", null)
                        .setPositiveButton("Replace") { _, _ -> importLibrary(uri, true) }
                        .show()
                } else importLibrary(uri, false)
            }
            REQUEST_REVIEW_IMPORT -> pendingMonitorStream?.let { importMonitor(uri, it) }
            REQUEST_CONFIG_IMPORT -> importTermuxConfig(uri)
        }
    }

    private fun showSettingsPage() {
        addTitle("Local settings", "The app stores these settings on this device. Endpoints are restricted to loopback; there is no cloud fallback.")
        settingsFields = mutableMapOf()
        val panel = card()
        field(panel, "baseUrl", "LOCAL MODEL URL", settings.baseUrl, "http://127.0.0.1:11434/v1")
        field(panel, "model", "OLLAMA MODEL ID", settings.model, "e.g. llama3.2:1b")
        field(panel, "timeout", "REQUEST TIMEOUT · SECONDS", settings.timeoutSeconds.toString(), "positive finite seconds; fractions allowed", numeric = true, decimal = true)
        field(panel, "pin", "FROZEN LIBRARY SNAPSHOT PIN", settings.librarySnapshotId, "snapshot-0123456789abcdef")
        field(panel, "reviewer", "HUMAN REVIEWER ID LABEL", settings.reviewerId, "Name or ID; not authentication")
        content?.addView(panel, matchWrap())
        settingsLoadWarning?.let { content?.addView(infoCard("SAVED SETTINGS REPAIRED", it), matchWrap(top = 10)) }
        val importConfig = smallButton("Import Termux config.json")
        importConfig.setOnClickListener { launchTermuxConfigPicker() }
        content?.addView(importConfig, matchWrap(top = 10))
        content?.addView(infoCard("SAFE SETTINGS IMPORT", "This fills the form from a user-selected JSON file. It never reads or changes the Termux data directory or library files, and values are not saved until you tap Save settings. The Android reviewer label is preserved."), matchWrap(top = 8))
        val save = primaryButton("Save settings")
        save.setOnClickListener { saveSettings() }
        content?.addView(save, matchWrap(top = 10))
        val test = smallButton("Test local server connection")
        test.setOnClickListener { testLocalConnection(test) }
        content?.addView(test, matchWrap(top = 8))
        content?.addView(infoCard("NETWORK BOUNDARY", "Only HTTP on localhost or a loopback IP is accepted. Requests bypass proxies, refuse redirects, and are capped at 1 MiB. The app does not request broad storage access."), matchWrap(top = 12))
    }

    private fun launchTermuxConfigPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_CONFIG_IMPORT)
    }

    private fun importTermuxConfig(uri: Uri) {
        executor.execute {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("could not open selected config file")
                val configText = input.use { readUtf8Bounded(it, MAX_CONFIG_BYTES) }
                val imported = SettingsStore.parseTermuxConfig(configText)
                mainHandler.post {
                    val dataNote = if (imported.dataDirectoryIgnored) {
                        "The config contains data_dir, but the app will ignore it and will not open that path."
                    } else {
                        "Android will continue using app-private storage; it will not access Termux files."
                    }
                    val message = "Copy these values into the Android settings form?\n\nURL: ${imported.baseUrl}\nModel: ${imported.model.ifBlank { "(not set)" }}\nTimeout: ${imported.timeoutSeconds} seconds\nLibrary pin: ${imported.librarySnapshotId.ifBlank { "(not set)" }}\n\n$dataNote\n\nReview the values and tap Save settings to apply them."
                    AlertDialog.Builder(this).setTitle("Import Termux settings")
                        .setMessage(message).setNegativeButton("Cancel", null)
                        .setPositiveButton("Fill settings form") { _, _ ->
                            settingsFields["baseUrl"]?.setText(imported.baseUrl)
                            settingsFields["model"]?.setText(imported.model)
                            settingsFields["timeout"]?.setText(imported.timeoutSeconds.toString())
                            settingsFields["pin"]?.setText(imported.librarySnapshotId)
                            toast("Settings form filled. Review and tap Save settings; Termux data was not changed.")
                        }.show()
                }
            } catch (exception: Exception) {
                mainHandler.post { toast("Config import stopped: ${exception.message ?: "invalid config file"}") }
            }
        }
    }

    private fun readUtf8Bounded(input: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val amount = input.read(buffer)
            if (amount < 0) break
            require(output.size() + amount <= maxBytes) { "selected config exceeds the $maxBytes byte import limit" }
            output.write(buffer, 0, amount)
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private fun field(parent: LinearLayout, key: String, title: String, value: String, hint: String, numeric: Boolean = false, decimal: Boolean = false) {
        parent.addView(label(title), matchWrap(top = if (parent.childCount == 0) 0 else 12))
        val input = editBox(hint, multiline = false, numeric = numeric, decimal = decimal).apply { setText(value) }
        settingsFields[key] = input
        parent.addView(input, matchWrap(top = 5))
    }

    private fun saveSettings(rerender: Boolean = true): Boolean = try {
        val base = LocalHarness.validateBaseUrl(settingsFields["baseUrl"]?.text?.toString().orEmpty())
        val model = settingsFields["model"]?.text?.toString()?.trim().orEmpty()
        val timeout = settingsFields["timeout"]?.text?.toString()?.trim()?.toDoubleOrNull()
            ?: throw IllegalArgumentException("timeout must be a finite positive number")
        LocalHarness.validateTimeout(timeout)
        val pin = settingsFields["pin"]?.text?.toString()?.trim().orEmpty()
        require(pin.isEmpty() || pin.matches(Regex("snapshot-[0-9a-f]{16}"))) {
            "snapshot pin must look like snapshot- followed by 16 lowercase hex characters"
        }
        val reviewer = settingsFields["reviewer"]?.text?.toString()?.trim().orEmpty()
        require(reviewer.length <= 128) { "reviewer label is too long" }
        settings = AppSettings(base, model, timeout, pin, reviewer)
        settingsStore.save(settings)
        settingsLoadWarning = null
        toast("Settings saved on this device.")
        if (rerender) render()
        true
    } catch (exception: Exception) {
        toast(exception.message ?: "Settings are invalid.")
        false
    }

    private fun testLocalConnection(button: Button) {
        if (!saveSettings(rerender = false)) return
        button.isEnabled = false
        button.text = "Checking loopback…"
        executor.execute {
            try {
                val message = LocalHarness.testConnection(settings)
                mainHandler.post { toast(message) }
            } catch (exception: Exception) {
                mainHandler.post { toast("Local connection failed: ${exception.message}") }
            } finally {
                mainHandler.post { button.isEnabled = true; button.text = "Test local server connection" }
            }
        }
    }

    private fun addTitle(title: String, subtitle: String) {
        content?.addView(text(title, 20, Color.WHITE, true), matchWrap(bottom = 5))
        content?.addView(text(subtitle, 13, MUTED).apply { setPadding(0, 0, 0, dp(14)) }, matchWrap())
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(15))
        background = rounded(0xFF191720.toInt(), 16)
        elevation = dp(1).toFloat()
    }

    private fun infoCard(title: String, message: String): LinearLayout = card().apply {
        addView(text(title, 11, 0xFFBBA9E3.toInt(), true))
        addView(text(message, 13, 0xFFD6D0DE.toInt()).apply { setPadding(0, dp(5), 0, 0) })
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
        setTextIsSelectable(true)
    }

    private fun label(value: String) = text(value, 10, 0xFFBBA9E3.toInt(), bold = true).apply { letterSpacing = 0.08f }

    private fun editBox(hint: String, multiline: Boolean, numeric: Boolean = false, decimal: Boolean = false, signed: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(0xFF827D89.toInt())
        setTextColor(0xFFF0EDF5.toInt())
        textSize = 15f
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(0xFF24212B.toInt(), 10, 0xFF3B3545.toInt())
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or
                (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) gravity = Gravity.TOP or Gravity.START
        minHeight = if (multiline) dp(130) else dp(48)
        if (multiline) setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun primaryButton(caption: String): Button = button(caption, ACCENT, 0xFF17141C.toInt(), bold = true)
    private fun smallButton(caption: String, selected: Boolean = false): Button = button(
        caption,
        if (selected) ACCENT else 0xFF27242E.toInt(),
        if (selected) 0xFF17141C.toInt() else 0xFFE4DFEB.toInt(),
        bold = selected,
    )

    private fun button(caption: String, bg: Int, fg: Int, bold: Boolean): Button = Button(this).apply {
        text = caption
        isAllCaps = false
        textSize = 13f
        setTextColor(fg)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = rounded(bg, 12)
        minHeight = dp(43)
        setPadding(dp(8), dp(5), dp(8), dp(5))
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { if (top > 0) topMargin = dp(top); if (bottom > 0) bottomMargin = dp(bottom) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_LIBRARY = 4101
        private const val REQUEST_REVIEW_IMPORT = 4102
        private const val REQUEST_CONFIG_IMPORT = 4103
        private const val MAX_CONFIG_BYTES = 1024 * 1024
        private const val ACCENT = 0xFFC6B2FF.toInt()
        private const val MUTED = 0xFFAAA3B3.toInt()
    }
}
