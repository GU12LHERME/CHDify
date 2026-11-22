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
}
