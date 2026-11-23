package com.conversorchd.core

import android.content.Context
import android.net.Uri

/**
 * Modo de uso da CPU. A fila do MAME distribui hunks independentes entre as
 * threads; menos threads significam menos calor e consumo, com conversão mais lenta.
 */
enum class CpuMode {
    ECONOMY,      // metade dos núcleos, prioridade baixa
    BALANCED,     // todos os núcleos, prioridade levemente reduzida (padrão)
    PERFORMANCE;  // todos os núcleos, prioridade normal

    fun threads(): Int = when (this) {
        ECONOMY -> maxOf(1, CpuInfo.cores / 2)
        BALANCED, PERFORMANCE -> 0 // 0 = todos os núcleos (o motor usa núcleos + 1 internamente)
    }

    /** Prioridade (nice) da thread de conversão; valores < 10 mantêm o grupo de primeiro plano. */
    fun priority(): Int = when (this) {
        ECONOMY -> 8
        BALANCED -> 4
        PERFORMANCE -> 0
    }
}

/** Preferências persistentes do usuário. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("conversor", Context.MODE_PRIVATE)

    var destinationTree: Uri?
        get() = sp.getString("destino", null)?.let { Uri.parse(it) }
        set(v) { sp.edit().putString("destino", v?.toString()).apply() }

    var verifyAfter: Boolean
        get() = sp.getBoolean("verificar", true)
        set(v) { sp.edit().putBoolean("verificar", v).apply() }

    /** 0 = automático (todos os núcleos). */
    var threads: Int
        get() = sp.getInt("threads", 0)
        set(v) { sp.edit().putInt("threads", v).apply() }

    var profile: Profile
        get() = runCatching { Profile.valueOf(sp.getString("perfil", null) ?: "") }.getOrDefault(Profile.BALANCED)
        set(v) { sp.edit().putString("perfil", v.name).apply() }

    /** Opções avançadas: tamanho de hunk forçado (0 = o do perfil). */
    var dvdHunk: Int
        get() = sp.getInt("hunk_dvd", 0)
        set(v) { sp.edit().putInt("hunk_dvd", v).apply() }

    var cdHunk: Int
        get() = sp.getInt("hunk_cd", 0)
        set(v) { sp.edit().putInt("hunk_cd", v).apply() }

    // ---------------------------------------------------------------- arquivos

    /** "Excluir original após converter" (desligado por padrão; exige verificação). */
    var deleteOriginal: Boolean
        get() = sp.getBoolean("excluir_original", false)
        set(v) { sp.edit().putBoolean("excluir_original", v).apply() }

    /** Pedir confirmação antes de apagar (ligado por padrão). */
    var confirmDelete: Boolean
        get() = sp.getBoolean("confirmar_exclusao", true)
        set(v) { sp.edit().putBoolean("confirmar_exclusao", v).apply() }

    /** Autorização explícita para também apagar o CHD de origem em extração/recompactação. */
    var deleteAfterChdOps: Boolean
        get() = sp.getBoolean("excluir_chd_origem", false)
        set(v) { sp.edit().putBoolean("excluir_chd_origem", v).apply() }

    /** Se já existir um arquivo com o mesmo nome no destino: renomear (padrão) ou pular. */
    var skipExisting: Boolean
        get() = sp.getBoolean("pular_existentes", false)
        set(v) { sp.edit().putBoolean("pular_existentes", v).apply() }

    /** Organizar a saída em subpastas por console (ou por tipo de mídia). */
    var organizeByConsole: Boolean
        get() = sp.getBoolean("organizar", false)
        set(v) { sp.edit().putBoolean("organizar", v).apply() }

    /** Criar playlist .m3u no destino para conjuntos multidisco adicionados com sua .m3u. */
    var writeM3u: Boolean
        get() = sp.getBoolean("m3u", true)
        set(v) { sp.edit().putBoolean("m3u", v).apply() }

    var cpuMode: CpuMode
        get() = runCatching { CpuMode.valueOf(sp.getString("cpu", null) ?: "") }.getOrDefault(CpuMode.BALANCED)
        set(v) { sp.edit().putString("cpu", v.name).apply() }

    /** Threads efetivas: valor manual (avançado) ou o do modo de CPU. */
    fun effectiveThreads(): Int = if (threads > 0) threads else cpuMode.threads()

    var advancedOpen: Boolean
        get() = sp.getBoolean("avancado", false)
        set(v) { sp.edit().putBoolean("avancado", v).apply() }

    /**
     * Saídas em andamento. Gravado (commit síncrono) antes de começar a escrever,
     * removido ao terminar. Se o processo for encerrado no meio, o app apaga
     * esses arquivos incompletos na próxima abertura.
     */
    fun addPendingOutput(uri: String) {
        val set = HashSet(sp.getStringSet("pendentes", emptySet()) ?: emptySet())
        set.add(uri)
        sp.edit().putStringSet("pendentes", set).commit()
    }

    fun removePendingOutput(uri: String) {
        val set = HashSet(sp.getStringSet("pendentes", emptySet()) ?: emptySet())
        set.remove(uri)
        sp.edit().putStringSet("pendentes", set).commit()
    }

    fun pendingOutputs(): Set<String> = HashSet(sp.getStringSet("pendentes", emptySet()) ?: emptySet())
}
