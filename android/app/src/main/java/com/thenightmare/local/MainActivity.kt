package com.thenightmare.local

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
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
import android.view.View
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Page { TASK, TRAINING, LIBRARY, REVIEW, SETTINGS }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val preparationLock = Any()
    private var pendingPreparation: FrozenLibrary.Preparation? = null
    private var activityDestroyed = false
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
    private var advancedExpanded = false
    private var setupStep = 0
    private var connectionStatus = "Not tested in this session."
    private var connectionStatusView: TextView? = null
    private var settingsLoadWarning: String? = null
    private var libraryInfoCacheKey: String? = null
    private var libraryInfoCache: LibraryInfo? = null
    private var libraryInfoCheckKey: String? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        settings = settingsStore.load()
        settingsLoadWarning = settingsStore.loadWarning
        scheduleAbandonedStagingCleanup()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT < 35) window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        render()
        if (savedInstanceState == null && !getPreferences(MODE_PRIVATE).getBoolean("setup_done", false)) {
            mainHandler.post { if (!isFinishing && !isDestroyed) showSetupStep(0) }
        }
    }

    override fun onDestroy() {
        val orphaned = synchronized(preparationLock) {
            activityDestroyed = true
            pendingPreparation.also { pendingPreparation = null }
        }
        orphaned?.let { preparation ->
            executor.execute { runCatching { FrozenLibrary.discardPreparation(this, preparation) } }
        }
        // Let in-flight library transactions and queued cleanup finish; do not interrupt a rename.
        executor.shutdown()
        super.onDestroy()
    }

    private fun postUi(action: () -> Unit) {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) action()
        }
    }

    private fun retainPreparation(preparation: FrozenLibrary.Preparation): Boolean = synchronized(preparationLock) {
        if (activityDestroyed || pendingPreparation != null) false else {
            pendingPreparation = preparation
            true
        }
    }

    private fun isPreparationPending(preparation: FrozenLibrary.Preparation): Boolean =
        synchronized(preparationLock) { pendingPreparation == preparation }

    private fun takePendingPreparation(preparation: FrozenLibrary.Preparation): Boolean = synchronized(preparationLock) {
        if (pendingPreparation != preparation) false else {
            pendingPreparation = null
            true
        }
    }

    private fun discardPendingPreparation(preparation: FrozenLibrary.Preparation) {
        if (takePendingPreparation(preparation)) {
            runCatching { FrozenLibrary.discardPreparation(this, preparation) }
        }
    }

    private fun postPreparationUi(preparation: FrozenLibrary.Preparation, action: () -> Unit) {
        val accepted = mainHandler.post {
            if (isFinishing || isDestroyed || !isPreparationPending(preparation)) {
                discardPendingPreparation(preparation)
            } else action()
        }
        if (!accepted) discardPendingPreparation(preparation)
    }

    private fun scheduleAbandonedStagingCleanup() {
        val schedule = synchronized(ABANDONED_STAGING_LOCK) {
            if (abandonedStagingCleanupScheduled) false else {
                abandonedStagingCleanupScheduled = true
                true
            }
        }
        if (schedule) executor.execute {
            try {
                FrozenLibrary.cleanupAbandonedPreparations(this)
            } catch (exception: Exception) {
                postUi { toast("Temporary library staging cleanup could not finish: ${exception.message ?: "storage is unavailable"}") }
            }
        }
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
        val helpButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val guide = smallButton("How to use")
        guide.setOnClickListener { showGettingStartedGuide() }
        helpButtons.addView(guide, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        val setup = smallButton("Setup assistant")
        setup.setOnClickListener { showSetupStep(setupStep) }
        helpButtons.addView(setup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(helpButtons, matchWrap(top = 6))
        root.addView(header)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        listOf(Page.TASK to "Run", Page.TRAINING to "Train", Page.LIBRARY to "Library", Page.REVIEW to "Review", Page.SETTINGS to "Settings").forEach { (page, title) ->
            val button = smallButton(title, selected = currentPage == page).apply {
                minWidth = 0
                textSize = 11f
                setPadding(dp(2), dp(5), dp(2), dp(5))
            }
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
            Page.TRAINING -> showTrainingPage()
            Page.LIBRARY -> showLibraryPage()
            Page.REVIEW -> showReviewPage()
            Page.SETTINGS -> showSettingsPage()
        }
    }

    private fun showSetupStep(step: Int) {
        setupStep = step
        val titles = listOf("Start the local server", "Set up your model", "Add sources (optional)", "Run your first task")
        val instructions = listOf(
            "In Termux, start Ollama on this phone and install a model. Return here when it is running. The app cannot start Ollama for you.",
            "Open Settings and enter the exact name of your installed model. Keep the default URL unless you changed the server address. Save the form and test the connection. The test checks the endpoint, not whether the model is installed. Return to this assistant to continue.",
            "For source-backed questions, open Library and create the demo library or import a trusted on-device folder. Review and confirm the installation. You can skip this step for tasks that do not need sources.",
            "Open Run, type a task and tap Run locally. For the demo question, first install the demo library. Read the result and verify any cited sources yourself."
        )
        val pages = listOf(Page.TASK, Page.SETTINGS, Page.LIBRARY, Page.TASK)
        AlertDialog.Builder(this)
            .setTitle("Setup ${step + 1} of ${titles.size}: ${titles[step]}")
            .setMessage(instructions[step] + "\n\nYour progress is not automatically verified. Save any settings before leaving that screen.")
            .setNegativeButton(if (step == 0) "Skip assistant" else "Back") { _, _ ->
                if (step == 0) getPreferences(MODE_PRIVATE).edit().putBoolean("setup_done", true).apply()
                else showSetupStep(step - 1)
            }
            .setNeutralButton("Close") { _, _ -> }
            .setPositiveButton(if (step == titles.lastIndex) "Finish" else "Next") { _, _ ->
                if (step == titles.lastIndex) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("setup_done", true).apply()
                } else {
                    val next = step + 1
                    val proceed = {
                        if (currentPage == Page.TASK) saveTaskDraft()
                        currentPage = pages[next]
                        render()
                        showSetupStep(next)
                    }
                    if (currentPage == Page.SETTINGS && pages[next] != Page.SETTINGS) {
                        AlertDialog.Builder(this).setTitle("Leave Settings?")
                            .setMessage("Unsaved changes in the form will be lost. Save settings before continuing if you want to keep them.")
                            .setNegativeButton("Stay", null)
                            .setPositiveButton("Continue") { _, _ -> proceed() }.show()
                    } else proceed()
                }
            }.show()
    }

    private fun showGettingStartedGuide() {
        AlertDialog.Builder(this)
            .setTitle("Your step-by-step guide")
            .setMessage(
                "1. Start Ollama in Termux on this phone and make sure a model is installed. " +
                    "In Settings, enter that model's exact name. Keep the default local URL unless you changed the server address. " +
                    "Tap Save settings, then Test local server connection. The test also saves the form.\n\n" +
                    "2. Optional: open Library and create the demo library, or select a trusted local folder of .md or .txt files. " +
                    "Review the preview and confirm installation. A library is needed for source-backed questions, not every task.\n\n" +
                    "3. Open Run, write your task, and tap Run locally. The example button fills in a sample; it does not run it. " +
                    "Wait for the result. If the app abstains, read the reason and try a supported request.\n\n" +
                    "4. Check the answer and any sources yourself. A citation ID does not prove that an answer is correct. " +
                    "Current or live information is not available.\n\n" +
                    "5. Review is optional. It is for importing or recording external monitor observations and making human decisions; " +
                    "the app does not run a monitor itself."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showTaskPage() {
        addTitle("Start a local episode", "A task runs Retriever → Analyst → Formatter. Live-data requests and unsupported source requests safely abstain.")
        content?.addView(infoCard("GET STARTED", if (settings.model.isBlank()) {
            "First, start Ollama on this phone and add your installed model name in Settings. Tap How to use above for all steps."
        } else {
            "Next, write a task below. For questions about local documents, first add a library on the Library tab. Tap How to use for help."
        }), matchWrap(bottom = 10))
        val taskCard = card()
        taskCard.addView(label("TASK TEXT"))
        taskInput = editBox("Write a self-contained task…", multiline = true).apply {
            minLines = 5
            maxLines = 9
            setText(taskDraft)
        }
        taskCard.addView(taskInput, matchWrap(top = 7))
        val example = smallButton("Fill in a demo library question")
        example.setOnClickListener {
            val sample = "According to the local guide, what light does a tree fern need? Cite the source."
            if (taskInput?.text?.isNotBlank() == true) {
                AlertDialog.Builder(this).setTitle("Replace your task?")
                    .setMessage("This replaces the text in the task box with the demo question. It does not run the task.")
                    .setNegativeButton("Keep my task", null)
                    .setPositiveButton("Use example") { _, _ -> taskInput?.setText(sample) }.show()
            } else {
                taskInput?.setText(sample)
            }
        }
        taskCard.addView(example, matchWrap(top = 7))
        taskCard.addView(text("For this example, first create the demo library on the Library tab. You still need a running local model.", 12, MUTED), matchWrap(top = 4))
        val toggleAdvanced = smallButton(if (advancedExpanded) "Hide advanced options" else "Show advanced options")
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        }
        toggleAdvanced.setOnClickListener {
            advancedExpanded = !advancedExpanded
            advanced.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            toggleAdvanced.text = if (advancedExpanded) "Hide advanced options" else "Show advanced options"
        }
        taskCard.addView(toggleAdvanced, matchWrap(top = 8))
        taskCard.addView(text("Hidden options remain active if you selected them.", 12, MUTED), matchWrap(top = 3))
        replayCheck = CheckBox(this).apply {
            text = "Run twice and compare declared app state"
            setTextColor(0xFFE1DCE8.toInt())
            textSize = 13f
            buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            isChecked = replayDraft
        }
        advanced.addView(replayCheck, matchWrap(top = 10))
        replaySeedInput = editBox("Replay seed (integer metadata only)", multiline = false, numeric = true, signed = true).apply {
            setText(replaySeedDraft)
        }
        advanced.addView(replaySeedInput, matchWrap(top = 5))
        advanced.addView(text("The seed is recorded only in the declared-state audit; it is never sent to Ollama.", 12, MUTED), matchWrap(top = 3))
        useReviewContextCheck = CheckBox(this).apply {
            text = "Include fully reviewed context from a prior episode"
            setTextColor(0xFFE1DCE8.toInt())
            textSize = 13f
            buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            isChecked = reviewContextDraft
        }
        advanced.addView(useReviewContextCheck, matchWrap(top = 3))
        sourceEpisodeInput = editBox("Source episode ID", multiline = false).apply { setText(sourceEpisodeDraft) }
        advanced.addView(sourceEpisodeInput, matchWrap(top = 4))
        nextEpisodeInput = editBox("New episode ID", multiline = false).apply { setText(nextEpisodeDraft) }
        advanced.addView(nextEpisodeInput, matchWrap(top = 7))
        advanced.addView(text(
            "Only approved findings and explicit human edits are included. Rejected findings are excluded; pending monitor items block preparation.",
            12, MUTED,
        ).apply { setPadding(dp(3), dp(6), 0, 0) }, matchWrap(top = 2))
        taskCard.addView(advanced, matchWrap())
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
        lastRunError?.let { content?.addView(infoCard("RUN STOPPED", "$it\n\nCheck your model name and that Ollama is running, then test the connection in Settings. If the message concerns a library or review record, check the relevant tab before retrying."), matchWrap(top = 14)) }
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
                    postUi {
                        runningMessage = message
                        runningProgressText?.text = message
                    }
                }
                postUi {
                    lastEpisodeResult = result
                    lastRunError = null
                    isRunInProgress = false
                    if (currentPage == Page.TASK) { saveTaskDraft(); render() }
                }
            } catch (exception: Exception) {
                postUi {
                    lastEpisodeResult = null
                    lastRunError = exception.message ?: "The local episode could not complete."
                    isRunInProgress = false
                    if (currentPage == Page.TASK) { saveTaskDraft(); render() }
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
            root.addView(text("Check the answer against its sources. A citation ID alone does not prove a claim is correct; missing or invalid citations need particular care.", 12, MUTED))
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
            val advice = when (result.reason) {
                "LIBRARY_UNAVAILABLE" -> "Live information is unavailable. For a source-backed question, add and verify a library, then try again."
                "LIBRARY_UNPINNED", "LIBRARY_INTEGRITY_FAILURE" -> "Open Library to check the snapshot and pin. Do not repin unexpected changes without reviewing them."
                "NO_RELEVANT_SOURCE" -> "No matching passage was found. Try different wording or add relevant documents to a trusted library."
                "LOW_CONFIDENCE", "INVALID_RESPONSE" -> "The local model could not provide an acceptable response. Try a simpler task or check your model setup."
                else -> "Read the reason above and try a more specific task or provide relevant local sources."
            }
            root.addView(text(advice, 13, MUTED).apply { setPadding(0, dp(8), 0, 0) })
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

    private fun showTrainingPage() {
        addTitle("3D expertise practice", "Build skills for rendering, shaders and photorealistic avatars. THE NIGHTMARE and 3D Mega Hub remain separate apps.")
        content?.addView(infoCard("WHAT THIS DOES", "Practice with your existing local model and save your own instruction and reference-answer examples. This does not fine-tune or change model weights. A compatible on-phone trainer and model have not been selected or verified."), matchWrap(bottom = 10))
        content?.addView(infoCard("YOUR SPECIALISTS", "Create an empty profile, then give it a name, optional model and your own instructions. Profiles can share one existing model or name different locally installed models. This is prompt-based practice, not weight training; it does not create a blank model or an adapter."), matchWrap(bottom = 8))
        val addSpecialist = primaryButton("Create blank specialist")
        addSpecialist.setOnClickListener { showSpecialistEditor(null) }
        content?.addView(addSpecialist, matchWrap(bottom = 8))
        val specialistList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content?.addView(specialistList, matchWrap(bottom = 10))
        executor.execute {
            try {
                val profiles = SpecialistStore(this).list()
                postUi {
                    if (currentPage == Page.TRAINING && specialistList.parent == content) {
                        if (profiles.isEmpty()) specialistList.addView(infoCard("NO SPECIALISTS YET", "Create one for rendering, shaders, avatars or another area. Nothing is pre-trained by the profile."))
                        profiles.forEach { specialistList.addView(specialistCard(it), matchWrap(bottom = 8)) }
                    }
                }
            } catch (exception: Exception) {
                postUi { if (currentPage == Page.TRAINING && specialistList.parent == content) specialistList.addView(infoCard("SPECIALISTS COULD NOT BE READ", exception.message ?: "Storage is unavailable.")) }
            }
        }
        content?.addView(text("GUIDED PRACTICE", 12, 0xFFBBA9E3.toInt(), bold = true), matchWrap(bottom = 6))
        TrainingCurriculum.lessons.forEach { (title, prompt) ->
            val panel = infoCard(title, prompt)
            val practice = smallButton("Practice this topic")
            practice.setOnClickListener { useTrainingPrompt(prompt) }
            panel.addView(practice, matchWrap(top = 7))
            content?.addView(panel, matchWrap(bottom = 8))
        }
        content?.addView(infoCard("YOUR EXAMPLES", "Save an instruction, an ideal reference answer and a source/permission note. Only add material you have permission to use. Review or edit each example yourself; approval is your label, not an authenticated rights check. Editing returns it to draft. Examples stay in this app's private storage and are not automatically sent to the model or other apps."), matchWrap(top = 8))
        val add = primaryButton("Add an example")
        add.setOnClickListener { showTrainingExampleEditor(null) }
        content?.addView(add, matchWrap(top = 8))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content?.addView(list, matchWrap(top = 10))
        executor.execute {
            try {
                val examples = TrainingExampleStore(this).list()
                postUi {
                    if (currentPage != Page.TRAINING || list.parent == null || list.parent != content) return@postUi
                    if (examples.isEmpty()) list.addView(infoCard("NO EXAMPLES YET", "Add an instruction and reference answer to begin building your own practice set."))
                    examples.forEach { example -> list.addView(trainingExampleCard(example), matchWrap(bottom = 8)) }
                }
            } catch (exception: Exception) {
                postUi {
                    if (currentPage == Page.TRAINING && list.parent == content) {
                        list.addView(infoCard("EXAMPLES COULD NOT BE READ", "${exception.message ?: "Storage is unavailable."} Existing data was not replaced."))
                    }
                }
            }
        }
    }

    private fun specialistCard(profile: SpecialistProfile): LinearLayout {
        val panel = infoCard(profile.name, "Model: ${profile.model.ifBlank { "Use Settings model" }}\nInstructions: ${profile.instructions.ifBlank { "None yet" }}")
        val test = smallButton("Practice with specialist")
        test.setOnClickListener { showSpecialistPractice(profile) }
        panel.addView(test, matchWrap(top = 7))
        val suite = smallButton("Run fixed 10-prompt contract suite")
        suite.setOnClickListener { confirmSpecialistSuite(profile, suite) }
        panel.addView(suite, matchWrap(top = 5))
        val edit = smallButton("Edit name, model or instructions")
        edit.setOnClickListener { showSpecialistEditor(profile) }
        panel.addView(edit, matchWrap(top = 5))
        val delete = smallButton("Delete specialist")
        delete.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Delete ${profile.name}?")
                .setMessage("This deletes only the profile, not the local model or your saved examples.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    executor.execute {
                        try {
                            SpecialistStore(this).delete(profile.id)
                            postUi { toast("Specialist deleted."); if (currentPage == Page.TRAINING) render() }
                        } catch (exception: Exception) { postUi { toast("Could not delete: ${exception.message}") } }
                    }
                }.show()
        }
        panel.addView(delete, matchWrap(top = 5))
        return panel
    }

    private fun confirmSpecialistSuite(profile: SpecialistProfile, button: Button) {
        val selected = profile.model.ifBlank { settings.model }
        if (selected.isBlank()) { toast("Set a local model in the specialist or Settings first."); return }
        AlertDialog.Builder(this).setTitle("Run ten fixed prompts?")
            .setMessage("This sends ten separate prompts to the configured local model ($selected), with this specialist's instructions and no saved examples. It can take ten request timeouts; the first transport failure stops the suite. Results are shown only in this session. Closing the screen does not cancel requests.\n\nThis checks structure and selected abstentions only, not answer accuracy, real 3D checks, model identity or weight hashes. A model name is not a version pin.")
            .setNegativeButton("Cancel", null).setPositiveButton("Run suite") { _, _ ->
                val settingsForRun = settings
                button.isEnabled = false
                button.text = "Running suite…"
                executor.execute {
                    val results = SpecialistFixedSuite.run { case -> LocalHarness.runSpecialist(profile, case.prompt, emptyList(), settingsForRun) }
                    val report = buildString {
                        append("Suite v${SpecialistFixedSuite.VERSION} · model ID: $selected (weights not verified)\n")
                        append("Structural/abstention checks: ${results.count { it.passed }}/${results.size}. This is not a correctness or safety score. Outputs are unverified; no 3D checks ran.\n")
                        results.forEach { item ->
                            val status = when {
                                item.error?.startsWith("NOT RUN") == true -> "NOT RUN"
                                item.passed -> "CHECK PASSED"
                                else -> "CHECK FAILED"
                            }
                            append("\n${item.case.id}: $status · ${if (item.case.abstainRequired) "abstention required" else "answer or abstention accepted"}\n")
                            val result = item.result
                            append(item.error ?: if (result == null) "No result" else "${result.outcome}: ${if (result.outcome == SpecialistOutcome.ABSTAIN) result.reason else result.answer}")
                            append("\n")
                        }
                    }
                    postUi {
                        button.isEnabled = true
                        button.text = "Run fixed 10-prompt contract suite"
                        AlertDialog.Builder(this).setTitle("Contract suite results")
                            .setMessage(report).setPositiveButton("Close", null).show()
                    }
                }
            }.show()
    }

    private fun showSpecialistEditor(existing: SpecialistProfile?) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val name = editBox("Name, for example Shader specialist", multiline = false).apply { setText(existing?.name.orEmpty()) }
        val model = editBox("Optional local model ID; blank uses Settings", multiline = false).apply { setText(existing?.model.orEmpty()) }
        val instructions = editBox("Your instructions (optional)", multiline = true).apply { setText(existing?.instructions.orEmpty()) }
        fields.addView(label("SPECIALIST NAME")); fields.addView(name, matchWrap(top = 5, bottom = 10))
        fields.addView(label("MODEL ID")); fields.addView(model, matchWrap(top = 5, bottom = 10))
        fields.addView(label("YOUR INSTRUCTIONS")); fields.addView(instructions, matchWrap(top = 5))
        val scroll = ScrollView(this).apply { addView(fields) }
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Create blank specialist" else "Edit specialist")
            .setMessage("The profile starts without instructions or its own model. A model ID selects an existing local model; it does not create or train one.")
            .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val profile = SpecialistProfile(existing?.id ?: java.util.UUID.randomUUID().toString(), name.text.toString().trim(), model.text.toString().trim(), instructions.text.toString().trim())
                try {
                    SpecialistProfiles.validate(profile)
                    val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    button.isEnabled = false
                    executor.execute {
                        try {
                            SpecialistStore(this).save(profile, existing == null)
                            postUi { dialog.dismiss(); toast("Specialist saved."); if (currentPage == Page.TRAINING) render() }
                        } catch (exception: Exception) { postUi { button.isEnabled = true; toast("Could not save: ${exception.message}") } }
                    }
                } catch (exception: Exception) { toast(exception.message ?: "Check the specialist fields.") }
            }
        }
        dialog.show()
    }

    private fun showSpecialistPractice(profile: SpecialistProfile) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val task = editBox("Ask this specialist a question", multiline = true)
        val examples = CheckBox(this).apply {
            text = "Send ALL reviewed examples (up to 8, 16 KB) to the local model as reference context"
            setTextColor(0xFFE1DCE8.toInt())
            buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        fields.addView(task, matchWrap()); fields.addView(examples, matchWrap(top = 8))
        val scroll = ScrollView(this).apply { addView(fields) }
        val dialog = AlertDialog.Builder(this).setTitle("Practice: ${profile.name}")
            .setMessage("Uses ${profile.model.ifBlank { settings.model.ifBlank { "the model in Settings" } }} via the configured loopback server. Each test is a single response, not the three-worker pipeline, a weight update, or a verified render. Instructions and checked examples are sent to that server. Closing the dialog does not cancel an already-started request.")
            .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Run locally", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requestTask = task.text.toString().trim()
                if (requestTask.isBlank() || requestTask.length > 4000) { toast("Enter a task of at most 4000 characters."); return@setOnClickListener }
                val includeExamples = examples.isChecked
                val settingsForRun = settings
                val run = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                run.isEnabled = false
                run.text = "Running…"
                executor.execute {
                    try {
                        val chosen = if (includeExamples) TrainingExampleStore(this).list().filter { it.approved } else emptyList()
                        val result = LocalHarness.runSpecialist(profile, requestTask, chosen, settingsForRun)
                        postUi {
                            if (!dialog.isShowing) return@postUi
                            dialog.dismiss()
                            val message = if (result.outcome == SpecialistOutcome.ABSTAIN) {
                                "The specialist could not reliably complete this task. Abstaining is a valid outcome.\n\nReason: ${result.reason}"
                            } else {
                                "Unverified answer — no external checks ran. Compare against your references.\n\n${result.answer}"
                            }
                            AlertDialog.Builder(this).setTitle(if (result.outcome == SpecialistOutcome.ABSTAIN) "Abstained" else "${profile.name}: unverified answer")
                                .setMessage("$message\n\nModel-reported confidence: ${result.confidence} (uncalibrated). No weights were changed.")
                                .setPositiveButton("Close", null).show()
                        }
                    } catch (exception: Exception) {
                        postUi {
                            if (dialog.isShowing) {
                                run.isEnabled = true; run.text = "Run locally"
                                AlertDialog.Builder(this).setTitle("No valid specialist result")
                                    .setMessage("${exception.message ?: "Local model unavailable or response invalid."}\n\nNo success was recorded. Check the model/server and output contract before trying again.")
                                    .setPositiveButton("Close", null).show()
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun useTrainingPrompt(prompt: String) {
        val open = {
            taskDraft = prompt
            currentPage = Page.TASK
            render()
        }
        if (taskDraft.isNotBlank()) {
            AlertDialog.Builder(this).setTitle("Replace the current task?")
                .setMessage("The practice instruction will replace your task draft. Nothing is run automatically.")
                .setNegativeButton("Keep task", null).setPositiveButton("Use instruction") { _, _ -> open() }.show()
        } else open()
    }

    private fun trainingExampleCard(example: TrainingExample): LinearLayout {
        val panel = infoCard(if (example.approved) "REVIEWED BY YOU" else "DRAFT — NOT REVIEWED", "Instruction: ${example.instruction}\n\nReference answer: ${example.reference}\n\nSource and permission note: ${example.source}")
        val practice = smallButton("Practice this instruction")
        practice.setOnClickListener { useTrainingPrompt(example.instruction) }
        panel.addView(practice, matchWrap(top = 7))
        val edit = smallButton("Edit example")
        edit.setOnClickListener { showTrainingExampleEditor(example) }
        panel.addView(edit, matchWrap(top = 5))
        val approve = smallButton(if (example.approved) "Return to draft" else "Mark as reviewed")
        approve.setOnClickListener {
            if (example.approved) trainingChange { it.approve(example.id, false) }
            else AlertDialog.Builder(this).setTitle("Mark as reviewed?")
                .setMessage("Confirm you reviewed the instruction, reference answer and source/permission note. This label does not verify rights or train a model.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Mark reviewed") { _, _ -> trainingChange { it.approve(example.id, true) } }.show()
        }
        panel.addView(approve, matchWrap(top = 5))
        val delete = smallButton("Delete example")
        delete.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Delete this example?")
                .setMessage("This removes the example from this app's private collection. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> trainingChange { it.delete(example.id) } }.show()
        }
        panel.addView(delete, matchWrap(top = 5))
        return panel
    }

    private fun showTrainingExampleEditor(existing: TrainingExample?) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val instruction = editBox("Instruction or question", multiline = true).apply { minLines = 3; setText(existing?.instruction.orEmpty()) }
        val reference = editBox("Ideal reference answer (your reviewed example)", multiline = true).apply { minLines = 3; setText(existing?.reference.orEmpty()) }
        val source = editBox("Source and permission to use", multiline = true).apply { minLines = 2; setText(existing?.source.orEmpty()) }
        fields.addView(label("INSTRUCTION")); fields.addView(instruction, matchWrap(top = 5, bottom = 10))
        fields.addView(label("REFERENCE ANSWER")); fields.addView(reference, matchWrap(top = 5, bottom = 10))
        fields.addView(label("SOURCE AND PERMISSION NOTE")); fields.addView(source, matchWrap(top = 5))
        val scroller = ScrollView(this).apply { addView(fields) }
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Add an example" else "Edit example")
            .setMessage("Use material you have permission to use. Editing returns a reviewed example to draft.")
            .setView(scroller).setNegativeButton("Cancel", null).setPositiveButton("Save draft", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val example = TrainingExample(existing?.id ?: java.util.UUID.randomUUID().toString(),
                    instruction.text.toString().trim(), reference.text.toString().trim(), source.text.toString().trim())
                try {
                    TrainingExamples.validate(example)
                    val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    saveButton.isEnabled = false
                    executor.execute {
                        try {
                            val store = TrainingExampleStore(this)
                            if (existing == null) store.create(example) else store.replace(example)
                            postUi {
                                dialog.dismiss()
                                toast("Example saved on this device.")
                                if (currentPage == Page.TRAINING) render()
                            }
                        } catch (exception: Exception) {
                            postUi {
                                saveButton.isEnabled = true
                                toast("Example not saved: ${exception.message ?: "storage error"}")
                            }
                        }
                    }
                } catch (exception: Exception) {
                    toast(exception.message ?: "Check the example fields.")
                }
            }
        }
        dialog.show()
    }

    private fun trainingChange(action: (TrainingExampleStore) -> Unit) {
        executor.execute {
            try {
                action(TrainingExampleStore(this))
                postUi { toast("Example saved on this device."); if (currentPage == Page.TRAINING) render() }
            } catch (exception: Exception) {
                postUi { toast("Example not saved: ${exception.message ?: "storage error"}") }
            }
        }
    }

    private fun showLibraryPage() {
        addTitle("Frozen local library", "Selected sources are copied, validated and frozen in this app's private storage. The Termux library is never moved or edited.")
        content?.addView(infoCard("HOW TO ADD SOURCES", "For a first test, choose Create app-private demo library below and confirm. To use your own documents, choose Freeze or import a library folder, select a trusted on-device folder, review the preview, then confirm. Return to Run to ask a question. A library is optional for tasks that do not require sources."), matchWrap(bottom = 10))
        val libraryRoot = File(filesDir, "library")
        val pinForCheck = settings.librarySnapshotId
        val key = "${libraryRoot.absolutePath}|$pinForCheck"
        val snapshot = libraryInfoCache.takeIf { libraryInfoCacheKey == key }
        if (snapshot == null) {
            content?.addView(infoCard("CHECKING SNAPSHOT", "Verifying the local read-only tree and pin…"), matchWrap())
            if (libraryInfoCheckKey != key) {
                libraryInfoCheckKey = key
                executor.execute {
                    val verified = FrozenLibrary(libraryRoot, pinForCheck).info()
                    postUi {
                        if (libraryInfoCheckKey == key) {
                            libraryInfoCheckKey = null
                            libraryInfoCacheKey = key
                            libraryInfoCache = verified
                            val currentKey = "${libraryRoot.absolutePath}|${settings.librarySnapshotId}"
                            if (!isFinishing && !isDestroyed && currentPage == Page.LIBRARY && currentKey == key) render()
                        }
                    }
                }
            }
        } else {
            content?.addView(infoCard(if (snapshot.available) "VERIFIED SNAPSHOT" else "NO VERIFIED SNAPSHOT", snapshot.message), matchWrap())
        }
        if (snapshot?.available == true) {
            content?.addView(text("${snapshot.sourceCount} sources · pinned as ${snapshot.snapshotId}", 13, 0xFFB9D9C8.toInt()).apply { setPadding(dp(4), dp(9), 0, dp(12)) }, matchWrap())
        }
        content?.addView(text("The selected folder can contain source-only .md/.txt files or an already frozen snapshot. Android validates it in private staging, then shows the exact snapshot pin before an explicit install, replace or repin confirmation.", 13, MUTED), matchWrap(top = 10))
        val import = primaryButton(if (snapshot?.available == true) "Prepare another library folder" else "Freeze or import a library folder")
        import.setOnClickListener { launchLibraryPicker() }
        content?.addView(import, matchWrap(top = 12))
        if (libraryRoot.isDirectory && snapshot?.available != true) {
            val recover = smallButton("Validate and adopt existing library pin")
            recover.setOnClickListener { confirmExistingPinRecovery() }
            content?.addView(recover, matchWrap(top = 8))
        }
        if (snapshot?.available != true && !libraryRoot.exists()) {
            val demo = smallButton("Create app-private demo library")
            demo.setOnClickListener { confirmDemoLibraryCreation() }
            content?.addView(demo, matchWrap(top = 8))
        }
        content?.addView(infoCard("PIN RECOVERY", "A pin is adopted only after the complete read-only tree, manifest, declared file set, source hashes and snapshot identity validate. A changed pin requires a separate confirmation; validation never edits the existing library."), matchWrap(top = 12))
        content?.addView(infoCard("PORTABLE PROJECT EXPORT", "Path exclusions and recognizable-secret checks are heuristic. Android SAF cannot prove that a provider did not resolve a link outside the selected folder; use only a trusted local project and review the archive before sharing."), matchWrap(top = 16))
        val exportButton = smallButton("Create verified portable export")
        exportButton.setOnClickListener { launchPortableProjectPicker() }
        content?.addView(exportButton, matchWrap(top = 8))
        val sharePrevious = smallButton("Verify a saved export and share it")
        sharePrevious.setOnClickListener { shareLatestVerifiedExport() }
        content?.addView(sharePrevious, matchWrap(top = 6))
    }

    private fun invalidateLibraryInfo() {
        libraryInfoCacheKey = null
        libraryInfoCache = null
    }

    private fun confirmDemoLibraryCreation() {
        AlertDialog.Builder(this)
            .setTitle("Create a local demo library?")
            .setMessage("This creates the same small sample text used by the Termux demo setup, but only inside this app's private storage. It refuses to overwrite any existing Android library and does not touch Termux files. The sample text is for retrieval testing, not horticultural advice.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create demo") { _, _ ->
                val expectedPin = settings.librarySnapshotId
                executor.execute {
                    try {
                        val result = DemoLibrarySeeder.createDemoLibrary(File(filesDir, "library"))
                        require(result.available && result.snapshotId.isNotBlank()) { "demo snapshot did not verify" }
                        settingsStore.updateLibrarySnapshotId(result.snapshotId, expectedPin)
                        postUi {
                            settings = settings.copy(librarySnapshotId = result.snapshotId)
                            invalidateLibraryInfo()
                            toast("App-private demo library created and pinned: ${result.snapshotId}")
                            if (currentPage == Page.LIBRARY) render()
                        }
                    } catch (exception: Exception) {
                        postUi {
                            invalidateLibraryInfo()
                            toast("Demo setup stopped: ${exception.message ?: "the library could not be created"}")
                            if (currentPage == Page.LIBRARY) render()
                        }
                    }
                }
            }
            .show()
    }

    private fun launchLibraryPicker() {
        AlertDialog.Builder(this)
            .setTitle("Choose a trusted local library folder")
            .setMessage("The app accepts only Android's built-in on-device storage provider and validates copied bytes, but SAF cannot prove physical folder containment or guarantee descriptor-anchored no-follow behavior. Continue only with a folder whose files you trust.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose local folder") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                ).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                startActivityForResult(intent, REQUEST_LIBRARY)
            }
            .show()
    }

    private fun prepareLibrary(uri: Uri) {
        if (uri.authority != PortableProjectExporter.LOCAL_STORAGE_AUTHORITY) {
            toast("For privacy, library setup reads only on-device storage folders.")
            return
        }
        executor.execute {
            try {
                val preparation = FrozenLibrary.prepareFromTree(this, uri)
                if (!retainPreparation(preparation)) {
                    runCatching { FrozenLibrary.discardPreparation(this, preparation) }
                    return@execute
                }
                postPreparationUi(preparation) { confirmPreparedLibrary(preparation) }
            } catch (exception: Exception) {
                postUi { toast("Library preparation stopped: ${exception.message ?: "the selected folder could not be verified"}") }
            }
        }
    }

    private fun confirmPreparedLibrary(preparation: FrozenLibrary.Preparation) {
        executor.execute {
            try {
                val latestSettings = settingsStore.load()
                val libraryRoot = File(filesDir, "library")
                val currentExists = Files.exists(libraryRoot.toPath(), LinkOption.NOFOLLOW_LINKS)
                val currentPin = if (currentExists && libraryRoot.isDirectory && !Files.isSymbolicLink(libraryRoot.toPath())) {
                    runCatching { FrozenLibrary(libraryRoot, "").validateForPinRecovery() }.getOrNull()
                } else null
                val repin = (latestSettings.librarySnapshotId.isNotBlank() &&
                    latestSettings.librarySnapshotId != preparation.snapshotId) ||
                    (currentPin != null && currentPin != preparation.snapshotId)
                postPreparationUi(preparation) {
                    showPreparedLibraryConfirmation(preparation, latestSettings, currentExists, currentPin, repin)
                }
            } catch (exception: Exception) {
                discardPendingPreparation(preparation)
                postUi { toast("Library confirmation stopped: ${exception.message ?: "the saved settings could not be read"}") }
            }
        }
    }

    private fun showPreparedLibraryConfirmation(
        preparation: FrozenLibrary.Preparation,
        previousSettings: AppSettings,
        currentExists: Boolean,
        currentPin: String?,
        repin: Boolean,
    ) {
        if (!isPreparationPending(preparation)) return
        val positiveLabel = when {
            currentExists && repin -> "Repin + replace Android copy"
            currentExists -> "Replace Android copy"
            repin -> "Confirm new snapshot pin"
            else -> "Install verified snapshot"
        }
        val message = buildString {
            append("Validated staging snapshot: ").append(preparation.snapshotId)
            append("\nSources: ").append(preparation.sourceCount)
            append("\nSaved pin: ").append(previousSettings.librarySnapshotId.ifBlank { "none" })
            currentPin?.let { append("\nExisting library pin: ").append(it) }
            append("\n\nThis action changes only the Android app's private library and saved pin. The prior Android library is kept as a backup until the new snapshot verifies and the new pin saves. The Termux files are not changed.")
        }
        var installStarted = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Review library snapshot")
            .setMessage(message)
            .setNegativeButton(if (currentExists) "Keep current" else "Cancel") { _, _ ->
                discardPendingPreparation(preparation)
            }
            .setPositiveButton(positiveLabel) { _, _ ->
                installStarted = true
                installPreparedLibrary(preparation, previousSettings, currentExists, repin)
            }
            .create()
        dialog.setOnCancelListener {
            if (!installStarted) discardPendingPreparation(preparation)
        }
        dialog.show()
    }

    private fun installPreparedLibrary(
        preparation: FrozenLibrary.Preparation,
        previousSettings: AppSettings,
        allowReplace: Boolean,
        allowRepin: Boolean,
    ) {
        if (!takePendingPreparation(preparation)) {
            toast("The prepared snapshot is no longer available; prepare it again.")
            return
        }
        try {
            executor.execute {
                try {
                    val result = FrozenLibrary.installPrepared(
                        this, preparation, settingsStore, previousSettings,
                        allowReplace = allowReplace, allowRepin = allowRepin,
                    )
                    postUi {
                        settings = settings.copy(librarySnapshotId = preparation.snapshotId)
                        invalidateLibraryInfo()
                        toast(result.message)
                        if (currentPage == Page.LIBRARY) render()
                    }
                } catch (exception: Exception) {
                    runCatching { FrozenLibrary.discardPreparation(this, preparation) }
                        .exceptionOrNull()?.let(exception::addSuppressed)
                    postUi {
                        invalidateLibraryInfo()
                        toast("Library install stopped: ${exception.message ?: "the snapshot could not be installed safely"}")
                        if (currentPage == Page.LIBRARY) render()
                    }
                }
            }
        } catch (exception: RuntimeException) {
            runCatching { FrozenLibrary.discardPreparation(this, preparation) }
                .exceptionOrNull()?.let(exception::addSuppressed)
            postUi { toast("Library install could not start: ${exception.message ?: "the app is closing"}") }
        }
    }

    private fun confirmExistingPinRecovery() {
        val root = File(filesDir, "library")
        executor.execute {
            try {
                val previousSettings = settingsStore.load()
                val recoveredPin = FrozenLibrary(root, "").validateForPinRecovery()
                postUi {
                    val repin = previousSettings.librarySnapshotId.isNotBlank() &&
                        previousSettings.librarySnapshotId != recoveredPin
                    val wording = if (repin) "Repin to verified snapshot" else "Adopt verified pin"
                    AlertDialog.Builder(this)
                        .setTitle("Review recovered snapshot pin")
                        .setMessage("The existing read-only library fully validates as $recoveredPin.\n\nSaved pin: ${previousSettings.librarySnapshotId.ifBlank { "none" }}\n\nNo library files will be changed.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(wording) { _, _ ->
                            executor.execute {
                                try {
                                    settingsStore.updateLibrarySnapshotId(recoveredPin, previousSettings.librarySnapshotId)
                                    postUi {
                                        settings = settings.copy(librarySnapshotId = recoveredPin)
                                        invalidateLibraryInfo()
                                        toast("Verified snapshot pin saved: $recoveredPin")
                                        if (currentPage == Page.LIBRARY) render()
                                    }
                                } catch (exception: Exception) {
                                    postUi { toast("Pin not saved: ${exception.message ?: "settings could not be committed"}") }
                                }
                            }
                        }
                        .show()
                }
            } catch (exception: Exception) {
                postUi { toast("Pin recovery stopped: ${exception.message ?: "the library did not fully validate"}") }
            }
        }
    }

    private fun launchPortableProjectPicker() {
        AlertDialog.Builder(this)
            .setTitle("Review portable export limits")
            .setMessage("The export stays on this device until you explicitly share it, but Android SAF cannot prove that a provider did not resolve a link outside the selected folder. Secret detection is heuristic, not a guarantee. Choose only a trusted local project folder and review the archive before sharing.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose local project") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                ).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                startActivityForResult(intent, REQUEST_PORTABLE_PROJECT)
            }
            .show()
    }

    private fun exportPortableProject(uri: Uri) {
        if (uri.authority != PortableProjectExporter.LOCAL_STORAGE_AUTHORITY) {
            toast("For privacy, portable export reads only on-device storage, not cloud or third-party folders.")
            return
        }
        toast("Scanning the selected project locally…")
        executor.execute {
            try {
                val snapshot = PortableProjectExporter.captureFromLocalTree(this, uri)
                val result = PortableProjectExporter.export(snapshot, File(filesDir, "portable-exports"))
                postUi { showPortableExportResult(result) }
            } catch (exception: Exception) {
                postUi {
                    toast("Portable export stopped: ${exception.message ?: "the selected project could not be verified"}")
                }
            }
        }
    }

    private fun showPortableExportResult(result: PortableProjectExporter.ExportResult) {
        AlertDialog.Builder(this)
            .setTitle("Portable export verified")
            .setMessage("${result.fileCount} files\nTree SHA-256: ${result.treeSha256}\nZIP SHA-256: ${result.archiveSha256}\n\nThe verified output pair is in app-private storage. Source exclusions and secret detection are heuristic, and Android SAF cannot prove physical containment when a provider resolves links. Review the archive before sharing. Sharing sends only the ZIP and manifest after you choose a recipient; that provider controls durability of its copy.")
            .setNegativeButton("Done", null)
            .setPositiveButton("Share ZIP + manifest") { _, _ -> sharePortableExport(result) }
            .show()
    }

    private fun shareLatestVerifiedExport() {
        toast("Verifying saved export pairs locally…")
        executor.execute {
            val result = PortableProjectExporter.findVerifiedExports(File(filesDir, "portable-exports")).firstOrNull()
            postUi {
                if (result == null) toast("No complete verified portable export is saved on this device.")
                else showPortableExportResult(result)
            }
        }
    }

    private fun sharePortableExport(result: PortableProjectExporter.ExportResult) {
        try {
            val authority = "$packageName.portable-exports"
            val zipUri = Uri.Builder().scheme("content").authority(authority)
                .appendPath(result.exportId).appendPath(PortableProjectExporter.ARCHIVE_NAME).build()
            val manifestUri = Uri.Builder().scheme("content").authority(authority)
                .appendPath(result.exportId).appendPath(PortableProjectExporter.MANIFEST_NAME).build()
            val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(zipUri, manifestUri))
                clipData = ClipData.newUri(contentResolver, "THE NIGHTMARE portable export", zipUri).apply {
                    addItem(ClipData.Item(manifestUri))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share verified portable export"))
        } catch (exception: Exception) {
            toast("Could not open the Android share sheet: ${exception.message ?: "sharing unavailable"}")
        }
    }

    private fun showReviewPage() {
        addTitle("Human review queue", "Observations are machine-attributed records; the app does not run a detector or authenticate the source. Decisions are separately recorded as human choices.")
        content?.addView(infoCard("OPTIONAL: HOW TO REVIEW", "First set a reviewer label in Settings. Import existing monitor records or record an observation supplied by an external monitor. Inspect each item, then approve, reject, or edit it. Only after review can you prepare eligible context for a later episode. An empty queue is normal if you have not added observations."), matchWrap(bottom = 10))
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
                postUi {
                    if (target.parent == null) return@postUi
                    if (items.isEmpty()) {
                        target.addView(infoCard("QUEUE EMPTY", "No matching monitor observations are stored in this app. The Android app does not generate detector findings."), matchWrap())
                    } else {
                        items.forEach { item -> target.addView(reviewItemCard(item), matchWrap(bottom = 8)) }
                    }
                }
            } catch (exception: Exception) {
                postUi {
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
                        postUi { dialog.dismiss(); showPreparedNextEpisode(prepared) }
                    } catch (exception: Exception) {
                        postUi { toast("Preparation stopped: ${exception.message}") }
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
                        val baseline = settings
                        settings = settingsStore.saveMerged(baseline.copy(reviewerId = reviewerId), baseline)
                    }
                    val text = note?.text?.toString()?.trim().orEmpty()
                    val rationale = editRationale?.text?.toString()?.trim().orEmpty()
                    executor.execute {
                        try {
                            ReviewStore(this).decide(item.observationId, action, reviewerId,
                                reason = if (action == "reject") text else rationale,
                                editText = if (action == "edit") text else "")
                            postUi { toast("Human decision appended."); dialog.dismiss(); if (currentPage == Page.REVIEW) render() }
                        } catch (exception: Exception) {
                            postUi { toast("Decision not recorded: ${exception.message}") }
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
                        postUi {
                            dialog.dismiss()
                            toast("Machine-attributed observation appended; source was not authenticated.")
                            if (currentPage == Page.REVIEW) render()
                        }
                    } catch (exception: Exception) {
                        postUi {
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
                postUi { toast("Imported $count $label record(s)."); if (currentPage == Page.REVIEW) render() }
            } catch (exception: Exception) {
                postUi { toast("Review import stopped: ${exception.message}"); if (currentPage == Page.REVIEW) render() }
            }
        }
    }

    @Deprecated("Uses the platform activity-result API for broad API compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQUEST_LIBRARY -> prepareLibrary(uri)
            REQUEST_REVIEW_IMPORT -> pendingMonitorStream?.let { importMonitor(uri, it) }
            REQUEST_CONFIG_IMPORT -> importTermuxConfig(uri)
            REQUEST_PORTABLE_PROJECT -> exportPortableProject(uri)
        }
    }

    private fun showSettingsPage() {
        addTitle("Local settings", "The app stores these settings on this device. Endpoints are restricted to loopback; there is no cloud fallback.")
        content?.addView(infoCard("SET UP YOUR MODEL", "1. Start Ollama in Termux on this phone and install a model.\n2. Enter its exact model ID below; the default URL normally needs no change.\n3. Tap Save settings, then Test local server connection. Testing also saves the form.\n\nThe library pin is filled when you install a library; the reviewer label is optional unless you record review decisions."), matchWrap(bottom = 10))
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
        connectionStatusView = text("Last connection test: $connectionStatus", 13, MUTED)
        content?.addView(connectionStatusView, matchWrap(top = 10))
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
                postUi {
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
                postUi { toast("Config import stopped: ${exception.message ?: "invalid config file"}") }
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
        val baseline = settings
        settings = settingsStore.saveMerged(AppSettings(base, model, timeout, pin, reviewer), baseline)
        if (settings.baseUrl != baseline.baseUrl || settings.model != baseline.model || settings.timeoutSeconds != baseline.timeoutSeconds) {
            connectionStatus = "Settings changed; test again."
            connectionStatusView?.text = "Last connection test: $connectionStatus"
        }
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
        val settingsForTest = settings
        button.isEnabled = false
        button.text = "Checking loopback…"
        connectionStatus = "Test in progress."
        connectionStatusView?.text = "Last connection test: $connectionStatus"
        executor.execute {
            val outcome = try {
                val message = LocalHarness.testConnection(settingsForTest)
                "$message This checks the endpoint only, not model availability. It may have changed since the test."
            } catch (exception: Exception) {
                "Failed: ${exception.message ?: "Could not connect."} Check that Ollama is running on this phone and the URL and model name in Settings are correct, then test again."
            }
            postUi {
                if (settings.baseUrl == settingsForTest.baseUrl && settings.model == settingsForTest.model &&
                    settings.timeoutSeconds == settingsForTest.timeoutSeconds) {
                    connectionStatus = outcome
                    connectionStatusView?.text = "Last connection test: $connectionStatus"
                }
                button.isEnabled = true
                button.text = "Test local server connection"
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
        private val ABANDONED_STAGING_LOCK = Any()
        private var abandonedStagingCleanupScheduled = false
        private const val REQUEST_LIBRARY = 4101
        private const val REQUEST_REVIEW_IMPORT = 4102
        private const val REQUEST_CONFIG_IMPORT = 4103
        private const val REQUEST_PORTABLE_PROJECT = 4104
        private const val MAX_CONFIG_BYTES = 1024 * 1024
        private const val ACCENT = 0xFFC6B2FF.toInt()
        private const val MUTED = 0xFFAAA3B3.toInt()
    }
}
