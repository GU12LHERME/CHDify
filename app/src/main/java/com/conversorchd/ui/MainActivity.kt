package com.conversorchd.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.conversorchd.App
import com.conversorchd.R
import com.conversorchd.core.Analyzer
import com.conversorchd.core.DiscMode
import com.conversorchd.core.Format
import com.conversorchd.core.InputFile
import com.conversorchd.core.Job
import com.conversorchd.core.JobRepository
import com.conversorchd.core.JobState
import com.conversorchd.core.Prefs
import com.conversorchd.core.SourceKind
import com.conversorchd.core.SourceResolver
import com.conversorchd.core.Storage
import com.conversorchd.service.ConversionService

class MainActivity : Activity(), JobCard.Actions {

    private lateinit var prefs: Prefs
    private val cards = LinkedHashMap<Long, JobCard>()
    private val listener: () -> Unit = { refresh() }
    private var startAfterDestination = false

    // views persistentes (recriadas apenas quando o layout muda de orientação/largura)
    private lateinit var root: FrameLayout
    private lateinit var destButton: Button
    private lateinit var threadsButton: Button
    private lateinit var verifySwitch: Switch
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var addFilesButton: Button
    private lateinit var queueTitle: TextView
    private lateinit var queueList: LinearLayout
    private lateinit var clearButton: Button
    private lateinit var emptyQueue: TextView
    private lateinit var progName: TextView
    private lateinit var progStage: TextView
    private lateinit var progBar: ProgressBar
    private lateinit var progPercent: TextView
    private lateinit var progStats: TextView
    private lateinit var progSizes: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars: Insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            } else {
                @Suppress("DEPRECATION")
                Insets.of(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        buildLayout()
        if (savedInstanceState == null) addFilesButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        JobRepository.addListener(listener)
        refresh()
    }

    override fun onStop() {
        JobRepository.removeListener(listener)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val focusedTag = currentFocus?.tag
        buildLayout()
        if (focusedTag != null) root.findViewWithTag<View>(focusedTag)?.requestFocus()
    }

    // ================================================================== layout

    private fun isWide(): Boolean {
        val c = resources.configuration
        return c.screenWidthDp >= 720 || (c.orientation == Configuration.ORIENTATION_LANDSCAPE && c.screenWidthDp >= 560)
    }

    private fun buildLayout() {
        root.removeAllViews()
        cards.clear()
        val pad = Ui.dp(this, 16)

        val header = Ui.column(this, 2).apply {
            addView(Ui.text(this@MainActivity, getString(R.string.app_name), 24f, Ui.ON_SURFACE, bold = true))
            addView(Ui.text(this@MainActivity, getString(R.string.subtitle), 13f, Ui.ON_SURFACE_VAR))
        }
        val actions = buildActionsCard()
        val progress = buildProgressCard()
        val controls = buildControls()
        val queue = buildQueue()

        if (isWide()) {
            val left = Ui.column(this, 12).apply {
                setPadding(pad, pad, pad / 2, pad)
                addView(header); addView(actions); addView(progress); addView(controls)
            }
            val right = Ui.column(this, 12).apply {
                setPadding(pad / 2, pad, pad, pad)
                addView(queue)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(scroll(left), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(scroll(right), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
            }
            root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val col = Ui.column(this, 12).apply {
                setPadding(pad, pad, pad, pad)
                addView(header); addView(actions); addView(progress); addView(controls); addView(queue)
            }
            root.addView(scroll(col), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        refresh()
    }

    private fun scroll(child: View) = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        addView(child)
    }

    private fun buildActionsCard(): View {
        val card = Ui.card(this)
        val col = Ui.column(this, 10)
        addFilesButton = Ui.button(this, getString(R.string.action_add_files), Ui.Style.TONAL) { pickFiles() }.apply { tag = "add-files" }
        val addFolder = Ui.button(this, getString(R.string.action_add_folder), Ui.Style.TONAL) { pickInputFolder() }.apply { tag = "add-folder" }
        col.addView(Ui.row(this, 10).apply {
            addView(addFilesButton, Ui.weighted())
            addView(addFolder, Ui.weighted())
        }, Ui.lp())
        destButton = Ui.button(this, "", Ui.Style.OUTLINED) { pickDestination() }.apply {
            tag = "dest"
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            Ui.singleLine(this)
        }
        col.addView(destButton, Ui.lp())
        verifySwitch = Switch(this).apply {
            tag = "verify"
            text = getString(R.string.option_verify)
            setTextColor(Ui.ON_SURFACE)
            textSize = 15f
            isChecked = prefs.verifyAfter
            minHeight = Ui.dp(this@MainActivity, 52)
            background = Ui.stateBackground(this@MainActivity, Ui.SURFACE, 0, 16, Ui.withAlpha(Ui.PRIMARY, 0.2f))
            setPadding(Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 12), 0)
            setOnCheckedChangeListener { _, checked -> prefs.verifyAfter = checked }
        }
        col.addView(verifySwitch, Ui.lp())
        threadsButton = Ui.button(this, "", Ui.Style.OUTLINED) { cycleThreads() }.apply {
            tag = "threads"
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        col.addView(threadsButton, Ui.lp())
        card.addView(col, Ui.lp())
        updateOptionLabels()
        return card
    }

    private fun buildProgressCard(): View {
        val card = Ui.card(this)
        val col = Ui.column(this, 8)
        progName = Ui.singleLine(Ui.text(this, "", 16f, Ui.ON_SURFACE, bold = true))
        progStage = Ui.text(this, "", 13f, Ui.PRIMARY)
        progBar = Ui.progressBar(this)
        progPercent = Ui.text(this, "", 32f, Ui.ON_SURFACE, bold = true)
        progStats = Ui.text(this, "", 13f, Ui.ON_SURFACE_VAR).apply { typeface = Typeface.MONOSPACE }
        progSizes = Ui.text(this, "", 13f, Ui.ON_SURFACE_VAR)
        col.addView(progName)
        col.addView(progStage)
        col.addView(progBar, Ui.lp(h = Ui.dp(this, 10)))
        col.addView(Ui.row(this, 16).apply {
            addView(progPercent)
            addView(progStats, Ui.weighted())
        }, Ui.lp())
        col.addView(progSizes)
        card.addView(col, Ui.lp())
        return card
    }

    private fun buildControls(): View {
        startButton = Ui.button(this, getString(R.string.action_start), Ui.Style.FILLED) { startConversion() }.apply { tag = "start" }
        cancelButton = Ui.button(this, getString(R.string.action_cancel), Ui.Style.OUTLINED) { confirmCancel() }.apply { tag = "cancel" }
        return Ui.row(this, 10).apply {
            addView(startButton, LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 64), 1.4f))
            addView(cancelButton, LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 64), 1f))
        }
    }

    private fun buildQueue(): View {
        val col = Ui.column(this, 10)
        queueTitle = Ui.text(this, "", 18f, Ui.ON_SURFACE, bold = true)
        clearButton = Ui.smallButton(this, getString(R.string.action_clear_finished), Ui.Style.TEXT) { clearFinished() }.apply { tag = "clear" }
        col.addView(Ui.row(this, 8).apply {
            addView(queueTitle, Ui.weighted())
            addView(clearButton)
        }, Ui.lp())
        emptyQueue = Ui.text(this, getString(R.string.queue_empty), 14f, Ui.ON_SURFACE_VAR)
        col.addView(emptyQueue)
        queueList = Ui.column(this, 10)
        col.addView(queueList, Ui.lp())
        return col
    }

    // ================================================================== estado -> tela

    private fun updateOptionLabels() {
        val tree = prefs.destinationTree
        destButton.text = if (tree != null && Storage.hasTreePermission(this, tree))
            getString(R.string.dest_label, Storage.treeDisplayName(contentResolver, tree))
        else getString(R.string.dest_choose)
        val cores = Runtime.getRuntime().availableProcessors()
        val t = prefs.threads
        threadsButton.text = if (t <= 0) getString(R.string.threads_auto, cores) else resources.getQuantityString(R.plurals.threads_n, t, t)
    }

    private fun refresh() {
        if (!::queueList.isInitialized) return
        val jobs = JobRepository.snapshot()

        // fila: adiciona/remove/atualiza cartões sem recriar os existentes
        val ids = jobs.map { it.id }.toSet()
        cards.keys.filter { it !in ids }.forEach { id -> cards.remove(id)?.let { queueList.removeView(it.view) } }
        jobs.forEachIndexed { index, job ->
            val card = cards.getOrPut(job.id) { JobCard(this, this).also { queueList.addView(it.view, index, Ui.lp()) } }
            card.bind(job)
        }
        emptyQueue.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
        val ready = jobs.count { it.state == JobState.READY }
        queueTitle.text = getString(R.string.queue_title, jobs.size)
        clearButton.visibility = if (jobs.any { it.isFinished }) View.VISIBLE else View.GONE

        // progresso
        val current = JobRepository.current()
        val processing = JobRepository.processing
        startButton.isEnabled = !processing && ready > 0
        startButton.text = if (ready > 0) getString(R.string.action_start_n, ready) else getString(R.string.action_start)
        cancelButton.isEnabled = processing
        if (current != null) {
            progName.text = current.displayName
            val modeText = getString(if (current.mode == DiscMode.CD) R.string.mode_cd else R.string.mode_dvd)
            progStage.text = getString(
                if (current.state == JobState.VERIFYING) R.string.stage_verifying else R.string.stage_compressing, modeText,
            )
            progBar.progress = (current.fraction * 1000).toInt()
            progPercent.text = Format.percent(current.fraction)
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - current.startedAt) / 1000
            val remaining = if (current.speedBps > 0) ((current.bytesTotal - current.bytesDone) / current.speedBps).toLong() else -1L
            progStats.text = getString(R.string.stats_line, Format.speed(current.speedBps), Format.duration(elapsed), Format.duration(remaining))
            val estimate = if (current.state == JobState.RUNNING && current.fraction > 0.02)
                getString(R.string.sizes_estimate, Format.bytes(current.inputBytes), Format.bytes((current.inputBytes * current.ratio).toLong()))
            else getString(R.string.sizes_input, Format.bytes(current.inputBytes))
            progSizes.text = estimate
        } else {
            val done = jobs.filter { it.state == JobState.DONE }
            progName.text = getString(if (processing) R.string.idle_starting else R.string.idle_title)
            progStage.text = if (done.isEmpty()) getString(R.string.idle_hint) else getString(R.string.idle_done, done.size)
            progBar.progress = 0
            progPercent.text = ""
            progStats.text = ""
            val inSum = done.sumOf { it.inputBytes }
            val outSum = done.sumOf { it.outputBytes }
            progSizes.text = if (done.isEmpty()) "" else getString(R.string.sizes_total, Format.bytes(inSum), Format.bytes(outSum))
        }
    }

    // ================================================================== ações

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launch(i, REQ_FILES)
    }

    private fun pickInputFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launch(i, REQ_INPUT_TREE)
    }

    private fun pickDestination() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        launch(i, REQ_DEST)
    }

    private fun launch(i: Intent, req: Int) {
        try {
            startActivityForResult(i, req)
        } catch (e: Exception) {
            toast(getString(R.string.err_no_picker))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            startAfterDestination = false
            return
        }
        when (requestCode) {
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) } }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                uris.forEach { Storage.takeReadPermission(this, it) }
                addInputs { uris.mapNotNull { Storage.describe(contentResolver, it) } }
            }
            REQ_INPUT_TREE -> {
                val tree = data.data ?: return
                Storage.takeReadPermission(this, tree)
                addInputs { Storage.listTree(contentResolver, tree) }
            }
            REQ_DEST -> {
                val tree = data.data ?: return
                Storage.takeReadWritePermission(this, tree)
                prefs.destinationTree = tree
                updateOptionLabels()
                if (startAfterDestination) {
                    startAfterDestination = false
                    startConversion()
                }
            }
        }
    }

    /** Agrupa e analisa em segundo plano; a fila é atualizada conforme cada item fica pronto. */
    private fun addInputs(load: () -> List<InputFile>) {
        val appCtx = applicationContext
        App.background.execute {
            val files = load()
            val resolved = SourceResolver.resolve(appCtx, files) { JobRepository.nextId() }
            JobRepository.add(resolved.jobs)
            runOnUiThread {
                when {
                    resolved.jobs.isEmpty() -> toast(getString(R.string.toast_nothing_added))
                    resolved.ignored.isNotEmpty() -> toast(resources.getQuantityString(R.plurals.toast_ignored, resolved.ignored.size, resolved.ignored.size))
                }
            }
            for (job in resolved.jobs) {
                val a = Analyzer.analyze(appCtx, job)
                job.analysis = a
                job.mode = a.recommended
                job.state = if (a.error == null && a.recommended != null) JobState.READY else JobState.BLOCKED
                job.log("Análise: ${a.summary}")
                a.warnings.forEach { job.log("Aviso: $it") }
                a.error?.let { job.log("Erro: $it") }
                JobRepository.changed()
            }
        }
    }

    private fun cycleThreads() {
        val cores = Runtime.getRuntime().availableProcessors()
        val options = listOf(0) + (1..cores)
        val idx = options.indexOf(prefs.threads).let { if (it < 0) 0 else it }
        prefs.threads = options[(idx + 1) % options.size]
        updateOptionLabels()
    }

    private fun startConversion() {
        if (JobRepository.processing) return
        if (!JobRepository.hasReady()) {
            toast(getString(R.string.toast_nothing_ready))
            return
        }
        val tree = prefs.destinationTree
        if (tree == null || !Storage.hasTreePermission(this, tree)) {
            toast(getString(R.string.toast_choose_dest))
            startAfterDestination = true
            pickDestination()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        try {
            ConversionService.start(this)
        } catch (e: Exception) {
            toast(getString(R.string.err_service, e.message ?: ""))
        }
    }

    private fun confirmCancel() {
        if (!JobRepository.processing) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.cancel_title)
            .setMessage(R.string.cancel_message)
            .setPositiveButton(R.string.cancel_confirm) { _, _ -> ConversionService.cancel(this) }
            .setNegativeButton(R.string.cancel_keep, null)
            .show()
    }

    private fun clearFinished() {
        JobRepository.clearFinished().forEach { releaseInputs(it) }
    }

    private fun releaseInputs(job: Job) {
        Storage.releasePermission(this, job.main.uri)
        job.tracks.forEach { t -> t.file?.let { Storage.releasePermission(this, it.uri) } }
    }

    // JobCard.Actions
    override fun setMode(job: Job, mode: DiscMode) {
        if (job.analysis?.allowedModes?.contains(mode) == true && !job.isActive) {
            job.mode = mode
            job.log("Modo alterado para ${mode.name}")
            JobRepository.changed()
        }
    }

    override fun remove(job: Job) {
        JobRepository.remove(job.id)?.let { releaseInputs(it) }
    }

    override fun retry(job: Job) = JobRepository.retry(job.id)

    override fun showLog(job: Job) {
        val tv = Ui.text(this, job.logText().ifEmpty { getString(R.string.log_empty) }, 12f, Ui.ON_SURFACE).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            val p = Ui.dp(this@MainActivity, 16)
            setPadding(p, p, p, p)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(job.displayName)
            .setView(sv)
            .setPositiveButton(R.string.action_copy) { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("log", job.logText()))
                toast(getString(R.string.toast_copied))
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    // ================================================================== controles físicos

    /**
     * Gamepads (XInput e similares): A confirma, B volta, Start inicia,
     * X adiciona arquivos, Y cancela (com confirmação), Select abre o log do item atual.
     * O D-pad e o analógico navegam pelo foco padrão do Android.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> 0
        }
        if (mapped != 0) {
            return super.dispatchKeyEvent(
                KeyEvent(event.downTime, event.eventTime, event.action, mapped, event.repeatCount, event.metaState,
                    event.deviceId, event.scanCode, event.flags, event.source),
            )
        }
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_START -> { startConversion(); return true }
                KeyEvent.KEYCODE_BUTTON_X -> { pickFiles(); return true }
                KeyEvent.KEYCODE_BUTTON_Y -> { confirmCancel(); return true }
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    (JobRepository.current() ?: JobRepository.snapshot().lastOrNull())?.let { showLog(it) }
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_SELECT -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_FILES = 10
        private const val REQ_INPUT_TREE = 11
        private const val REQ_DEST = 12
        private const val REQ_NOTIF = 13
    }
}
