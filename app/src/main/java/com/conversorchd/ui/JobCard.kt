package com.conversorchd.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.conversorchd.R
import com.conversorchd.core.DiscMode
import com.conversorchd.core.Format
import com.conversorchd.core.Job
import com.conversorchd.core.JobState
import com.conversorchd.core.SourceKind

/**
 * Cartão de um item da fila. Os textos são atualizados no lugar (sem recriar
 * views) para não roubar o foco do D-pad durante a conversão; a linha de
 * botões só é refeita quando o conjunto de ações muda.
 */
class JobCard(private val ctx: Context, private val actions: Actions) {

    interface Actions {
        fun setMode(job: Job, mode: DiscMode)
        fun remove(job: Job)
        fun retry(job: Job)
        fun showLog(job: Job)
    }

    val view: LinearLayout = Ui.card(ctx)
    private val title = Ui.singleLine(Ui.text(ctx, "", 16f, Ui.ON_SURFACE, bold = true))
    private val meta = Ui.text(ctx, "", 13f, Ui.ON_SURFACE_VAR)
    private val summary = Ui.text(ctx, "", 13f, Ui.ON_SURFACE_VAR)
    private val warnings = Ui.text(ctx, "", 13f, Ui.WARN)
    private val status = Ui.text(ctx, "", 14f, Ui.PRIMARY, bold = true)
    private val message = Ui.text(ctx, "", 13f, Ui.ERROR)
    private val buttons = Ui.row(ctx, 8)
    private var buttonsSig = ""

    init {
        val col = Ui.column(ctx, 6)
        col.addView(title)
        col.addView(meta)
        col.addView(summary)
        col.addView(warnings)
        col.addView(status)
        col.addView(message)
        view.addView(col, Ui.lp())
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        }
        view.addView(scroll, Ui.lp().apply { topMargin = Ui.dp(ctx, 10) })
    }

    fun bind(job: Job) {
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
        status.text = statusText
        status.setTextColor(statusColor)

        val msg = when {
            job.state == JobState.BLOCKED -> a?.error
            job.state == JobState.FAILED || job.state == JobState.CANCELLED -> job.message
            else -> null
        }
        setText(message, msg)
        message.setTextColor(if (job.state == JobState.CANCELLED) Ui.WARN else Ui.ERROR)

        updateButtons(job)
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
        JobState.READY -> ctx.getString(R.string.state_ready) to Ui.PRIMARY
        JobState.BLOCKED -> ctx.getString(R.string.state_blocked) to Ui.ERROR
        JobState.RUNNING -> ctx.getString(R.string.state_running, Format.percent(job.fraction)) to Ui.PRIMARY
        JobState.VERIFYING -> ctx.getString(R.string.state_verifying, Format.percent(job.fraction)) to Ui.PRIMARY
        JobState.DONE -> {
            val pct = if (job.inputBytes > 0) Format.percent(job.outputBytes.toDouble() / job.inputBytes) else "—"
            val base = ctx.getString(R.string.state_done, job.outputName ?: "", Format.bytes(job.outputBytes), pct)
            (if (job.verified) base + " · " + ctx.getString(R.string.state_verified) else base) to Ui.SUCCESS
        }
        JobState.FAILED -> ctx.getString(R.string.state_failed) to Ui.ERROR
        JobState.CANCELLED -> ctx.getString(R.string.state_cancelled) to Ui.WARN
    }

    private fun updateButtons(job: Job) {
        val allowed = job.analysis?.allowedModes ?: emptySet()
        val canEditMode = allowed.size > 1 && (job.state == JobState.READY || job.state == JobState.FAILED || job.state == JobState.CANCELLED)
        val canRemove = !job.isActive
        val canRetry = job.state == JobState.FAILED || job.state == JobState.CANCELLED
        val sig = "$canEditMode|${job.mode}|$canRemove|$canRetry|${job.state}"
        if (sig == buttonsSig) return
        val hadFocus = buttons.findFocus()?.tag as? String
        buttonsSig = sig
        buttons.removeAllViews()

        if (canEditMode) {
            for (m in listOf(DiscMode.DVD, DiscMode.CD)) {
                if (m !in allowed) continue
                val selected = job.mode == m
                val label = ctx.getString(if (m == DiscMode.CD) R.string.mode_cd else R.string.mode_dvd)
                val b = Ui.smallButton(ctx, if (selected) "✓ $label" else label, if (selected) Ui.Style.FILLED else Ui.Style.OUTLINED) {
                    actions.setMode(job, m)
                }
                b.tag = "mode-${m.name}"
                buttons.addView(b)
            }
        }
        if (canRetry) buttons.addView(Ui.smallButton(ctx, ctx.getString(R.string.action_retry), Ui.Style.TONAL) { actions.retry(job) }.apply { tag = "retry" })
        buttons.addView(Ui.smallButton(ctx, ctx.getString(R.string.action_log), Ui.Style.OUTLINED) { actions.showLog(job) }.apply { tag = "log" })
        if (canRemove) buttons.addView(Ui.smallButton(ctx, ctx.getString(R.string.action_remove), Ui.Style.TEXT) { actions.remove(job) }.apply { tag = "remove" })

        if (hadFocus != null) {
            (0 until buttons.childCount).map { buttons.getChildAt(it) }.firstOrNull { it.tag == hadFocus }?.requestFocus()
                ?: buttons.getChildAt(0)?.requestFocus()
        }
    }
}
