package com.conversorchd.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.conversorchd.R
import com.conversorchd.core.DiscMode
import com.conversorchd.core.Format
import com.conversorchd.core.Job
import com.conversorchd.core.JobState
import com.conversorchd.core.SourceKind
import com.conversorchd.core.Storage

/**
 * Cartão compacto de um item da fila. Os textos e a barra são atualizados no
 * lugar (sem recriar views) para não roubar o foco do D-pad durante a
 * conversão; a linha de botões só é refeita quando o conjunto de ações muda.
 */
class JobCard(private val ctx: Context, private val actions: Actions) {

    interface Actions {
        fun setMode(job: Job, mode: DiscMode)
        fun remove(job: Job)
        fun retry(job: Job)
        fun showLog(job: Job)
        fun move(job: Job, delta: Int)
    }

    val view: LinearLayout = Ui.card(ctx)
    private val format = Ui.badge(ctx)
    private val title = Ui.singleLine(Ui.text(ctx, "", 14f, Ui.ON_SURFACE, bold = true))
    private val state = Ui.badge(ctx)
    private val meta = Ui.text(ctx, "", 12f, Ui.ON_SURFACE_VAR)
    private val summary = Ui.text(ctx, "", 12f, Ui.ON_SURFACE_VAR)
    private val warnings = Ui.text(ctx, "", 12f, Ui.WARN)
    private val bar: ProgressBar = Ui.progressBar(ctx)
    private val message = Ui.text(ctx, "", 12f, Ui.ERROR)
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
        view.addView(col, Ui.lp())
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        }
        view.addView(scroll, Ui.lp().apply { topMargin = Ui.dp(ctx, 8) })
    }

    fun bind(job: Job, index: Int, count: Int) {
        val ext = Storage.extension(job.displayName).uppercase().ifEmpty { "IMG" }
        Ui.setBadge(format, ext, Ui.SUBTLE)
        title.text = job.displayName

        val kind = when (job.kind) {
            SourceKind.IMAGE -> ctx.getString(R.string.kind_image)
            SourceKind.CUE -> ctx.getString(R.string.kind_cue, job.tracks.size)
            SourceKind.GDI -> ctx.getString(R.string.kind_gdi, job.tracks.size)
        }
        val modeText = job.mode?.let { ctx.getString(if (it == DiscMode.CD) R.string.mode_cd else R.string.mode_dvd) }
        meta.text = listOfNotNull(kind, Format.bytes(job.inputBytes), modeText).joinToString(" · ")

        val a = job.analysis
        setText(summary, a?.summary)
        setText(warnings, a?.warnings?.takeIf { it.isNotEmpty() }?.joinToString("\n") { "⚠ $it" })

        val (statusText, statusColor) = statusOf(job)
        Ui.setBadge(state, statusText, statusColor)

        // barra individual: visível durante a conversão e ao final
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
            JobState.FAILED, JobState.CANCELLED -> job.message
            JobState.DONE -> doneLine(job)
            else -> null
        }
        setText(message, msg)
        message.setTextColor(if (job.state == JobState.CANCELLED) Ui.SUBTLE else Ui.FG)

        updateButtons(job, index, count)
    }

    private fun doneLine(job: Job): String {
        val saved = job.inputBytes - job.outputBytes
        val pct = if (job.inputBytes > 0) Format.percent(saved.toDouble() / job.inputBytes) else "—"
        val base = ctx.getString(R.string.done_line, job.outputName ?: "", Format.bytes(job.outputBytes), Format.bytes(saved), pct)
        return if (job.verified) base + " · " + ctx.getString(R.string.state_verified) else base
    }

    private fun setText(tv: TextView, s: String?) {
        if (s.isNullOrBlank()) {
            tv.visibility = View.GONE
        } else {
            tv.text = s
            tv.visibility = View.VISIBLE
        }
    }

    private fun statusOf(job: Job): Pair<String, Int> = when (job.state) {
        JobState.ANALYZING -> ctx.getString(R.string.state_analyzing) to Ui.ON_SURFACE_VAR
        JobState.READY -> ctx.getString(R.string.state_ready) to Ui.SUBTLE
        JobState.BLOCKED -> "✕ " + ctx.getString(R.string.state_blocked) to Ui.FG
        JobState.RUNNING -> ctx.getString(R.string.state_running, Format.percent(job.fraction)) to Ui.PRIMARY
        JobState.VERIFYING -> ctx.getString(R.string.state_verifying, Format.percent(job.fraction)) to Ui.PRIMARY
        JobState.DONE -> "✓ " + ctx.getString(R.string.state_done_short) to Ui.FG
        JobState.FAILED -> "✕ " + ctx.getString(R.string.state_failed) to Ui.FG
        JobState.CANCELLED -> "– " + ctx.getString(R.string.state_cancelled) to Ui.SUBTLE
    }

    private fun updateButtons(job: Job, index: Int, count: Int) {
        val allowed = job.analysis?.allowedModes ?: emptySet()
        val editable = job.state == JobState.READY || job.state == JobState.FAILED || job.state == JobState.CANCELLED
        val canEditMode = allowed.size > 1 && editable
        val canRemove = !job.isActive
        val canRetry = job.state == JobState.FAILED || job.state == JobState.CANCELLED
        val canUp = !job.isActive && index > 0
        val canDown = !job.isActive && index < count - 1
        val sig = "$canEditMode|${job.mode}|$canRemove|$canRetry|$canUp|$canDown|${job.state}"
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
