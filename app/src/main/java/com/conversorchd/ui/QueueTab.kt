package com.conversorchd.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.conversorchd.R
import com.conversorchd.core.Format
import com.conversorchd.core.JobRepository
import com.conversorchd.core.JobState

/** Fila: progresso individual e geral, reorganização, remoção, cancelamento. */
class QueueTab(private val a: MainActivity) : MainActivity.Tab {

    private val cards = LinkedHashMap<Long, JobCard>()
    private lateinit var list: LinearLayout
    private lateinit var header: TextView
    private lateinit var overallBar: ProgressBar
    private lateinit var empty: TextView
    private lateinit var clear: Button
    private lateinit var cancelRow: LinearLayout
    private var focusAfterRefresh: Pair<Long, String>? = null

    fun focusAfterMove(id: Long, delta: Int) {
        focusAfterRefresh = id to (if (delta < 0) "up" else "down")
    }

    override fun build(): View {
        val ctx = a
        val pad = Ui.dp(ctx, 12)
        cards.clear()
        val col = Ui.column(ctx, 8).apply { setPadding(pad, pad, pad, pad) }
        header = Ui.text(ctx, "", 13f, Ui.SUBTLE)
        overallBar = Ui.progressBar(ctx)
        clear = Ui.smallButton(ctx, ctx.getString(R.string.action_clear_finished), Ui.Style.TEXT) { a.clearFinished() }.apply { tag = "clear" }
        col.addView(Ui.row(ctx, 8).apply {
            addView(header, Ui.weighted())
            addView(clear)
        }, Ui.lp())
        col.addView(overallBar, Ui.lp(h = Ui.dp(ctx, 3)))
        cancelRow = Ui.row(ctx, 8).apply {
            addView(Ui.smallButton(ctx, ctx.getString(R.string.action_cancel_item), Ui.Style.OUTLINED) { a.cancelCurrentItem() }.apply { tag = "q-cancel-item" }, Ui.weighted())
            addView(Ui.smallButton(ctx, ctx.getString(R.string.action_cancel_queue), Ui.Style.OUTLINED) { a.confirmCancelQueue() }.apply { tag = "q-cancel-queue" }, Ui.weighted())
            addView(Ui.smallButton(ctx, ctx.getString(R.string.action_start), Ui.Style.FILLED) { a.startQueue() }.apply { tag = "q-start" }, Ui.weighted())
        }
        col.addView(cancelRow, Ui.lp())
        empty = Ui.empty(ctx, ctx.getString(R.string.queue_empty))
        col.addView(empty)
        list = Ui.column(ctx, 6)
        col.addView(list, Ui.lp())
        return col
    }

    override fun refresh() {
        if (!::list.isInitialized) return
        val jobs = JobRepository.snapshot()
        val ids = jobs.map { it.id }.toSet()
        cards.keys.filter { it !in ids }.forEach { id -> cards.remove(id)?.let { list.removeView(it.view) } }
        jobs.forEachIndexed { index, job ->
            val card = cards.getOrPut(job.id) { JobCard(a, a) }
            if (list.getChildAt(index) !== card.view) {
                (card.view.parent as? ViewGroup)?.removeView(card.view)
                list.addView(card.view, index, Ui.lp())
            }
            card.bind(job, index, jobs.size)
        }
        focusAfterRefresh?.let { (id, tag) ->
            focusAfterRefresh = null
            val v = cards[id]?.view
            (v?.findViewWithTag<View>(tag) ?: v?.findViewWithTag<View>("log"))?.requestFocus()
        }
        val processing = JobRepository.processing
        empty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
        clear.visibility = if (jobs.any { it.isFinished }) View.VISIBLE else View.GONE
        val ready = jobs.count { it.state == JobState.READY }
        val done = jobs.count { it.state == JobState.DONE }
        val failed = jobs.count { it.state == JobState.FAILED }
        header.text = a.getString(R.string.queue_header, jobs.size, ready, done, failed)
        overallBar.visibility = if (processing) View.VISIBLE else View.GONE
        overallBar.progress = (JobRepository.batchProgress() * 1000).toInt()
        cancelRow.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        cancelRow.getChildAt(0).visibility = if (processing) View.VISIBLE else View.GONE
        cancelRow.getChildAt(1).visibility = if (processing) View.VISIBLE else View.GONE
        cancelRow.getChildAt(2).visibility = if (!processing && ready > 0) View.VISIBLE else View.GONE
        if (processing) header.text = header.text.toString() + " · " + Format.percent(JobRepository.batchProgress())
    }
}
