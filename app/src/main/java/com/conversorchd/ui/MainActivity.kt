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
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.conversorchd.App
import com.conversorchd.R
import com.conversorchd.core.Analyzer
import com.conversorchd.core.DeleteState
import com.conversorchd.core.Deletion
import com.conversorchd.core.DiscMode
import com.conversorchd.core.Format
import com.conversorchd.core.InputFile
import com.conversorchd.core.Job
import com.conversorchd.core.JobRepository
import com.conversorchd.core.JobState
import com.conversorchd.core.Operation
import com.conversorchd.core.Prefs
import com.conversorchd.core.SourceKind
import com.conversorchd.core.SourceResolver
import com.conversorchd.core.Storage
import com.conversorchd.service.ConversionService

/**
 * Tela única com cinco seções: Conversão, Fila, Ferramentas, Histórico e Ajustes.
 * Cada seção é uma classe ([Tab]) que constrói suas views e as atualiza no lugar.
 */
class MainActivity : Activity(), JobCard.Actions {

    interface Tab {
        fun build(): View
        fun refresh() {}
    }

    lateinit var prefs: Prefs
        private set
    private val listener: () -> Unit = { refresh() }
    private var startAfterDestination = false
    private var current = 0
    private lateinit var root: FrameLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var content: ScrollView
    private lateinit var tabs: List<Tab>
    private lateinit var tabTitles: List<Int>

    /** Resultado de um seletor de CHD para a aba Ferramentas. */
    var onChdPicked: ((InputFile) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // sai do tema de abertura (splash) para o tema normal antes de montar a interface
        setTheme(R.style.Theme_CHDify)
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        current = savedInstanceState?.getInt("aba") ?: 0
        tabs = listOf(ConvertTab(this), QueueTab(this), ToolsTab(this), HistoryTab(this), SettingsTab(this))
        tabTitles = listOf(R.string.tab_convert, R.string.tab_queue, R.string.tab_tools, R.string.tab_history, R.string.tab_settings)

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
        buildShell()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("aba", current)
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
        buildShell()
        if (focusedTag != null) root.findViewWithTag<View>(focusedTag)?.requestFocus()
    }

    /** Larguras de tablet/console na horizontal usam duas colunas onde fizer sentido. */
    fun isWide(): Boolean {
        val c = resources.configuration
        return c.screenWidthDp >= 720 || (c.orientation == Configuration.ORIENTATION_LANDSCAPE && c.screenWidthDp >= 560)
    }

    // ================================================================== estrutura

    private fun buildShell() {
        root.removeAllViews()
        val pad = Ui.dp(this, 12)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = Ui.row(this, 10).apply {
            setPadding(pad, pad, pad, Ui.dp(this@MainActivity, 4))
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_notification)
                contentDescription = null
            }, LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 26), Ui.dp(this@MainActivity, 26)))
            addView(Ui.text(this@MainActivity, getString(R.string.app_name), 20f, Ui.FG, bold = true))
            addView(Ui.text(this@MainActivity, getString(R.string.subtitle), 12f, Ui.SUBTLE).apply { Ui.singleLine(this, middle = false) }, Ui.weighted())
        }
        column.addView(header, Ui.lp())

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(pad - Ui.dp(this@MainActivity, 4), 0, pad, 0)
            addView(tabRow)
        }
        column.addView(tabScroll, Ui.lp())
        column.addView(Ui.divider(this))

        content = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        showTab(current)
    }

    private fun buildTabRow() {
        tabRow.removeAllViews()
        tabTitles.forEachIndexed { i, res ->
            val label = if (i == 1) {
                val n = JobRepository.snapshot().size
                if (n > 0) "${getString(res)} ($n)" else getString(res)
            } else getString(res)
            val b = Ui.smallButton(this, label, Ui.Style.TEXT) { showTab(i) }.apply {
                tag = "tab-$i"
                setTextColor(if (i == current) Ui.FG else Ui.SUBTLE)
                typeface = Typeface.create("sans-serif-medium", if (i == current) Typeface.BOLD else Typeface.NORMAL)
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(b)
                // sublinhado branco na aba atual (como no launcher do Crash)
                addView(View(this@MainActivity).apply { setBackgroundColor(if (i == current) Ui.FG else 0) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 2)))
            }
            tabRow.addView(cell)
        }
    }

    fun showTab(i: Int) {
        current = i.coerceIn(0, tabs.size - 1)
        buildTabRow()
        content.removeAllViews()
        content.addView(tabs[current].build())
        content.scrollTo(0, 0)
        tabs[current].refresh()
        updateBackHandling()
    }

    private var lastQueueCount = -1

    fun refresh() {
        if (!::tabs.isInitialized || !::content.isInitialized) return
        val n = JobRepository.snapshot().size
        if (n != lastQueueCount) {
            lastQueueCount = n
            val focused = tabRow.findFocus()?.tag
            buildTabRow()
            if (focused != null) tabRow.findViewWithTag<View>(focused)?.requestFocus()
        }
        tabs[current].refresh()
    }

    // ================================================================== seletores (SAF)

    fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            // escrita é pedida para permitir "Excluir original" quando o usuário ligar a opção
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launch(i, REQ_FILES)
    }

    fun pickInputFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launch(i, REQ_INPUT_TREE)
    }

    fun pickDestination() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launch(i, REQ_DEST)
    }

    fun pickChd(onPicked: (InputFile) -> Unit) {
        onChdPicked = onPicked
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launch(i, REQ_CHD)
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
                uris.forEach { Storage.takeInputPermission(this, it) }
                addInputs(null) { uris.mapNotNull { Storage.describe(contentResolver, it) } }
            }
            REQ_INPUT_TREE -> {
                val tree = data.data ?: return
                Storage.takeInputPermission(this, tree)
                addInputs(tree) { Storage.listTree(contentResolver, tree) }
            }
            REQ_DEST -> {
                val tree = data.data ?: return
                Storage.takeReadWritePermission(this, tree)
                prefs.destinationTree = tree
                refresh()
                if (startAfterDestination) {
                    startAfterDestination = false
                    startQueue()
                }
            }
            REQ_CHD -> {
                val uri = data.data ?: return
                Storage.takeInputPermission(this, uri)
                val f = Storage.describe(contentResolver, uri) ?: return
                if (Storage.extension(f.name) != "chd") {
                    toast(getString(R.string.toast_not_chd))
                    return
                }
                onChdPicked?.invoke(f)
            }
        }
    }

    /** Agrupa e analisa em segundo plano; a fila é atualizada conforme cada item fica pronto. */
    private fun addInputs(tree: Uri?, load: () -> List<InputFile>) {
        val appCtx = applicationContext
        App.background.execute {
            val files = load()
            val resolved = SourceResolver.resolve(appCtx, files, tree, JobRepository::isDuplicate) { JobRepository.nextId() }
            JobRepository.add(resolved.jobs, resolved.groups)
            runOnUiThread {
                val msgs = ArrayList<String>()
                if (resolved.jobs.isEmpty() && resolved.duplicates.isEmpty()) msgs.add(getString(R.string.toast_nothing_added))
                if (resolved.duplicates.isNotEmpty()) msgs.add(resources.getQuantityString(R.plurals.toast_duplicates, resolved.duplicates.size, resolved.duplicates.size))
                if (resolved.ignored.isNotEmpty()) msgs.add(resources.getQuantityString(R.plurals.toast_ignored, resolved.ignored.size, resolved.ignored.size))
                if (resolved.groups.isNotEmpty()) msgs.add(resources.getQuantityString(R.plurals.toast_m3u, resolved.groups.size, resolved.groups.size))
                if (msgs.isNotEmpty()) toast(msgs.joinToString("\n"))
            }
            for (job in resolved.jobs) {
                Analyzer.analyzeAndApply(appCtx, job)
                JobRepository.persist()
            }
        }
    }

    /** Coloca na fila uma operação de ferramenta (verificar, recompactar, extrair) sobre um CHD. */
    fun enqueueTool(file: InputFile, op: Operation) {
        if (JobRepository.isDuplicate(file, op)) {
            toast(resources.getQuantityString(R.plurals.toast_duplicates, 1, 1))
            return
        }
        val job = Job(JobRepository.nextId(), SourceKind.CHD, file, emptyList(), op)
        JobRepository.add(listOf(job))
        val appCtx = applicationContext
        App.background.execute {
            Analyzer.analyzeAndApply(appCtx, job)
            JobRepository.persist()
        }
        toast(getString(R.string.toast_tool_queued, Labels.operation(this, op)))
        showTab(0)
    }

    // ================================================================== fila

    fun startQueue() {
        if (JobRepository.processing) return
        if (!JobRepository.hasReady()) {
            toast(getString(R.string.toast_nothing_ready))
            return
        }
        val needsDest = JobRepository.snapshot().any { it.state == JobState.READY && it.operation != Operation.VERIFY }
        val tree = prefs.destinationTree
        if (needsDest && (tree == null || !Storage.hasTreePermission(this, tree))) {
            toast(getString(R.string.toast_choose_dest))
            startAfterDestination = true
            pickDestination()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        if (needsDest && tree != null) {
            // estimativa conservadora: a saída pode ocupar até o tamanho da entrada
            val estimate = JobRepository.snapshot().filter { it.state == JobState.READY && it.operation != Operation.VERIFY }
                .sumOf { it.analysis?.outputEstimate?.takeIf { e -> e > 0 } ?: it.inputBytes }
            App.background.execute {
                val free = Storage.freeSpaceOf(contentResolver, Storage.root(tree))
                runOnUiThread {
                    if (free in 0 until estimate) {
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(R.string.space_title)
                            .setMessage(getString(R.string.space_message, Format.bytes(free), Format.bytes(estimate)))
                            .setPositiveButton(R.string.space_continue) { _, _ -> launchService() }
                            .setNegativeButton(R.string.cancel_keep, null)
                            .show()
                    } else {
                        launchService()
                    }
                }
            }
        } else {
            launchService()
        }
    }

    private fun launchService() {
        try {
            ConversionService.start(this)
        } catch (e: Exception) {
            toast(getString(R.string.err_service, e.message ?: ""))
        }
    }

    fun confirmCancelQueue() {
        if (!JobRepository.processing) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.cancel_title)
            .setMessage(R.string.cancel_message)
            .setPositiveButton(R.string.cancel_confirm) { _, _ -> ConversionService.cancel(this) }
            .setNegativeButton(R.string.cancel_keep, null)
            .show()
    }

    fun cancelCurrentItem() {
        if (JobRepository.processing) ConversionService.cancelCurrent()
    }

    fun clearFinished() {
        JobRepository.clearFinished().forEach { releaseInputs(it) }
    }

    private fun releaseInputs(job: Job) {
        // mantém permissões ainda usadas por outros itens
        job.inputFiles.forEach { f -> if (JobRepository.jobsReferencing(f.uri, job.id).isEmpty()) Storage.releasePermission(this, f.uri) }
    }

    /** Abre a pasta de destino no gerenciador de arquivos (quando o aparelho suportar). */
    fun openDestination() {
        val tree = prefs.destinationTree ?: return
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_VIEW, doc),
        ).map { it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
            }
        }
        toast(getString(R.string.toast_open_folder_unsupported, Storage.treeDisplayName(contentResolver, tree)))
    }

    // ================================================================== JobCard.Actions

    override fun setMode(job: Job, mode: DiscMode) {
        if (job.analysis?.allowedModes?.contains(mode) == true && !job.isActive) {
            job.mode = mode
            job.log("Modo alterado para ${mode.name}")
            JobRepository.persist()
        }
    }

    override fun remove(job: Job) {
        JobRepository.remove(job.id)?.let { releaseInputs(it) }
    }

    override fun retry(job: Job) = JobRepository.retry(job.id)

    override fun move(job: Job, delta: Int) {
        (tabs[1] as? QueueTab)?.focusAfterMove(job.id, delta)
        JobRepository.move(job.id, delta)
    }

    override fun confirmDelete(job: Job) {
        val files = job.deleteCandidates
        if (files.isEmpty()) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, job.outputName ?: "", files.joinToString("\n") { "• ${it.name}" }))
            .setPositiveButton(R.string.action_delete_original) { _, _ ->
                App.background.execute {
                    // a confirmação reavalia todas as condições antes de apagar
                    val plan = Deletion.plan(this, job, prefs)
                    if (plan.allowed && plan.files.map { it.uri }.toSet() == files.map { it.uri }.toSet()) {
                        Deletion.execute(this, job)
                    } else {
                        job.deleteState = DeleteState.KEPT
                        job.deleteNote = plan.reason ?: getString(R.string.keep_changed)
                        job.deleteCandidates = emptyList()
                    }
                    JobRepository.persist()
                }
            }
            .setNegativeButton(R.string.cancel_keep, null)
            .show()
    }

    override fun keepOriginal(job: Job) {
        job.deleteState = DeleteState.KEPT
        job.deleteNote = getString(R.string.keep_user)
        job.deleteCandidates = emptyList()
        JobRepository.persist()
    }

    override fun showLog(job: Job) = showText(job.displayName, job.logText().ifEmpty { getString(R.string.log_empty) })

    /** Diálogo com texto selecionável, botões Copiar e Compartilhar. */
    fun showText(title: String, text: String) {
        val tv = Ui.text(this, text, 12f, Ui.FG).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            val p = Ui.dp(this@MainActivity, 16)
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.action_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, text))
                toast(getString(R.string.toast_copied))
            }
            .setNeutralButton(R.string.action_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
                try {
                    startActivity(Intent.createChooser(send, title))
                } catch (e: Exception) {
                    toast(getString(R.string.err_no_share))
                }
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    fun confirm(title: Int, message: String, positive: Int, action: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> action() }
            .setNegativeButton(R.string.cancel_keep, null)
            .show()
    }

    // ================================================================== controles físicos

    /**
     * Gamepads (XInput e similares): A confirma, B volta, Start inicia a fila,
     * X adiciona arquivos, Y cancela a fila (com confirmação), Select abre o log
     * do item atual, L1/R1 trocam de seção. D-pad e analógico movem o foco.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapped = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) KeyEvent.KEYCODE_DPAD_CENTER else 0
        if (mapped != 0) {
            return super.dispatchKeyEvent(
                KeyEvent(event.downTime, event.eventTime, event.action, mapped, event.repeatCount, event.metaState,
                    event.deviceId, event.scanCode, event.flags, event.source),
            )
        }
        val handled = setOf(
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        )
        if (event.keyCode in handled) {
            if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_B -> if (current != 0) showTab(0) else finish()
                    KeyEvent.KEYCODE_BUTTON_START -> startQueue()
                    KeyEvent.KEYCODE_BUTTON_X -> pickFiles()
                    KeyEvent.KEYCODE_BUTTON_Y -> confirmCancelQueue()
                    KeyEvent.KEYCODE_BUTTON_SELECT -> (JobRepository.current() ?: JobRepository.snapshot().lastOrNull())?.let { showLog(it) }
                    KeyEvent.KEYCODE_BUTTON_L1 -> showTab((current + tabs.size - 1) % tabs.size)
                    KeyEvent.KEYCODE_BUTTON_R1 -> showTab((current + 1) % tabs.size)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // "Voltar" (gesto, botão ou B): de outra seção volta para Conversão; na Conversão sai.
    // Android 13+ usa OnBackInvokedCallback (obrigatório com o voltar preditivo do Android 16).
    private var backCallback: Any? = null

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = onBackInvokedDispatcher
        val cb = backCallback as? android.window.OnBackInvokedCallback
        if (current != 0 && cb == null) {
            val newCb = android.window.OnBackInvokedCallback { showTab(0) }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, newCb)
            backCallback = newCb
        } else if (current == 0 && cb != null) {
            dispatcher.unregisterOnBackInvokedCallback(cb)
            backCallback = null
        }
    }

    @Deprecated("Somente Android 10-12; nas versões novas o callback acima é usado")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (current != 0) showTab(0) else super.onBackPressed()
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_FILES = 10
        private const val REQ_INPUT_TREE = 11
        private const val REQ_DEST = 12
        private const val REQ_NOTIF = 13
        private const val REQ_CHD = 14
    }
}
