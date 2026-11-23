package com.conversorchd.ui

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.conversorchd.BuildConfig
import com.conversorchd.R
import com.conversorchd.core.CompressionParams
import com.conversorchd.core.CpuInfo
import com.conversorchd.core.CpuMode
import com.conversorchd.core.DiscMode
import com.conversorchd.core.Profile
import com.conversorchd.core.Storage
import com.conversorchd.nativebridge.NativeChd

/** Ajustes: destino, perfil, verificação, arquivos, desempenho e opções avançadas. */
class SettingsTab(private val a: MainActivity) : MainActivity.Tab {

    private val prefs get() = a.prefs
    private lateinit var destButton: Button
    private lateinit var profileBox: LinearLayout
    private lateinit var profileHint: TextView
    private lateinit var cpuBox: LinearLayout
    private lateinit var cpuHint: TextView
    private lateinit var conflictBox: LinearLayout
    private lateinit var advancedBox: LinearLayout
    private lateinit var advancedToggle: Button
    private lateinit var threadsButton: Button
    private lateinit var dvdHunkButton: Button
    private lateinit var cdHunkButton: Button
    private lateinit var confirmSwitch: Switch
    private lateinit var deleteSwitch: Switch
    private lateinit var verifySwitch: Switch
    private lateinit var chdOpsSwitch: Switch

    override fun build(): View {
        val ctx = a
        val pad = Ui.dp(ctx, 12)
        val col = Ui.column(ctx, 10).apply { setPadding(pad, pad, pad, pad) }

        fun section(title: Int, vararg views: View) {
            val card = Ui.card(ctx)
            val c = Ui.column(ctx, 6)
            c.addView(Ui.label(ctx, ctx.getString(title)))
            views.forEach { c.addView(it, Ui.lp()) }
            card.addView(c, Ui.lp())
            col.addView(card)
        }

        destButton = Ui.button(ctx, "", Ui.Style.OUTLINED) { a.pickDestination() }.apply {
            tag = "set-dest"; gravity = Gravity.CENTER_VERTICAL or Gravity.START; Ui.singleLine(this)
        }
        section(R.string.set_destination, destButton)

        profileBox = LinearLayout(ctx)
        profileHint = Ui.text(ctx, "", 12f, Ui.SUBTLE)
        verifySwitch = Ui.switchRow(ctx, ctx.getString(R.string.option_verify), prefs.verifyAfter, "verify") { on ->
            if (!on && prefs.deleteOriginal) {
                // a exclusão do original depende da verificação
                verifySwitch.isChecked = true
                a.toast(ctx.getString(R.string.toast_verify_required))
            } else {
                prefs.verifyAfter = on
            }
        }
        section(R.string.profile_label, profileBox, profileHint, verifySwitch)

        deleteSwitch = Ui.switchRow(ctx, ctx.getString(R.string.option_delete), prefs.deleteOriginal, "delete") { on ->
            when {
                // ligar exige ciência explícita das regras: desfaz, pergunta e só então liga
                on && !prefs.deleteOriginal -> {
                    deleteSwitch.isChecked = false
                    a.confirm(R.string.delete_enable_title, ctx.getString(R.string.delete_enable_message), R.string.delete_enable_ok) {
                        prefs.deleteOriginal = true
                        prefs.verifyAfter = true
                        deleteSwitch.isChecked = true
                        verifySwitch.isChecked = true
                        render()
                    }
                }
                !on && prefs.deleteOriginal -> {
                    prefs.deleteOriginal = false
                    render()
                }
            }
        }
        val delete = deleteSwitch
        confirmSwitch = Ui.switchRow(ctx, ctx.getString(R.string.option_confirm_delete), prefs.confirmDelete, "confirm-delete") { prefs.confirmDelete = it }
        chdOpsSwitch = Ui.switchRow(ctx, ctx.getString(R.string.option_delete_chd_ops), prefs.deleteAfterChdOps, "delete-chd") { prefs.deleteAfterChdOps = it }
        conflictBox = LinearLayout(ctx)
        val organize = Ui.switchRow(ctx, ctx.getString(R.string.option_organize), prefs.organizeByConsole, "organize") { prefs.organizeByConsole = it }
        val m3u = Ui.switchRow(ctx, ctx.getString(R.string.option_m3u), prefs.writeM3u, "m3u") { prefs.writeM3u = it }
        section(R.string.set_files, delete, confirmSwitch, chdOpsSwitch,
            Ui.text(ctx, ctx.getString(R.string.delete_rules), 11f, Ui.SUBTLE),
            Ui.label(ctx, ctx.getString(R.string.set_conflict)), conflictBox, organize, m3u)

        cpuBox = LinearLayout(ctx)
        cpuHint = Ui.text(ctx, "", 12f, Ui.SUBTLE)
        advancedToggle = Ui.smallButton(ctx, "", Ui.Style.TEXT) { prefs.advancedOpen = !prefs.advancedOpen; render() }.apply {
            tag = "advanced"; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        threadsButton = Ui.smallButton(ctx, "", Ui.Style.OUTLINED) { cycleThreads() }.apply { tag = "threads"; gravity = Gravity.CENTER_VERTICAL or Gravity.START }
        dvdHunkButton = Ui.smallButton(ctx, "", Ui.Style.OUTLINED) { cycleHunk(DiscMode.DVD) }.apply { tag = "hunk-dvd"; gravity = Gravity.CENTER_VERTICAL or Gravity.START }
        cdHunkButton = Ui.smallButton(ctx, "", Ui.Style.OUTLINED) { cycleHunk(DiscMode.CD) }.apply { tag = "hunk-cd"; gravity = Gravity.CENTER_VERTICAL or Gravity.START }
        advancedBox = Ui.column(ctx, 6).apply {
            addView(threadsButton, Ui.lp()); addView(dvdHunkButton, Ui.lp()); addView(cdHunkButton, Ui.lp())
            addView(Ui.text(ctx, ctx.getString(R.string.advanced_note), 11f, Ui.SUBTLE))
        }
        section(R.string.set_performance, cpuBox, cpuHint, advancedToggle, advancedBox)

        section(R.string.set_about, Ui.text(ctx, ctx.getString(R.string.about_text, BuildConfig.VERSION_NAME, NativeChd.coreVersion()), 12f, Ui.SUBTLE))
        render()
        return col
    }

    override fun refresh() {
        if (!::destButton.isInitialized) return
        val tree = prefs.destinationTree
        destButton.text = if (tree != null && Storage.hasTreePermission(a, tree))
            a.getString(R.string.dest_label, Storage.treeDisplayName(a.contentResolver, tree))
        else a.getString(R.string.dest_choose)
    }

    private fun render() {
        if (!::profileBox.isInitialized) return
        refresh()
        val ctx = a
        fun segmented(box: LinearLayout, labels: List<String>, selected: Int, prefix: String, onSelect: (Int) -> Unit) {
            val focused = box.findFocus()?.tag
            box.removeAllViews()
            box.addView(Ui.segmented(ctx, labels, selected, prefix, onSelect), Ui.lp())
            if (focused != null) box.findViewWithTag<View>(focused)?.requestFocus()
        }
        val p = prefs.profile
        segmented(profileBox, listOf(ctx.getString(R.string.profile_fast), ctx.getString(R.string.profile_balanced), ctx.getString(R.string.profile_max)),
            p.ordinal, "profile-") { prefs.profile = Profile.values()[it]; render() }
        val dvd = CompressionParams.of(p, DiscMode.DVD, prefs.dvdHunk, prefs.cdHunk).describe(DiscMode.DVD)
        val cd = CompressionParams.of(p, DiscMode.CD, prefs.dvdHunk, prefs.cdHunk).describe(DiscMode.CD)
        profileHint.text = ctx.getString(when (p) {
            Profile.FAST -> R.string.profile_fast_hint
            Profile.BALANCED -> R.string.profile_balanced_hint
            Profile.MAX -> R.string.profile_max_hint
        }) + "\n" + ctx.getString(R.string.profile_params, dvd, cd)

        val m = prefs.cpuMode
        segmented(cpuBox, listOf(ctx.getString(R.string.cpu_economy), ctx.getString(R.string.cpu_balanced), ctx.getString(R.string.cpu_performance)),
            m.ordinal, "cpu-") { prefs.cpuMode = CpuMode.values()[it]; render() }
        cpuHint.text = ctx.getString(when (m) {
            CpuMode.ECONOMY -> R.string.cpu_economy_hint
            CpuMode.BALANCED -> R.string.cpu_balanced_hint
            CpuMode.PERFORMANCE -> R.string.cpu_performance_hint
        }, CpuInfo.cores, maxOf(1, CpuInfo.cores / 2))

        segmented(conflictBox, listOf(ctx.getString(R.string.conflict_rename), ctx.getString(R.string.conflict_skip)),
            if (prefs.skipExisting) 1 else 0, "conflict-") { prefs.skipExisting = it == 1; render() }

        val deleting = prefs.deleteOriginal
        confirmSwitch.isEnabled = deleting
        chdOpsSwitch.isEnabled = deleting

        val t = prefs.threads
        val clusters = CpuInfo.describeClusters()
        threadsButton.text = if (t <= 0) {
            if (clusters.isEmpty()) ctx.getString(R.string.threads_auto, CpuInfo.cores) else ctx.getString(R.string.threads_auto_clusters, CpuInfo.cores, clusters)
        } else ctx.resources.getQuantityString(R.plurals.threads_n, t, t)
        dvdHunkButton.text = ctx.getString(R.string.hunk_dvd, hunkLabel(DiscMode.DVD, prefs.dvdHunk))
        cdHunkButton.text = ctx.getString(R.string.hunk_cd, hunkLabel(DiscMode.CD, prefs.cdHunk))
        val open = prefs.advancedOpen
        advancedBox.visibility = if (open) View.VISIBLE else View.GONE
        advancedToggle.text = ctx.getString(if (open) R.string.advanced_hide else R.string.advanced_show)
    }

    private fun hunkLabel(mode: DiscMode, bytes: Int): String = when {
        bytes <= 0 -> a.getString(R.string.hunk_profile)
        mode == DiscMode.CD -> a.getString(R.string.hunk_frames, bytes / CompressionParams.CD_FRAME)
        else -> "${bytes / 1024} KiB"
    }

    private fun cycleThreads() {
        val options = listOf(0) + (1..CpuInfo.cores)
        val idx = options.indexOf(prefs.threads).let { if (it < 0) 0 else it }
        prefs.threads = options[(idx + 1) % options.size]
        render()
    }

    private fun cycleHunk(mode: DiscMode) {
        val list = if (mode == DiscMode.DVD) CompressionParams.DVD_HUNKS else CompressionParams.CD_HUNKS
        val cur = if (mode == DiscMode.DVD) prefs.dvdHunk else prefs.cdHunk
        val next = list[(list.indexOf(cur).coerceAtLeast(0) + 1) % list.size]
        if (mode == DiscMode.DVD) prefs.dvdHunk = next else prefs.cdHunk = next
        render()
    }
}
