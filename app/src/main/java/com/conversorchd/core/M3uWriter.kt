package com.conversorchd.core

import android.content.Context
import android.util.Log

/**
 * Playlists de jogos multidisco. Quando uma .m3u é adicionada junto com os
 * discos que ela lista, e todos terminam com sucesso, uma nova .m3u é criada no
 * destino apontando para os arquivos gerados (.chd, ou .cue/.gdi na extração),
 * na mesma ordem. A playlist original nunca é alterada.
 */
object M3uWriter {

    fun writeCompleted(ctx: Context, prefs: Prefs) {
        val groups = JobRepository.completedGroups()
        if (!prefs.writeM3u || groups.isEmpty()) return
        val tree = prefs.destinationTree ?: return
        val cr = ctx.contentResolver
        for (g in groups) {
            val jobs = g.jobIds.mapNotNull { JobRepository.get(it) }
            if (jobs.size != g.jobIds.size || jobs.any { it.outputName == null }) continue
            try {
                val folders = jobs.map { it.outputFolder ?: "" }.distinct()
                // todos na mesma pasta: playlist junto deles; senão, na raiz com caminhos relativos
                val folderPath = if (folders.size == 1) folders[0] else ""
                var folder = Storage.root(tree)
                if (folderPath.isNotEmpty()) for (seg in folderPath.split('/')) folder = Storage.childFolder(cr, folder, seg)
                val lines = jobs.map { j ->
                    val f = j.outputFolder ?: ""
                    if (folderPath.isNotEmpty() || f.isEmpty()) j.outputName!! else "$f/${j.outputName}"
                }
                val existing = Storage.listFiles(cr, folder).map { it.name.lowercase() }.toSet()
                val wanted = "${Storage.safeName(g.name)}.m3u"
                if (prefs.skipExisting && wanted.lowercase() in existing) {
                    jobs.first().log("Playlist $wanted já existe no destino; mantida")
                    continue
                }
                val name = Storage.uniqueName(existing, Storage.safeName(g.name), "m3u")
                val uri = android.provider.DocumentsContract.createDocument(cr, folder.docUri, "application/octet-stream", name) ?: continue
                if (Storage.writeText(cr, uri, lines.joinToString("\n", postfix = "\n"))) {
                    jobs.forEach { it.log("Playlist criada: ${if (folderPath.isEmpty()) "" else "$folderPath/"}$name") }
                } else {
                    Storage.deleteDocument(cr, uri)
                }
            } catch (e: Exception) {
                Log.w("CHDify", "falha ao criar playlist ${g.name}", e)
                jobs.firstOrNull()?.log("Não foi possível criar a playlist ${g.name}: ${e.message}")
            }
        }
        JobRepository.persist()
    }
}
