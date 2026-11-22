package com.conversorchd.core

import java.io.File

/**
 * Topologia da CPU lida do sysfs (sem permissões especiais). Usada só para
 * informar o usuário: o modo automático usa todos os núcleos, porque a fila de
 * trabalho do MAME distribui hunks independentes e os núcleos de desempenho
 * naturalmente processam mais itens que os de eficiência.
 */
object CpuInfo {
    data class Cluster(val maxKHz: Long, val cores: Int)

    val cores: Int = Runtime.getRuntime().availableProcessors()

    val clusters: List<Cluster> by lazy {
        try {
            val freqs = (0 until cores).mapNotNull { i ->
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }
                    ?.readText()?.trim()?.toLongOrNull()
            }
            if (freqs.size != cores) emptyList()
            else freqs.groupingBy { it }.eachCount().map { Cluster(it.key, it.value) }.sortedByDescending { it.maxKHz }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Ex.: "1×3,2 + 3×2,5 + 4×1,8 GHz". Vazio se desconhecido ou homogêneo. */
    fun describeClusters(): String {
        val c = clusters
        if (c.size < 2) return ""
        return c.joinToString(" + ") { String.format(java.util.Locale.forLanguageTag("pt-BR"), "%d×%.1f", it.cores, it.maxKHz / 1e6) } + " GHz"
    }
}
