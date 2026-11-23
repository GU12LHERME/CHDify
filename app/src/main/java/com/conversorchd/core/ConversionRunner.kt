package com.conversorchd.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.conversorchd.R
import com.conversorchd.nativebridge.NativeChd
import com.conversorchd.nativebridge.NativeListener
import org.json.JSONObject
import java.io.FileNotFoundException

/**
 * Executa um item da fila (bloqueante; chamado pela thread do serviço).
 *
 * Garantias, para todas as operações:
 *  - os arquivos de entrada são abertos somente para leitura;
 *  - nada existente no destino é sobrescrito (renomeia ou pula, conforme o ajuste);
 *  - as saídas são gravadas como "<nome>.parcial" e só recebem o nome final
 *    depois de concluídas e verificadas;
 *  - em erro, cancelamento ou perda de acesso, as saídas parciais são apagadas;
 *  - saídas em andamento ficam registradas e, se o processo for encerrado, são
 *    apagadas na próxima abertura do app.
 */
class ConversionRunner(private val ctx: Context, private val prefs: Prefs, private val onUpdate: (Job) -> Unit) {

    private val cr = ctx.contentResolver

    /** Saídas criadas pela operação atual (apagadas se ela não terminar com sucesso). */
    private val created = ArrayList<Uri>()

    private class Abort(val state: JobState, message: String) : Exception(message)

    fun run(job: Job) {
        job.startedAt = SystemClock.elapsedRealtime()
        job.fraction = 0.0
        job.bytesDone = 0
        job.bytesWritten = 0
        job.speedBps = 0.0
        job.verified = false
        job.message = null
        job.outputUri = null
        job.outputFolder = null
        job.outputBytes = 0
        job.deleteState = DeleteState.NONE
        job.deleteNote = null
        job.deleteCandidates = emptyList()
        job.stage = if (job.operation == Operation.VERIFY) 1 else if (job.operation == Operation.EXTRACT) 2 else 0
        job.state = if (job.operation == Operation.VERIFY) JobState.VERIFYING else JobState.RUNNING
        job.profile = if (job.operation == Operation.CONVERT || job.operation == Operation.RECOMPRESS) prefs.profile else null
        job.log("Início: ${job.displayName} — ${job.operation.name} (núcleo ${NativeChd.coreVersion()})")
        persist(job)

        created.clear()
        var success = false
        try {
            when (job.operation) {
                Operation.CONVERT -> convert(job)
                Operation.EXTRACT -> extract(job)
                Operation.RECOMPRESS -> recompress(job)
                Operation.VERIFY -> verifyOnly(job)
            }
            success = true
            finish(job, JobState.DONE, null)
        } catch (a: Abort) {
            finish(job, a.state, a.message)
        } catch (e: SecurityException) {
            job.log("Permissão: ${e.message}")
            finish(job, JobState.FAILED, ctx.getString(R.string.err_permission_lost))
        } catch (e: FileNotFoundException) {
            job.log("Arquivo: ${e.message}")
            finish(job, JobState.FAILED, ctx.getString(R.string.err_storage_lost, e.message ?: ""))
        } catch (e: Exception) {
            job.log("Exceção: ${e.javaClass.simpleName}: ${e.message}")
            finish(job, JobState.FAILED, ctx.getString(R.string.err_generic, e.message ?: e.javaClass.simpleName))
        } finally {
            if (!success) cleanupCreated(job)
            created.clear()
        }
        if (success) planDeletion(job)
    }

    // ================================================================== operações

    private fun convert(job: Job) {
        val mode = job.mode ?: DiscMode.DVD
        val params = CompressionParams.of(prefs.profile, mode, prefs.dvdHunk, prefs.cdHunk)
        job.log("Perfil: ${prefs.profile.name} — ${params.describe(mode)}; threads: ${threadsLabel()}")
        val folder = outputFolder(job)
        val name = finalName(job, folder, Storage.baseName(job.displayName), "chd")
        val out = createOutput(job, folder, name)
        job.outputUri = out.uri
        job.outputName = out.finalName

        val result = openRw(out.uri) { outPfd ->
            checkFreeSpace(job, outPfd, job.inputBytes)
            val listener = ProgressListener(job)
            when (mode) {
                DiscMode.DVD -> openR(job.main.uri) { inPfd ->
                    if (Storage.regularFileSize(inPfd) < 0) throw Abort(JobState.FAILED, ctx.getString(R.string.err_not_regular))
                    NativeResult.parse(NativeChd.convertDvd(inPfd.fd, outPfd.fd, threads(), params.hunkBytes, params.codecs, listener))
                }
                DiscMode.CD -> WorkDir.prepareCd(ctx, job).use { p ->
                    NativeResult.parse(NativeChd.convertCd(p.tocPath, p.trackPaths, p.trackFds, outPfd.fd, threads(), params.hunkBytes, params.codecs, listener))
                }
            }
        }
        check(job, result, "Conversão")
        job.outputBytes = result.outputBytes
        job.ratio = if (result.logicalBytes > 0) result.outputBytes.toDouble() / result.logicalBytes else 1.0
        job.log("SHA-1 dos dados: ${result.rawSha1}")
        if (prefs.verifyAfter) verifyOutput(job, out.uri, result.rawSha1)
        finalizeChd(job, out)
    }

    private fun extract(job: Job) {
        val type = job.analysis?.chdType ?: ""
        val base = Storage.safeName(Storage.baseName(job.displayName))
        when (type) {
            "dvd" -> {
                val folder = outputFolder(job)
                val name = finalName(job, folder, base, "iso")
                val out = createOutput(job, folder, name)
                val result = openRw(out.uri) { outPfd ->
                    checkFreeSpace(job, outPfd, job.analysis?.outputEstimate ?: 0)
                    openR(job.main.uri) { inPfd -> NativeResult.parse(NativeChd.extractDvd(inPfd.fd, outPfd.fd, threads(), ProgressListener(job))) }
                }
                check(job, result, "Extração")
                // o motor compara o SHA-1 dos dados extraídos com o gravado no CHD
                job.verified = true
                job.log("SHA-1 dos dados extraídos confere com o CHD: ${result.rawSha1}")
                job.outputBytes = result.outputBytes
                val finalUri = renameFinal(job, out)
                job.outputUri = finalUri
            }
            "cd", "gd" -> extractCd(job, base)
            else -> throw Abort(JobState.FAILED, ctx.getString(R.string.err_chd_type_unsupported))
        }
    }

    /** CHD de CD -> BIN/CUE, GD-ROM -> GDI, numa subpasta com o nome do jogo. */
    private fun extractCd(job: Job, base: String) {
        // 1) o CHD é verificado por completo antes (a extração de CD lê quadro a quadro)
        job.state = JobState.VERIFYING
        job.stage = 1
        persist(job)
        val v = openR(job.main.uri) { NativeResult.parse(NativeChd.verify(it.fd, threads(), ProgressListener(job))) }
        job.log("Verificação do CHD de origem: código ${v.code} ${v.message}".trim())
        check(job, v, "Verificação")
        job.verified = true

        // 2) plano: nomes, tamanhos e folha exatamente como o chdman extractcd
        val plan = openR(job.main.uri) { JSONObject(NativeChd.planCdExtract(it.fd, base)) }
        if (!plan.optBoolean("ok")) throw Abort(JobState.FAILED, ctx.getString(R.string.err_invalid, plan.optString("error")))
        val names = plan.getJSONArray("files").let { a -> (0 until a.length()).map { a.getString(it) } }
        val sizes = plan.getJSONArray("sizes").let { a -> (0 until a.length()).map { a.getLong(it) } }
        val sheetName = "$base.${plan.getString("sheetExt")}"
        val sheet = plan.getString("sheet")
        if (plan.optBoolean("subcodeDropped")) job.log("Aviso: o CHD contém subcódigo, que .cue/.gdi não comportam (omitido, como no chdman)")

        // 3) subpasta própria ("Jogo/", "Jogo (2)/"...) para não misturar trilhas de jogos diferentes
        val parent = outputFolder(job)
        val existingDirs = existingDirNames(parent)
        if (prefs.skipExisting && base.lowercase() in existingDirs) throw Abort(JobState.SKIPPED, ctx.getString(R.string.msg_skipped_exists, base))
        var dirName = base
        var n = 2
        while (dirName.lowercase() in existingDirs) dirName = "$base (${n++})"
        // pasta nova, criada por esta operação: se ela falhar, a pasta inteira é removida
        val folder = Storage.newFolder(cr, parent, dirName)
        created.add(folder.docUri)
        prefs.addPendingOutput(folder.docUri.toString())
        job.outputFolder = folder.path
        job.log("Destino: ${folder.path}/ (${names.size + 1} arquivos)")

        val outs = names.map { createOutput(job, folder, it) }
        val sheetOut = createOutput(job, folder, sheetName, "text/plain")
        job.state = JobState.RUNNING
        job.stage = 2
        persist(job)

        val pfds = ArrayList<ParcelFileDescriptor>()
        val result = try {
            for (o in outs) pfds.add(cr.openFileDescriptor(o.uri, "rw") ?: throw FileNotFoundException(o.finalName))
            checkFreeSpace(job, pfds.first(), sizes.sum())
            openR(job.main.uri) { inPfd ->
                NativeResult.parse(NativeChd.extractCd(inPfd.fd, base, pfds.map { it.fd }.toIntArray(), ProgressListener(job)))
            }
        } finally {
            pfds.forEach { runCatching { it.close() } }
        }
        check(job, result, "Extração")
        outs.forEachIndexed { i, o ->
            val sz = Storage.documentSize(cr, o.uri)
            if (sz != sizes[i]) throw Abort(JobState.FAILED, ctx.getString(R.string.err_io, "${o.finalName}: $sz ≠ ${sizes[i]}"))
        }
        if (!Storage.writeText(cr, sheetOut.uri, sheet)) throw Abort(JobState.FAILED, ctx.getString(R.string.err_io, sheetName))
        outs.forEach { renameFinal(job, it) }
        val sheetUri = renameFinal(job, sheetOut)
        created.remove(folder.docUri)
        prefs.removePendingOutput(folder.docUri.toString())
        job.outputUri = sheetUri
        job.outputName = sheetName
        job.outputBytes = sizes.sum() + sheet.toByteArray().size
    }

    private fun recompress(job: Job) {
        val type = job.analysis?.chdType ?: ""
        val mode = if (type == "dvd") DiscMode.DVD else if (type == "cd" || type == "gd") DiscMode.CD else null
        val params = if (mode != null) CompressionParams.of(prefs.profile, mode, prefs.dvdHunk, prefs.cdHunk) else CompressionParams("", 0)
        job.log("Perfil: ${prefs.profile.name} — ${if (mode != null) params.describe(mode) else "padrões do chdman"}; threads: ${threadsLabel()}")
        val folder = outputFolder(job)
        val name = finalName(job, folder, Storage.baseName(job.displayName), "chd")
        val out = createOutput(job, folder, name)
        job.outputUri = out.uri
        job.outputName = out.finalName
        val result = openRw(out.uri) { outPfd ->
            checkFreeSpace(job, outPfd, job.inputBytes)
            openR(job.main.uri) { inPfd ->
                NativeResult.parse(NativeChd.recompress(inPfd.fd, outPfd.fd, threads(), params.hunkBytes, params.codecs, ProgressListener(job)))
            }
        }
        check(job, result, "Recompactação")
        job.outputBytes = result.outputBytes
        job.ratio = if (job.inputBytes > 0) result.outputBytes.toDouble() / job.inputBytes else 1.0
        // a verificação é sempre feita aqui: o SHA-1 dos dados precisa ser o mesmo do CHD original
        verifyOutput(job, out.uri, result.rawSha1)
        val originalSha = openR(job.main.uri) { ChdInfo.parse(NativeChd.chdInfo(it.fd)).rawSha1 }
        if (originalSha.isNotEmpty() && originalSha != result.rawSha1) {
            throw Abort(JobState.FAILED, ctx.getString(R.string.err_verify, "SHA-1 ${result.rawSha1} ≠ $originalSha"))
        }
        finalizeChd(job, out)
    }

    private fun verifyOnly(job: Job) {
        val v = openR(job.main.uri) { NativeResult.parse(NativeChd.verify(it.fd, threads(), ProgressListener(job))) }
        job.log("Verificação: código ${v.code} ${v.message}".trim())
        check(job, v, "Verificação")
        job.verified = true
        job.outputBytes = job.inputBytes
        job.log("SHA-1 dos dados: ${v.rawSha1}")
    }

    // ================================================================== etapas comuns

    private fun verifyOutput(job: Job, uri: Uri, expectedRawSha1: String) {
        if (JobRepository.stopRequested) throw Abort(JobState.CANCELLED, ctx.getString(R.string.msg_cancelled))
        job.state = JobState.VERIFYING
        job.stage = 1
        job.fraction = 0.0
        job.bytesDone = 0
        job.speedBps = 0.0
        persist(job)
        val v = openR(uri) { NativeResult.parse(NativeChd.verify(it.fd, threads(), ProgressListener(job))) }
        job.log("Verificação: código ${v.code} ${v.message}".trim())
        check(job, v, "Verificação")
        if (v.rawSha1 != expectedRawSha1) throw Abort(JobState.FAILED, ctx.getString(R.string.err_verify, "SHA-1 ${v.rawSha1} ≠ $expectedRawSha1"))
        job.verified = true
    }

    /** Renomeia o CHD para o nome final e confere o tamanho gravado. */
    private fun finalizeChd(job: Job, out: Storage.Output) {
        val finalUri = renameFinal(job, out)
        job.outputUri = finalUri
        openR(finalUri) { pfd ->
            val info = ChdInfo.parse(NativeChd.chdInfo(pfd.fd))
            if (!info.ok) throw Abort(JobState.FAILED, ctx.getString(R.string.err_chd_invalid, info.error))
            job.outputBytes = info.fileBytes
            job.log("CHD v${info.version} ${info.type}, ${info.compression}, hunk ${info.hunkBytes}")
        }
    }

    private fun renameFinal(job: Job, out: Storage.Output): Uri {
        if (!out.partial) {
            prefs.removePendingOutput(out.uri.toString())
            created.remove(out.uri)
            return out.uri
        }
        val renamed = Storage.rename(cr, out.uri, out.finalName)
            ?: throw Abort(JobState.FAILED, ctx.getString(R.string.err_rename, out.finalName))
        job.log("Concluído: ${out.finalName}")
        prefs.removePendingOutput(out.uri.toString())
        created.remove(out.uri)
        if (job.outputUri == out.uri || job.operation != Operation.EXTRACT) job.outputName = Storage.describe(cr, renamed)?.name ?: out.finalName
        return renamed
    }

    private fun createOutput(job: Job, folder: Folder, finalName: String, mime: String = "application/octet-stream"): Storage.Output {
        val out = Storage.createPartialDocument(cr, folder, finalName, mime)
        created.add(out.uri)
        prefs.addPendingOutput(out.uri.toString())
        job.log("Criado: ${if (folder.path.isEmpty()) "" else folder.path + "/"}${out.currentName}")
        return out
    }

    private fun cleanupCreated(job: Job) {
        // ordem inversa: arquivos antes da pasta que os contém
        for (u in created.toList().asReversed()) {
            val deleted = Storage.deleteDocument(cr, u)
            job.log(if (deleted) "Arquivo parcial removido" else "Não foi possível remover um arquivo parcial (será removido na próxima abertura)")
            if (deleted) prefs.removePendingOutput(u.toString())
        }
        job.outputUri = null
    }

    /** Pasta de destino (com organização por console, se ligada). */
    private fun outputFolder(job: Job): Folder {
        val tree = prefs.destinationTree
        if (tree == null || !Storage.hasTreePermission(ctx, tree)) throw Abort(JobState.FAILED, ctx.getString(R.string.err_no_destination))
        var folder = Storage.root(tree)
        if (prefs.organizeByConsole) {
            val name = Analyzer.platformName(ctx, job.analysis?.platform ?: "")
                ?: when (job.mode) {
                    DiscMode.CD -> if (job.analysis?.chdType == "gd") "GD-ROM" else "CD"
                    DiscMode.DVD -> "DVD"
                    null -> null
                }
            if (name != null) folder = Storage.childFolder(cr, folder, name)
        }
        job.outputFolder = folder.path.ifEmpty { null }
        return folder
    }

    private fun existingDirNames(folder: Folder): Set<String> {
        val all = Storage.listFiles(cr, folder).map { it.name.lowercase() }.toSet()
        // pastas não aparecem em listFiles; consulta a árvore completa
        return all + dirNames(folder)
    }

    private fun dirNames(folder: Folder): Set<String> = try {
        val uri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(folder.tree, folder.docId)
        cr.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            val s = HashSet<String>()
            while (c.moveToNext()) c.getString(0)?.let { s.add(it.lowercase()) }
            s
        } ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Nome final livre (ou interrompe com "pulado" se o ajuste for não sobrescrever nem renomear). */
    private fun finalName(job: Job, folder: Folder, base: String, ext: String): String {
        val existing = Storage.listFiles(cr, folder).map { it.name.lowercase() }.toSet()
        val wanted = "${Storage.safeName(base)}.$ext"
        if (prefs.skipExisting && wanted.lowercase() in existing) {
            throw Abort(JobState.SKIPPED, ctx.getString(R.string.msg_skipped_exists, wanted))
        }
        val name = Storage.uniqueName(existing, Storage.safeName(base), ext)
        if (name != wanted) job.log("Já existe $wanted no destino; usando $name")
        return name
    }

    private fun checkFreeSpace(job: Job, pfd: ParcelFileDescriptor, estimate: Long) {
        val free = Storage.freeSpace(pfd)
        if (free in 0 until MIN_FREE_BYTES) throw Abort(JobState.FAILED, ctx.getString(R.string.err_no_space))
        if (free in 0 until estimate) {
            job.log("Aviso: espaço livre (${Format.bytes(free)}) menor que a estimativa conservadora (${Format.bytes(estimate)})")
        }
    }

    private fun check(job: Job, r: NativeResult, what: String) {
        job.log("$what: código ${r.code} ${r.message}".trim())
        if (!r.ok) throw Abort(if (r.code == ResultCodes.CANCELLED) JobState.CANCELLED else JobState.FAILED, messageFor(r))
    }

    private inline fun <T> openR(uri: Uri, block: (ParcelFileDescriptor) -> T): T =
        (cr.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.lastPathSegment)).use(block)

    private inline fun <T> openRw(uri: Uri, block: (ParcelFileDescriptor) -> T): T =
        (cr.openFileDescriptor(uri, "rw") ?: throw FileNotFoundException(uri.lastPathSegment)).use(block)

    private fun threads() = prefs.effectiveThreads()

    private fun threadsLabel() = if (threads() > 0) threads().toString() else "auto (${CpuInfo.cores})"

    private fun persist(job: Job) {
        onUpdate(job)
        JobRepository.persist()
    }

    private fun finish(job: Job, state: JobState, message: String?) {
        job.state = state
        job.message = message
        job.durationMs = SystemClock.elapsedRealtime() - job.startedAt
        job.log("Fim: ${state.name}${if (message != null) " — $message" else ""} (${Format.duration(job.durationMs / 1000)})")
        persist(job)
    }

    /** Depois do sucesso: decide (e, sem confirmação, executa) a exclusão do original. */
    private fun planDeletion(job: Job) {
        val plan = Deletion.plan(ctx, job, prefs)
        when {
            plan.allowed && prefs.confirmDelete -> {
                job.deleteCandidates = plan.files
                job.deleteState = DeleteState.PENDING_CONFIRM
                job.log("Exclusão do original aguardando confirmação (${plan.files.size} arquivo(s))")
            }
            plan.allowed -> {
                job.deleteCandidates = plan.files
                Deletion.execute(ctx, job)
            }
            plan.reason != null -> {
                job.deleteState = DeleteState.KEPT
                job.deleteNote = plan.reason
                job.log("Original mantido: ${plan.reason}")
            }
        }
        persist(job)
    }

    private fun messageFor(r: NativeResult): String = when (r.code) {
        ResultCodes.CANCELLED -> ctx.getString(R.string.msg_cancelled)
        ResultCodes.INVALID_INPUT -> ctx.getString(R.string.err_invalid, r.message)
        ResultCodes.NO_SPACE -> ctx.getString(R.string.err_no_space)
        ResultCodes.IO_ERROR -> ctx.getString(R.string.err_io, r.message)
        ResultCodes.CHD_ERROR -> ctx.getString(R.string.err_chd, r.message)
        ResultCodes.VERIFY_FAILED -> ctx.getString(R.string.err_verify, r.message)
        else -> ctx.getString(R.string.err_generic, r.message)
    }

    /** Recebe progresso do núcleo e calcula a velocidade (média móvel exponencial). */
    private inner class ProgressListener(private val job: Job) : NativeListener {
        private var lastBytes = 0L
        private var lastTime = SystemClock.elapsedRealtime()

        override fun onProgress(stage: Int, fraction: Double, ratio: Double, done: Long, total: Long, written: Long) {
            val now = SystemClock.elapsedRealtime()
            if (stage != job.stage) {
                job.stage = stage
                job.speedBps = 0.0
                lastBytes = 0
                lastTime = now
            }
            val dt = (now - lastTime) / 1000.0
            if (dt >= 0.5) {
                val inst = (done - lastBytes) / dt
                job.speedBps = if (job.speedBps <= 0.0) inst else job.speedBps * 0.7 + inst * 0.3
                lastBytes = done
                lastTime = now
            }
            job.fraction = fraction
            if (stage == 0) job.ratio = ratio
            job.bytesDone = done
            job.bytesTotal = total
            if (stage != 1) job.bytesWritten = written
            onUpdate(job)
        }

        override fun onLog(message: String) {
            job.log(message)
        }
    }

    companion object {
        /** Abaixo disso nem tenta: o cabeçalho e o mapa do CHD já não caberiam com folga. */
        private const val MIN_FREE_BYTES = 8L * 1024 * 1024
    }
}
