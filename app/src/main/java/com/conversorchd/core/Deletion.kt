package com.conversorchd.core

import android.content.Context
import com.conversorchd.R

/**
 * Exclusão segura do original após uma operação concluída.
 *
 * Só apaga quando TODAS as condições abaixo valem; na dúvida, mantém o original:
 *  1. a opção está ligada (e, para extração/recompactação, também a autorização extra);
 *  2. o item terminou com sucesso E foi verificado (SHA-1 conferido);
 *  3. o arquivo gerado existe com o nome final e o tamanho esperado;
 *  4. nenhum arquivo de entrada é usado por outro item da fila;
 *  5. para .cue/.gdi (vários arquivos): o item veio de "Adicionar pasta", e
 *     nenhuma outra folha (.cue/.gdi) ou playlist (.m3u) dessa pasta referencia
 *     as mesmas trilhas; sem acesso à pasta não há como garantir isso;
 *     (o mesmo vale para CHD de origem, por causa das playlists .m3u);
 *  6. para arquivo único: nenhuma folha ou playlist da pasta de origem
 *     (quando conhecida) o referencia;
 *  7. o provedor de arquivos permite excluir cada documento.
 * Itens com falha, cancelados ou interrompidos nunca chegam aqui.
 */
object Deletion {

    data class Plan(val allowed: Boolean, val files: List<InputFile>, val reason: String?)

    fun plan(ctx: Context, job: Job, prefs: Prefs): Plan {
        val cr = ctx.contentResolver
        fun keep(res: Int, vararg args: Any) = Plan(false, emptyList(), ctx.getString(res, *args))

        val enabled = when (job.operation) {
            Operation.CONVERT -> prefs.deleteOriginal
            Operation.EXTRACT, Operation.RECOMPRESS -> prefs.deleteOriginal && prefs.deleteAfterChdOps
            Operation.VERIFY -> false
        }
        if (!enabled) return Plan(false, emptyList(), null)
        if (job.state != JobState.DONE) return Plan(false, emptyList(), null)
        if (!job.verified) return keep(R.string.keep_not_verified)

        val out = job.outputUri ?: return keep(R.string.keep_output_missing)
        val outSize = Storage.documentSize(cr, out)
        val expected = if (job.operation == Operation.EXTRACT) -1L else job.outputBytes
        if (outSize <= 0 || (expected > 0 && outSize != expected)) return keep(R.string.keep_output_missing)

        val files = job.inputFiles
        for (f in files) {
            val others = JobRepository.jobsReferencing(f.uri, job.id)
            if (others.isNotEmpty()) return keep(R.string.keep_shared_queue, f.name, others.first().displayName)
        }

        val multi = job.kind == SourceKind.CUE || job.kind == SourceKind.GDI
        val tree = job.sourceTree
        // CHDs costumam ser referenciados por playlists .m3u: sem a pasta não há como conferir
        if ((multi || job.kind == SourceKind.CHD) && tree == null) return keep(R.string.keep_need_folder)
        if (tree != null) {
            val ours = files.map { it.uri }.toSet()
            val ourNames = files.map { it.name.lowercase() }.toSet()
            val siblings = Storage.listTree(cr, tree).filter { it.uri !in ours }
            for (s in siblings) {
                val ext = Storage.extension(s.name)
                val refs: List<String> = when (ext) {
                    "cue", "gdi" -> SourceResolver.sheetReferences(ctx, s) ?: return keep(R.string.keep_unreadable_sibling, s.name)
                    "m3u" -> Storage.readSmallText(cr, s.uri)?.let { b ->
                        Sheets.decode(b).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    } ?: return keep(R.string.keep_unreadable_sibling, s.name)
                    else -> continue
                }
                val hit = refs.map { Sheets.leafName(it).lowercase() }.firstOrNull { it in ourNames }
                if (hit != null) return keep(R.string.keep_shared_sibling, hit, s.name)
            }
        }

        for (f in files) {
            if (!Storage.supportsDelete(cr, f.uri)) return keep(R.string.keep_no_permission, f.name)
        }
        return Plan(true, files, null)
    }

    /**
     * Apaga os arquivos planejados (trilhas primeiro, a folha por último).
     * Uma falha no meio não perde dados: a cópia verificada já existe.
     */
    fun execute(ctx: Context, job: Job) {
        val cr = ctx.contentResolver
        val files = job.deleteCandidates
        if (files.isEmpty()) return
        val ordered = files.drop(1) + files.first()
        val failed = ArrayList<String>()
        for (f in ordered) {
            if (Storage.deleteDocument(cr, f.uri)) {
                job.log("Original excluído: ${f.name}")
                Storage.releasePermission(ctx, f.uri)
            } else {
                failed.add(f.name)
                job.log("Não foi possível excluir: ${f.name}")
            }
        }
        if (failed.isEmpty()) {
            job.deleteState = DeleteState.DELETED
            job.deleteNote = ctx.resources.getQuantityString(R.plurals.deleted_files, files.size, files.size)
        } else {
            job.deleteState = DeleteState.FAILED
            job.deleteNote = ctx.getString(R.string.delete_failed, failed.joinToString(", "))
        }
        job.deleteCandidates = emptyList()
    }
}
