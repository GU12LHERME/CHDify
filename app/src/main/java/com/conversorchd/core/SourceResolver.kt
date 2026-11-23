package com.conversorchd.core

import android.content.Context
import android.net.Uri

/**
 * Agrupa os arquivos escolhidos em itens da fila:
 *  - cada .cue/.gdi vira um item de CD com seus arquivos de trilha;
 *  - .iso/.img (e .bin avulsos, não referenciados por nenhuma folha) viram itens de imagem;
 *  - .chd vira um item de extração (o formato de saída é detectado pelo conteúdo);
 *  - .m3u forma um grupo multidisco com os itens que referencia;
 *  - itens já presentes na fila (mesmo documento, ou mesmo nome e tamanho) são ignorados.
 */
object SourceResolver {

    private val IMAGE_EXT = setOf("iso", "img", "bin")
    private val SHEET_EXT = setOf("cue", "gdi")

    data class Resolved(
        val jobs: List<Job>,
        val groups: List<M3uGroup>,
        val ignored: List<String>,
        val duplicates: List<String>,
    )

    /** Arquivos lidos de uma folha (.cue/.gdi). */
    fun sheetReferences(ctx: Context, sheet: InputFile): List<String>? {
        val bytes = Storage.readSmallText(ctx.contentResolver, sheet.uri) ?: return null
        val text = Sheets.decode(bytes)
        return if (Storage.extension(sheet.name) == "cue") Sheets.cueFiles(text) else Sheets.gdiFiles(text)
    }

    fun resolve(
        ctx: Context,
        files: List<InputFile>,
        sourceTree: Uri?,
        isDuplicate: (InputFile, Operation) -> Boolean,
        nextId: () -> Long,
    ): Resolved {
        val byName = HashMap<String, InputFile>()
        files.forEach { byName.putIfAbsent(it.name.lowercase(), it) }
        val used = HashSet<String>()
        val jobs = ArrayList<Job>()
        val ignored = ArrayList<String>()
        val duplicates = ArrayList<String>()

        fun addJob(job: Job) {
            if (isDuplicate(job.main, job.operation) || jobs.any { it.main.uri == job.main.uri }) duplicates.add(job.main.name)
            else jobs.add(job)
        }

        val sheets = files.filter { Storage.extension(it.name) in SHEET_EXT }.sortedBy { it.name.lowercase() }
        for (sheet in sheets) {
            val names = sheetReferences(ctx, sheet) ?: emptyList()
            val tracks = names.map { n ->
                val f = byName[Sheets.leafName(n).lowercase()]
                if (f != null) used.add(f.name.lowercase())
                TrackRef(n, f)
            }
            used.add(sheet.name.lowercase())
            val kind = if (Storage.extension(sheet.name) == "cue") SourceKind.CUE else SourceKind.GDI
            addJob(Job(nextId(), kind, sheet, tracks, Operation.CONVERT, sourceTree))
        }

        val m3us = ArrayList<InputFile>()
        for (f in files.sortedBy { it.name.lowercase() }) {
            val key = f.name.lowercase()
            if (key in used) continue
            when (Storage.extension(f.name)) {
                in IMAGE_EXT -> {
                    used.add(key)
                    addJob(Job(nextId(), SourceKind.IMAGE, f, emptyList(), Operation.CONVERT, sourceTree))
                }
                "chd" -> {
                    used.add(key)
                    addJob(Job(nextId(), SourceKind.CHD, f, emptyList(), Operation.EXTRACT, sourceTree))
                }
                "m3u" -> m3us.add(f)
                in SHEET_EXT -> Unit
                else -> ignored.add(f.name)
            }
        }

        // playlists: cada entrada aponta para uma folha/imagem adicionada agora
        val groups = ArrayList<M3uGroup>()
        for (m in m3us) {
            val text = Storage.readSmallText(ctx.contentResolver, m.uri)?.let { Sheets.decode(it) } ?: continue
            val entries = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val ids = entries.mapNotNull { e -> jobs.firstOrNull { it.main.name.equals(Sheets.leafName(e), ignoreCase = true) }?.id }
            if (ids.size == entries.size && ids.size >= 1) {
                val name = Storage.baseName(m.name)
                groups.add(M3uGroup(name, ids))
                ids.forEach { id -> jobs.first { it.id == id }.m3uGroup = name }
            } else {
                ignored.add(m.name)
            }
        }
        return Resolved(jobs, groups, ignored, duplicates)
    }
}
