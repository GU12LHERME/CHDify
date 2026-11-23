package com.conversorchd.ui

import android.view.View
import android.widget.LinearLayout
import com.conversorchd.App
import com.conversorchd.R
import com.conversorchd.core.Format
import com.conversorchd.core.HistoryEntry
import com.conversorchd.core.HistoryStore
import com.conversorchd.core.JobState
import com.conversorchd.core.Operation
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Histórico local de operações (sem internet, sem dados além do necessário). */
class HistoryTab(private val a: MainActivity) : MainActivity.Tab {

    private lateinit var list: LinearLayout
    private var shown = -1

    override fun build(): View {
        val ctx = a
        val pad = Ui.dp(ctx, 12)
        val col = Ui.column(ctx, 8).apply { setPadding(pad, pad, pad, pad) }
        col.addView(Ui.smallButton(ctx, ctx.getString(R.string.history_clear), Ui.Style.TEXT) {
            a.confirm(R.string.history_clear_title, ctx.getString(R.string.history_clear_message), R.string.history_clear) {
                App.background.execute {
                    HistoryStore.clear(a)
                    a.runOnUiThread { shown = -1; refresh() }
                }
            }
        }.apply { tag = "history-clear" })
        list = Ui.column(ctx, 6)
        col.addView(list, Ui.lp())
        shown = -1
        return col
    }

    override fun refresh() {
        if (!::list.isInitialized) return
        App.background.execute {
            val entries = HistoryStore.all(a)
            a.runOnUiThread { render(entries) }
        }
    }

    private fun render(entries: List<HistoryEntry>) {
        val sig = entries.size * 31 + (entries.firstOrNull()?.timestamp?.toInt() ?: 0)
        if (sig == shown) return
        shown = sig
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(Ui.empty(a, a.getString(R.string.history_empty)))
            return
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag("pt-BR"))
        for (e in entries.take(150)) {
            val card = Ui.card(a)
            val c = Ui.column(a, 2)
            val symbol = when (e.result) {
                JobState.DONE -> "✓"
                JobState.FAILED -> "✕"
                else -> "–"
            }
            c.addView(Ui.singleLine(Ui.text(a, "$symbol  ${e.name}", 14f, Ui.FG, bold = true)))
            val parts = listOfNotNull(
                Labels.operation(a, e.operation),
                fmt.format(Date(e.timestamp)),
                Format.duration(e.durationMs / 1000),
                e.profile?.let { Labels.profile(a, it) },
            )
            c.addView(Ui.text(a, parts.joinToString(" · "), 12f, Ui.SUBTLE))
            if (e.result == JobState.DONE && e.operation != Operation.VERIFY) {
                val sizes = "${Format.bytes(e.inputBytes)} → ${Format.bytes(e.outputBytes)}"
                val compress = e.operation == Operation.CONVERT || e.operation == Operation.RECOMPRESS
                val saved = e.inputBytes - e.outputBytes
                val extra = if (compress && e.inputBytes > 0) " · ${a.getString(R.string.stat_saved).lowercase()} ${Format.bytes(saved)} (${Format.percent(saved.toDouble() / e.inputBytes)})" else ""
                c.addView(Ui.text(a, sizes + extra + if (e.verified) " · " + a.getString(R.string.state_verified) else "", 12f, Ui.FG))
            } else if (e.note != null) {
                c.addView(Ui.text(a, e.note, 12f, Ui.SUBTLE).apply { maxLines = 3 })
            }
            card.addView(c, Ui.lp())
            list.addView(card)
        }
    }
}
