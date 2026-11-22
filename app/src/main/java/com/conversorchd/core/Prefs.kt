package com.conversorchd.core

import android.content.Context
import android.net.Uri

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
