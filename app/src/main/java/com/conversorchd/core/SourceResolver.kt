package com.conversorchd.core

import android.content.Context

/**
 * Agrupa os arquivos escolhidos em itens de conversão:
 *  - cada .cue/.gdi vira um item de CD com seus arquivos de trilha;
 *  - .iso/.img (e .bin avulsos, não referenciados por nenhuma folha) viram itens de imagem;
 *  - o restante é ignorado.
 */
object SourceResolver {

    private val IMAGE_EXT = setOf("iso", "img", "bin")
    private val SHEET_EXT = setOf("cue", "gdi")

    data class Resolved(val jobs: List<Job>, val ignored: List<String>)

    fun resolve(ctx: Context, files: List<InputFile>, nextId: () -> Long): Resolved {
        val byName = HashMap<String, InputFile>()
        files.forEach { byName.putIfAbsent(it.name.lowercase(), it) }
        val used = HashSet<String>()
        val jobs = ArrayList<Job>()
        val ignored = ArrayList<String>()

        val sheets = files.filter { Storage.extension(it.name) in SHEET_EXT }.sortedBy { it.name.lowercase() }
        for (sheet in sheets) {
            val ext = Storage.extension(sheet.name)
            val bytes = Storage.readSmallText(ctx.contentResolver, sheet.uri)
            val names = if (bytes == null) emptyList() else {
                val text = Sheets.decode(bytes)
                if (ext == "cue") Sheets.cueFiles(text) else Sheets.gdiFiles(text)
            }
            val tracks = names.map { n ->
                val f = byName[Sheets.leafName(n).lowercase()]
                if (f != null) used.add(f.name.lowercase())
                TrackRef(n, f)
            }
            used.add(sheet.name.lowercase())
            jobs.add(Job(nextId(), if (ext == "cue") SourceKind.CUE else SourceKind.GDI, sheet, tracks))
        }

        for (f in files.sortedBy { it.name.lowercase() }) {
            val key = f.name.lowercase()
            if (key in used) continue
            when (Storage.extension(f.name)) {
                in IMAGE_EXT -> {
                    used.add(key)
                    jobs.add(Job(nextId(), SourceKind.IMAGE, f, emptyList()))
                }
                in SHEET_EXT -> Unit
                else -> ignored.add(f.name)
            }
        }
        return Resolved(jobs, ignored)
    }
}
