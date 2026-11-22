package com.conversorchd.core

import java.util.Locale

/** Formatação em português brasileiro (vírgula decimal). */
object Format {
    private val BR: Locale = Locale.forLanguageTag("pt-BR")

    fun bytes(b: Long): String {
        if (b < 0) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "$b B" else String.format(BR, if (v >= 100) "%.0f %s" else "%.2f %s", v, units[i])
    }

    fun speed(bps: Double): String = if (bps <= 0.0) "—" else bytes(bps.toLong()) + "/s"

    fun duration(seconds: Long): String {
        if (seconds < 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun percent(f: Double): String = String.format(BR, "%.1f%%", (f * 100).coerceIn(0.0, 100.0))

    fun msf(frames: Long): String {
        val m = frames / (75 * 60)
        val s = (frames / 75) % 60
        val f = frames % 75
        return String.format(Locale.ROOT, "%02d:%02d:%02d", m, s, f)
    }
}
