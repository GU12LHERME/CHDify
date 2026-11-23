package com.conversorchd.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.conversorchd.R
import com.conversorchd.core.DeleteState
import com.conversorchd.core.DiscMode
import com.conversorchd.core.Format
import com.conversorchd.core.Job
import com.conversorchd.core.JobState
import com.conversorchd.core.Operation
import com.conversorchd.core.SourceKind
import com.conversorchd.core.Storage

/**
 * Cartão compacto de um item da fila. Textos e barra são atualizados no lugar
 * (sem recriar views) para não roubar o foco do D-pad durante a operação; a
 * linha de botões só é refeita quando o conjunto de ações muda.
 */
class JobCard(private val ctx: Context, private val actions: Actions) {

    interface Actions {
        fun setMode(job: Job, mode: DiscMode)
        fun remove(job: Job)
        fun retry(job: Job)
        fun showLog(job: Job)
        fun move(job: Job, delta: Int)
        fun confirmDelete(job: Job)
        fun keepOriginal(job: Job)
    }

    val view: LinearLayout = Ui.card(ctx)
    private val format = Ui.badge(ctx)
    private val title = Ui.singleLine(Ui.text(ctx, "", 14f, Ui.FG, bold = true))
    private val state = Ui.badge(ctx)
    private val meta = Ui.text(ctx, "", 12f, Ui.SUBTLE)
    private val summary = Ui.text(ctx, "", 12f, Ui.SUBTLE)
    private val warnings = Ui.text(ctx, "", 12f, Ui.WARN)
    private val bar: ProgressBar = Ui.progressBar(ctx)
    private val message = Ui.text(ctx, "", 12f, Ui.FG)
    private val deletion = Ui.text(ctx, "", 12f, Ui.SUBTLE)
    private val buttons = Ui.row(ctx, 6)
    private var buttonsSig = ""

    init {
        val col = Ui.column(ctx, 4)
        col.addView(Ui.row(ctx, 8).apply {
            addView(format)
            addView(title, Ui.weighted())
            addView(state)
        }, Ui.lp())
        col.addView(meta)
        col.addView(summary)
        col.addView(warnings)
        col.addView(bar, Ui.lp(h = Ui.dp(ctx, 4)).apply { topMargin = Ui.dp(ctx, 2) })
        col.addView(message)
        col.addView(deletion)
        view.addView(col, Ui.lp())
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        }
        view.addView(scroll, Ui.lp().apply { topMargin = Ui.dp(ctx, 8) })
    }

    fun bind(job: Job, index: Int, count: Int) {
        val ext = Storage.extension(job.displayName).uppercase().ifEmpty { "IMG" }
        Ui.setBadge(format, "$ext → ${Labels.target(job)}", Ui.SUBTLE)
        title.text = job.displayName

        val kind = when (job.kind) {
            SourceKind.CUE -> ctx.getString(R.string.kind_cue, job.tracks.size)
            SourceKind.GDI -> ctx.getString(R.string.kind_gdi, job.tracks.size)
            else -> null
        }
        meta.text = listOfNotNull(
            Labels.operation(ctx, job.operation), kind, Format.bytes(job.inputBytes), Labels.mode(ctx, job),
            job.m3uGroup?.let { ctx.getString(R.string.m3u_group, it) },
        ).joinToString(" · ")

        val a = job.analysis
        setText(summary, a?.summary)
        setText(warnings, a?.warnings?.takeIf { it.isNotEmpty() && !job.isFinished }?.joinToString("\n") { "⚠ $it" })
        Ui.setBadge(state, Labels.state(ctx, job), if (job.state == JobState.READY || job.state == JobState.CANCELLED || job.state == JobState.SKIPPED) Ui.SUBTLE else Ui.FG)

        when {
            job.isActive -> {
                bar.visibility = View.VISIBLE
                bar.progress = (job.fraction * 1000).toInt()
            }
            job.state == JobState.DONE -> {
                bar.visibility = View.VISIBLE
                bar.progress = 1000
            }
            else -> bar.visibility = View.GONE
        }

        val msg = when (job.state) {
            JobState.BLOCKED -> a?.error
            JobState.FAILED, JobState.CANCELLED, JobState.SKIPPED -> job.message
            JobState.INTERRUPTED -> ctx.getString(R.string.msg_interrupted)
            JobState.DONE -> doneLine(job)
            else -> null
        }
        setText(message, msg)
        message.setTextColor(if (job.state == JobState.CANCELLED || job.state == JobState.SKIPPED) Ui.SUBTLE else Ui.FG)

        setText(deletion, when (job.deleteState) {
            DeleteState.NONE -> null
            DeleteState.PENDING_CONFIRM -> ctx.getString(R.string.delete_pending, job.deleteCandidates.size)
            DeleteState.DELETED -> "✓ " + (job.deleteNote ?: ctx.getString(R.string.delete_done))
            DeleteState.KEPT -> ctx.getString(R.string.delete_kept, job.deleteNote ?: "")
            DeleteState.FAILED -> job.deleteNote
        })

        updateButtons(job, index, count)
    }

    private fun doneLine(job: Job): String {
        val where = listOfNotNull(job.outputFolder, job.outputName).joinToString("/")
        return when (job.operation) {
            Operation.VERIFY -> ctx.getString(R.string.done_verify)
            Operation.EXTRACT -> ctx.getString(R.string.done_extract, where, Format.bytes(job.outputBytes)) +
                if (job.verified) " · " + ctx.getString(R.string.state_verified) else ""
            else -> {
                val saved = job.inputBytes - job.outputBytes
                val pct = if (job.inputBytes > 0) Format.percent(saved.toDouble() / job.inputBytes) else "—"
                ctx.getString(R.string.done_line, where, Format.bytes(job.outputBytes), Format.bytes(saved), pct) +
                    if (job.verified) " · " + ctx.getString(R.string.state_verified) else ""
            }
        }
    }

    private fun setText(tv: TextView, s: String?) {
        if (s.isNullOrBlank()) {
            tv.visibility = View.GONE
        } else {
            tv.text = s
            tv.visibility = View.VISIBLE
        }
    }

    private fun updateButtons(job: Job, index: Int, count: Int) {
        val allowed = job.analysis?.allowedModes ?: emptySet()
        val editable = job.state == JobState.READY || job.state == JobState.FAILED || job.state == JobState.CANCELLED
        val canEditMode = job.operation == Operation.CONVERT && allowed.size > 1 && editable
        val canRemove = !job.isActive && job.deleteState != DeleteState.PENDING_CONFIRM
        val canRetry = job.state == JobState.FAILED || job.state == JobState.CANCELLED || job.state == JobState.INTERRUPTED || job.state == JobState.SKIPPED
        val canUp = !job.isActive && index > 0
        val canDown = !job.isActive && index < count - 1
        val pendingDelete = job.deleteState == DeleteState.PENDING_CONFIRM
        val sig = "$canEditMode|${job.mode}|$canRemove|$canRetry|$canUp|$canDown|$pendingDelete|${job.state}"
        if (sig == buttonsSig) return
        val hadFocus = buttons.findFocus()?.tag as? String
        buttonsSig = sig
        buttons.removeAllViews()

        fun add(label: String, style: Ui.Style, tag: String, desc: String? = null, onClick: () -> Unit) {
            buttons.addView(Ui.smallButton(ctx, label, style) { onClick() }.apply {
                this.tag = tag
                if (desc != null) contentDescription = desc
            })
        }

        if (pendingDelete) {
            add(ctx.getString(R.string.action_delete_original), Ui.Style.FILLED, "delete") { actions.confirmDelete(job) }
            add(ctx.getString(R.string.action_keep_original), Ui.Style.OUTLINED, "keep") { actions.keepOriginal(job) }
        }
        if (canEditMode) {
            for (m in listOf(DiscMode.DVD, DiscMode.CD)) {
                if (m !in allowed) continue
                val selected = job.mode == m
                val label = ctx.getString(if (m == DiscMode.CD) R.string.mode_cd else R.string.mode_dvd)
                add(if (selected) "✓ $label" else label, if (selected) Ui.Style.FILLED else Ui.Style.OUTLINED, "mode-${m.name}") {
                    actions.setMode(job, m)
                }
            }
        }
        if (canRetry) add(ctx.getString(R.string.action_retry), Ui.Style.TONAL, "retry") { actions.retry(job) }
        if (canUp) add("↑", Ui.Style.OUTLINED, "up", ctx.getString(R.string.action_move_up)) { actions.move(job, -1) }
        if (canDown) add("↓", Ui.Style.OUTLINED, "down", ctx.getString(R.string.action_move_down)) { actions.move(job, 1) }
        add(ctx.getString(R.string.action_log), Ui.Style.OUTLINED, "log") { actions.showLog(job) }
        if (canRemove) add(ctx.getString(R.string.action_remove), Ui.Style.TEXT, "remove") { actions.remove(job) }

        if (hadFocus != null) {
            (0 until buttons.childCount).map { buttons.getChildAt(it) }.firstOrNull { it.tag == hadFocus }?.requestFocus()
                ?: buttons.getChildAt(0)?.requestFocus()
        }
    }
}
