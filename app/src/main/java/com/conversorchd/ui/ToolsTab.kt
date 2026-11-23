package com.conversorchd.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.conversorchd.App
import com.conversorchd.R
import com.conversorchd.core.ChdInfo
import com.conversorchd.core.Format
import com.conversorchd.core.InputFile
import com.conversorchd.core.Operation
import com.conversorchd.nativebridge.NativeChd

/**
 * Ferramentas CHD: inspeção (cabeçalho, codecs, metadados), verificação
 * independente, recompactação (chdman copy), extração e exportação das
 * informações técnicas. Operações demoradas entram na fila.
 */
class ToolsTab(private val a: MainActivity) : MainActivity.Tab {

    private var file: InputFile? = null
    private var info: ChdInfo? = null
    private lateinit var body: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var title: TextView

    override fun build(): View {
        val ctx = a
        val pad = Ui.dp(ctx, 12)
        val col = Ui.column(ctx, 10).apply { setPadding(pad, pad, pad, pad) }
        col.addView(Ui.button(ctx, ctx.getString(R.string.tools_pick), Ui.Style.TONAL) {
            a.pickChd { f -> load(f) }
        }.apply { tag = "tools-pick" }, Ui.lp())
        col.addView(Ui.text(ctx, ctx.getString(R.string.tools_hint), 12f, Ui.SUBTLE))
        val card = Ui.card(ctx)
        val c = Ui.column(ctx, 6)
        title = Ui.text(ctx, "", 15f, Ui.FG, bold = true).apply { maxLines = 3 }
        body = Ui.column(ctx, 4)
        actions = Ui.column(ctx, 6)
        c.addView(title)
        c.addView(body, Ui.lp())
        c.addView(actions, Ui.lp().apply { topMargin = Ui.dp(ctx, 6) })
        card.addView(c, Ui.lp())
        col.addView(card)
        render()
        return col
    }

    private fun load(f: InputFile) {
        file = f
        info = null
        render()
        App.background.execute {
            val i = try {
                a.contentResolver.openFileDescriptor(f.uri, "r")?.use { ChdInfo.parse(NativeChd.chdInfo(it.fd)) }
            } catch (e: Exception) {
                null
            } ?: ChdInfo.parse("{\"ok\":false,\"error\":\"${a.getString(R.string.err_open)}\"}")
            a.runOnUiThread {
                if (file == f) {
                    info = i
                    render()
                }
            }
        }
    }

    private fun render() {
        if (!::body.isInitialized) return
        val ctx = a
        body.removeAllViews()
        actions.removeAllViews()
        val f = file
        if (f == null) {
            title.text = ctx.getString(R.string.tools_none)
            return
        }
        title.text = f.name
        val i = info
        if (i == null) {
            body.addView(Ui.text(ctx, ctx.getString(R.string.state_analyzing), 13f, Ui.SUBTLE))
            return
        }
        if (!i.ok) {
            body.addView(Ui.text(ctx, "✕ " + ctx.getString(R.string.err_chd_invalid, i.error), 13f, Ui.FG))
            return
        }
        for ((label, value) in rows(i)) body.addView(Ui.infoRow(ctx, label, value))

        fun act(label: String, tag: String, style: Ui.Style = Ui.Style.OUTLINED, onClick: () -> Unit) =
            actions.addView(Ui.button(ctx, label, style) { onClick() }.apply { this.tag = tag }, Ui.lp())
        act(ctx.getString(R.string.tools_verify), "tools-verify") { a.enqueueTool(f, Operation.VERIFY) }
        act(ctx.getString(R.string.tools_recompress, Labels.profile(ctx, a.prefs.profile)), "tools-recompress") { a.enqueueTool(f, Operation.RECOMPRESS) }
        if (i.type == "dvd" || i.type == "cd" || i.type == "gd") {
            val target = when (i.type) { "dvd" -> "ISO"; "gd" -> "GDI"; else -> "BIN/CUE" }
            act(ctx.getString(R.string.tools_extract, target), "tools-extract") { a.enqueueTool(f, Operation.EXTRACT) }
        }
        act(ctx.getString(R.string.tools_export), "tools-export", Ui.Style.TEXT) { a.showText(f.name, report(f, i)) }
    }

    private fun typeName(i: ChdInfo) = when (i.type) {
        "dvd" -> "DVD"
        "cd" -> "CD-ROM"
        "gd" -> "GD-ROM (Dreamcast)"
        else -> a.getString(R.string.chd_type_other)
    }

    private fun rows(i: ChdInfo): List<Pair<String, String>> {
        val ctx = a
        val ratio = if (i.logicalBytes > 0) Format.percent(i.fileBytes.toDouble() / i.logicalBytes) else "—"
        return listOfNotNull(
            ctx.getString(R.string.info_version) to "v${i.version}",
            ctx.getString(R.string.info_type) to typeName(i),
            if (i.tracks > 0) ctx.getString(R.string.info_tracks) to i.tracks.toString() else null,
            ctx.getString(R.string.info_file_size) to Format.bytes(i.fileBytes),
            ctx.getString(R.string.info_logical) to Format.bytes(i.logicalBytes),
            ctx.getString(R.string.info_ratio) to ratio,
            ctx.getString(R.string.info_hunk) to "${i.hunkBytes} B (${i.hunkBytes / maxOf(1, i.unitBytes)} × ${i.unitBytes} B)",
            ctx.getString(R.string.info_codecs) to i.compression.ifBlank { ctx.getString(R.string.info_uncompressed) },
            ctx.getString(R.string.info_sha1_data) to i.rawSha1,
            ctx.getString(R.string.info_sha1) to i.sha1,
            ctx.getString(R.string.info_metadata) to i.metadata.take(12).joinToString("\n").ifEmpty { "—" } +
                if (i.metadata.size > 12) "\n… (+${i.metadata.size - 12})" else "",
        )
    }

    /** Relatório técnico em texto (copiar/compartilhar). */
    private fun report(f: InputFile, i: ChdInfo): String = buildString {
        appendLine("CHDify — ${a.getString(R.string.tools_report_title)}")
        appendLine(f.name)
        appendLine()
        for ((label, value) in rows(i).dropLast(1)) appendLine("$label: $value")
        appendLine("${a.getString(R.string.info_metadata)}:")
        i.metadata.forEach { appendLine("  $it") }
        appendLine()
        appendLine("Núcleo: ${NativeChd.coreVersion()}")
    }
}
