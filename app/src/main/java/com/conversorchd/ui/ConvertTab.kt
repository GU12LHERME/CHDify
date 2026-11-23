package com.conversorchd.ui

import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.conversorchd.R
import com.conversorchd.core.DeleteState
import com.conversorchd.core.Format
import com.conversorchd.core.JobRepository
import com.conversorchd.core.JobState
import com.conversorchd.core.Operation
import com.conversorchd.core.Storage

/**
 * Seção principal: adicionar arquivos, iniciar, acompanhar o item atual e ver a
 * conclusão. Detalhes técnicos ficam na Fila, nas Ferramentas e nos Ajustes.
 */
class ConvertTab(private val a: MainActivity) : MainActivity.Tab {

    private lateinit var destButton: Button
    private lateinit var startButton: Button
    private lateinit var cancelRow: LinearLayout
    private lateinit var progressCard: LinearLayout
    private lateinit var doneCard: LinearLayout
    private lateinit var emptyHint: TextView

    private lateinit var progName: TextView
    private lateinit var progStage: TextView
    private lateinit var progPercent: TextView
    private lateinit var progBar: ProgressBar
    private lateinit var statSpeed: TextView
    private lateinit var statElapsed: TextView
    private lateinit var statRemaining: TextView
    private lateinit var statOriginal: TextView
    private lateinit var statProcessed: TextView
    private lateinit var statVerify: TextView
    private lateinit var overall: TextView
    private lateinit var overallBar: ProgressBar

    private lateinit var doneTitle: TextView
    private lateinit var doneBody: LinearLayout
    private var doneSig = ""

    override fun build(): View {
        val ctx = a
        val pad = Ui.dp(ctx, 12)
        val col = Ui.column(ctx, 10).apply { setPadding(pad, pad, pad, pad) }

        // --- adicionar ---
        val add = Ui.card(ctx)
        val addCol = Ui.column(ctx, 8)
        addCol.addView(Ui.label(ctx, ctx.getString(R.string.section_add)))
        val files = Ui.button(ctx, ctx.getString(R.string.action_add_files), Ui.Style.TONAL) { a.pickFiles() }.apply { tag = "add-files" }
        val folder = Ui.button(ctx, ctx.getString(R.string.action_add_folder), Ui.Style.TONAL) { a.pickInputFolder() }.apply { tag = "add-folder" }
        addCol.addView(Ui.row(ctx, 8).apply {
            addView(files, Ui.weighted())
            addView(folder, Ui.weighted())
        }, Ui.lp())
        addCol.addView(Ui.text(ctx, ctx.getString(R.string.add_hint), 12f, Ui.SUBTLE))
        destButton = Ui.button(ctx, "", Ui.Style.OUTLINED) { a.pickDestination() }.apply {
            tag = "dest"
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            Ui.singleLine(this, middle = true)
        }
        addCol.addView(destButton, Ui.lp())
        add.addView(addCol, Ui.lp())

        // --- iniciar / cancelar ---
        startButton = Ui.button(ctx, "", Ui.Style.FILLED) { a.startQueue() }.apply {
            tag = "start"
            minHeight = Ui.dp(ctx, 56)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        cancelRow = Ui.row(ctx, 8).apply {
            addView(Ui.button(ctx, ctx.getString(R.string.action_cancel_item), Ui.Style.OUTLINED) { a.cancelCurrentItem() }.apply { tag = "cancel-item" }, Ui.weighted())
            addView(Ui.button(ctx, ctx.getString(R.string.action_cancel_queue), Ui.Style.OUTLINED) { a.confirmCancelQueue() }.apply { tag = "cancel-queue" }, Ui.weighted())
        }

        // --- progresso ---
        progressCard = Ui.card(ctx)
        val p = Ui.column(ctx, 6)
        progName = Ui.text(ctx, "", 14f, Ui.FG, bold = true).apply { maxLines = 2 }
        progStage = Ui.text(ctx, "", 12f, Ui.SUBTLE)
        progPercent = Ui.text(ctx, "", 26f, Ui.FG, bold = true)
        progBar = Ui.progressBar(ctx)
        p.addView(Ui.row(ctx, 10).apply {
            addView(Ui.column(ctx, 0).apply { addView(progName); addView(progStage) }, Ui.weighted())
            addView(progPercent)
        }, Ui.lp())
        p.addView(progBar, Ui.lp(h = Ui.dp(ctx, 6)))
        val (s1, v1) = Ui.stat(ctx, ctx.getString(R.string.stat_speed))
        val (s2, v2) = Ui.stat(ctx, ctx.getString(R.string.stat_elapsed))
        val (s3, v3) = Ui.stat(ctx, ctx.getString(R.string.stat_remaining))
        val (s4, v4) = Ui.stat(ctx, ctx.getString(R.string.stat_original))
        val (s5, v5) = Ui.stat(ctx, ctx.getString(R.string.stat_processed))
        val (s6, v6) = Ui.stat(ctx, ctx.getString(R.string.stat_verify))
        statSpeed = v1; statElapsed = v2; statRemaining = v3; statOriginal = v4; statProcessed = v5; statVerify = v6
        p.addView(Ui.row(ctx, 8).apply { addView(s1, Ui.weighted()); addView(s2, Ui.weighted()); addView(s3, Ui.weighted()) }, Ui.lp())
        p.addView(Ui.row(ctx, 8).apply { addView(s4, Ui.weighted()); addView(s5, Ui.weighted()); addView(s6, Ui.weighted()) }, Ui.lp())
        p.addView(Ui.divider(ctx))
        overall = Ui.text(ctx, "", 12f, Ui.SUBTLE)
        overallBar = Ui.progressBar(ctx)
        p.addView(overall)
        p.addView(overallBar, Ui.lp(h = Ui.dp(ctx, 3)))
        progressCard.addView(p, Ui.lp())

        // --- conclusão ---
        doneCard = Ui.card(ctx)
        val d = Ui.column(ctx, 8)
        doneTitle = Ui.text(ctx, "", 18f, Ui.FG, bold = true)
        doneBody = Ui.column(ctx, 6)
        d.addView(doneTitle)
        d.addView(doneBody, Ui.lp())
        d.addView(Ui.row(ctx, 8).apply {
            addView(Ui.smallButton(ctx, ctx.getString(R.string.action_open_folder), Ui.Style.OUTLINED) { a.openDestination() }.apply { tag = "open-folder" }, Ui.weighted())
            addView(Ui.smallButton(ctx, ctx.getString(R.string.action_new), Ui.Style.OUTLINED) { a.pickFiles() }.apply { tag = "new" }, Ui.weighted())
            addView(Ui.smallButton(ctx, ctx.getString(R.string.action_finish), Ui.Style.FILLED) {
                JobRepository.dismissSummary()
                a.clearFinished()
            }.apply { tag = "finish" }, Ui.weighted())
        }, Ui.lp())
        doneCard.addView(d, Ui.lp())

        emptyHint = Ui.empty(ctx, ctx.getString(R.string.convert_empty))

        if (a.isWide()) {
            val left = Ui.column(ctx, 10).apply { addView(add); addView(startButton, Ui.lp()); addView(cancelRow, Ui.lp()) }
            val right = Ui.column(ctx, 10).apply { addView(progressCard); addView(doneCard); addView(emptyHint) }
            col.addView(Ui.row(ctx, 12).apply {
                gravity = Gravity.TOP
                addView(left, Ui.weighted())
                addView(right, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            }, Ui.lp())
        } else {
            col.addView(add)
            col.addView(startButton, Ui.lp())
            col.addView(cancelRow, Ui.lp())
            col.addView(progressCard)
            col.addView(doneCard)
            col.addView(emptyHint)
        }
        doneSig = ""
        return col
    }

    override fun refresh() {
        if (!::startButton.isInitialized) return
        val tree = a.prefs.destinationTree
        destButton.text = if (tree != null && Storage.hasTreePermission(a, tree))
            a.getString(R.string.dest_label, Storage.treeDisplayName(a.contentResolver, tree))
        else a.getString(R.string.dest_choose)

        val jobs = JobRepository.snapshot()
        val processing = JobRepository.processing
        val ready = jobs.count { it.state == JobState.READY }
        val analyzing = jobs.count { it.state == JobState.ANALYZING }
        startButton.visibility = if (processing) View.GONE else View.VISIBLE
        cancelRow.visibility = if (processing) View.VISIBLE else View.GONE
        startButton.isEnabled = ready > 0
        startButton.text = when {
            ready > 0 -> a.resources.getQuantityString(R.plurals.action_start_n, ready, ready)
            analyzing > 0 -> a.getString(R.string.state_analyzing)
            else -> a.getString(R.string.action_start)
        }

        val current = JobRepository.current()
        val summary = JobRepository.lastSummary
        progressCard.visibility = if (processing) View.VISIBLE else View.GONE
        doneCard.visibility = if (!processing && summary != null) View.VISIBLE else View.GONE
        emptyHint.visibility = if (!processing && summary == null && jobs.none { it.state == JobState.READY || it.state == JobState.ANALYZING }) View.VISIBLE else View.GONE

        if (processing) {
            if (current != null) {
                progName.text = current.displayName
                val stageText = a.getString(
                    when {
                        current.state == JobState.VERIFYING -> R.string.stage_verifying
                        current.operation == Operation.EXTRACT -> R.string.stage_extracting
                        current.operation == Operation.RECOMPRESS -> R.string.stage_recompressing
                        else -> R.string.stage_compressing
                    },
                )
                progStage.text = listOfNotNull(stageText, Labels.mode(a, current),
                    current.profile?.let { Labels.profile(a, it) }).joinToString(" · ")
                progBar.progress = (current.fraction * 1000).toInt()
                progPercent.text = Format.percent(current.fraction)
                val elapsed = (SystemClock.elapsedRealtime() - current.startedAt) / 1000
                val remaining = if (current.speedBps > 0) ((current.bytesTotal - current.bytesDone) / current.speedBps).toLong() else -1L
                statSpeed.text = Format.speed(current.speedBps)
                statElapsed.text = Format.duration(elapsed)
                statRemaining.text = Format.duration(remaining)
                statOriginal.text = Format.bytes(current.inputBytes)
                statProcessed.text = if (current.state == JobState.VERIFYING) Format.bytes(current.bytesDone) else Format.bytes(current.bytesWritten)
                statVerify.text = a.getString(
                    when {
                        current.state == JobState.VERIFYING -> R.string.verify_running
                        current.verified -> R.string.verify_ok
                        current.operation == Operation.EXTRACT && current.analysis?.chdType == "dvd" -> R.string.verify_inline
                        a.prefs.verifyAfter || current.operation != Operation.CONVERT -> R.string.verify_pending
                        else -> R.string.verify_off
                    },
                )
            } else {
                progName.text = a.getString(R.string.idle_starting)
                progStage.text = ""
                progPercent.text = ""
                progBar.progress = 0
            }
            val batch = JobRepository.batch
            val finished = batch.count { id -> JobRepository.get(id)?.isFinished == true }
            val frac = JobRepository.batchProgress()
            overall.text = a.getString(R.string.overall_line, (finished + if (current != null) 1 else 0).coerceAtMost(batch.size), batch.size, Format.percent(frac))
            overallBar.progress = (frac * 1000).toInt()
        }
        if (!processing && summary != null) bindSummary(summary)
    }

    private fun bindSummary(s: com.conversorchd.core.BatchSummary) {
        val jobs = s.jobIds.mapNotNull { JobRepository.get(it) }
        val sig = s.hashCode().toString() + jobs.joinToString { "${it.id}:${it.deleteState}" }
        if (sig == doneSig) return
        doneSig = sig
        doneTitle.text = when {
            s.done > 0 && s.failed == 0 && s.cancelled == 0 -> "✓ " + a.getString(R.string.done_title_ok)
            s.done == 0 -> "✕ " + a.getString(R.string.done_title_fail)
            else -> "! " + a.getString(R.string.done_title_partial)
        }
        doneBody.removeAllViews()
        val ctx = a
        if (jobs.size == 1) {
            val j = jobs[0]
            doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.info_file), j.displayName))
            doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.info_operation), Labels.operation(ctx, j.operation) + " → " + Labels.target(j)))
            if (j.state != JobState.DONE) doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.info_result), j.message ?: Labels.state(ctx, j)))
        } else {
            doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.info_items), a.getString(R.string.done_counts, s.done, s.failed, s.cancelled, s.skipped)))
        }
        val okJobs = jobs.filter { it.state == JobState.DONE }
        if (okJobs.isNotEmpty()) {
            val verified = okJobs.count { it.verified }
            doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.info_verify),
                if (verified == okJobs.size) a.getString(R.string.verify_ok) else a.getString(R.string.verify_partial, verified, okJobs.size)))
            doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.stat_original), Format.bytes(s.inputBytes)))
            doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.info_final), Format.bytes(s.outputBytes)))
            val compressing = okJobs.all { it.operation == Operation.CONVERT || it.operation == Operation.RECOMPRESS }
            if (compressing && s.inputBytes > 0) {
                val saved = s.inputBytes - s.outputBytes
                doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.stat_saved), "${Format.bytes(saved)} (${Format.percent(saved.toDouble() / s.inputBytes)})"))
            }
            doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.info_time), Format.duration(s.durationMs / 1000)))
            val tree = a.prefs.destinationTree
            if (tree != null) {
                val where = okJobs.mapNotNull { it.outputFolder }.distinct()
                val base = Storage.treeDisplayName(a.contentResolver, tree)
                doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.info_location),
                    if (where.size == 1 && okJobs.all { it.outputFolder != null }) "$base/${where[0]}" else base))
            }
            val pending = okJobs.filter { it.deleteState == DeleteState.PENDING_CONFIRM }
            val deleted = okJobs.count { it.deleteState == DeleteState.DELETED }
            val kept = okJobs.count { it.deleteState == DeleteState.KEPT || it.deleteState == DeleteState.FAILED }
            if (pending.isNotEmpty() || deleted > 0 || kept > 0) {
                doneBody.addView(Ui.infoRow(ctx, a.getString(R.string.info_originals), a.getString(R.string.done_delete_counts, deleted, pending.size, kept)))
            }
            if (pending.isNotEmpty()) {
                doneBody.addView(Ui.smallButton(ctx, a.getString(R.string.action_review_delete, pending.size), Ui.Style.OUTLINED) { a.showTab(1) }.apply { tag = "review-delete" }, Ui.lp())
            }
        }
    }
}
