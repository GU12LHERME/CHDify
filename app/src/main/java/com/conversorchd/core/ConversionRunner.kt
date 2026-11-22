package com.conversorchd.core

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.conversorchd.R
import com.conversorchd.nativebridge.NativeChd
import com.conversorchd.nativebridge.NativeListener
import org.json.JSONObject

/**
 * Executa um item da fila (bloqueante; chamado pela thread do serviço).
 *
 * Garantias:
 *  - os arquivos de entrada são abertos somente para leitura (original preservado);
 *  - nenhum arquivo existente no destino é sobrescrito;
 *  - em caso de erro ou cancelamento o CHD parcial é apagado;
 *  - com a verificação ativada, o CHD é relido por completo e os SHA-1 conferidos.
 */
class ConversionRunner(private val ctx: Context, private val prefs: Prefs, private val onUpdate: (Job) -> Unit) {

    private val cr = ctx.contentResolver

    fun run(job: Job) {
        val mode = job.mode ?: DiscMode.DVD
        val tree = prefs.destinationTree
        job.startedAt = SystemClock.elapsedRealtime()
        job.stageStartedAt = job.startedAt
        job.fraction = 0.0
        job.bytesDone = 0
        job.speedBps = 0.0
        job.verified = false
        job.message = null
        job.state = JobState.RUNNING
        job.log("Início: ${job.displayName} (modo ${mode.name}, núcleo ${NativeChd.coreVersion()})")
        onUpdate(job)

        if (tree == null || !Storage.hasTreePermission(ctx, tree)) {
            finish(job, JobState.FAILED, ctx.getString(R.string.err_no_destination))
            return
        }

        val profile = prefs.profile
        val params = CompressionParams.of(profile, mode, prefs.dvdHunk, prefs.cdHunk)
        job.profile = profile
        job.bytesWritten = 0
        job.log("Perfil: ${profile.name} — ${params.describe(mode)}; threads: ${if (prefs.threads > 0) prefs.threads else "auto"}")

        var outUri: Uri? = null
        var pendingKey: String? = null
        var success = false
        try {
            val output = Storage.createPartialDocument(cr, tree, Storage.baseName(job.displayName), "chd")
            val uri = output.uri
            outUri = uri
            pendingKey = uri.toString()
            prefs.addPendingOutput(uri.toString())
            job.outputUri = uri
            job.outputName = output.finalName
            job.log("Destino: ${output.currentName}${if (output.partial) " (renomeado para ${output.finalName} ao concluir)" else ""}")

            val result = cr.openFileDescriptor(uri, "rw").use { outPfd ->
                outPfd ?: throw IllegalStateException(ctx.getString(R.string.err_create_output))
                val free = Storage.freeSpace(outPfd)
                if (free in 0 until MIN_FREE_BYTES) {
                    return@use NativeResult(ResultCodes.NO_SPACE, "free=$free", 0, 0, "", "", "", 0)
                }
                if (free in 0 until job.inputBytes) {
                    job.log("Aviso: espaço livre (${Format.bytes(free)}) menor que a entrada (${Format.bytes(job.inputBytes)})")
                }
                val listener = ProgressListener(job)
                when (mode) {
                    DiscMode.DVD -> {
                        cr.openFileDescriptor(job.main.uri, "r").use { inPfd ->
                            inPfd ?: throw IllegalStateException(ctx.getString(R.string.err_open))
                            val size = Storage.regularFileSize(inPfd)
                            if (size < 0) throw IllegalStateException(ctx.getString(R.string.err_not_regular))
                            NativeResult.parse(NativeChd.convertDvd(inPfd.fd, outPfd.fd, prefs.threads, params.hunkBytes, params.codecs, listener))
                        }
                    }
                    DiscMode.CD -> {
                        WorkDir.prepareCd(ctx, job).use { p ->
                            NativeResult.parse(
                                NativeChd.convertCd(p.tocPath, p.trackPaths, p.trackFds, outPfd.fd, prefs.threads, params.hunkBytes, params.codecs, listener),
                            )
                        }
                    }
                }
            }

            job.log("Resultado da conversão: código ${result.code} ${result.message}".trim())
            if (!result.ok) {
                finish(job, stateFor(result.code), messageFor(result))
                return
            }
            job.outputBytes = result.outputBytes
            job.ratio = if (result.logicalBytes > 0) result.outputBytes.toDouble() / result.logicalBytes else 1.0
            job.log("SHA-1 dos dados: ${result.rawSha1}")
            job.log("SHA-1 do CHD: ${result.sha1}")

            if (prefs.verifyAfter) {
                if (JobRepository.stopRequested) {
                    finish(job, JobState.CANCELLED, ctx.getString(R.string.msg_cancelled))
                    return
                }
                job.state = JobState.VERIFYING
                job.stage = 1
                job.fraction = 0.0
                job.bytesDone = 0
                job.speedBps = 0.0
                job.stageStartedAt = SystemClock.elapsedRealtime()
                onUpdate(job)
                val v = cr.openFileDescriptor(uri, "r").use { pfd ->
                    pfd ?: throw IllegalStateException(ctx.getString(R.string.err_open))
                    NativeResult.parse(NativeChd.verify(pfd.fd, prefs.threads, ProgressListener(job)))
                }
                job.log("Verificação: código ${v.code} ${v.message}".trim())
                if (!v.ok) {
                    finish(job, stateFor(v.code), messageFor(v))
                    return
                }
                if (v.rawSha1 != result.rawSha1) {
                    finish(job, JobState.FAILED, ctx.getString(R.string.err_verify, "SHA-1 ${v.rawSha1} ≠ ${result.rawSha1}"))
                    return
                }
                job.verified = true
            }

            // conversão concluída: dá o nome definitivo ao arquivo
            var finalUri = uri
            if (output.partial) {
                val renamed = Storage.rename(cr, uri, output.finalName)
                if (renamed != null) {
                    finalUri = renamed
                    job.outputName = Storage.describe(cr, renamed)?.name ?: output.finalName
                    job.log("Renomeado para ${job.outputName}")
                } else {
                    job.outputName = output.currentName
                    job.log("Aviso: não foi possível renomear; o arquivo ficou como ${output.currentName}")
                }
            }
            job.outputUri = finalUri
            outUri = finalUri

            // tamanho final e tipo, lidos do próprio CHD
            cr.openFileDescriptor(finalUri, "r")?.use { pfd ->
                val info = JSONObject(NativeChd.chdInfo(pfd.fd))
                if (info.optBoolean("ok")) {
                    job.outputBytes = info.optLong("fileBytes", job.outputBytes)
                    job.log("CHD v${info.optInt("version")} ${info.optString("type")}, ${info.optString("compression")}")
                }
            }
            success = true
            finish(job, JobState.DONE, null)
        } catch (e: Exception) {
            job.log("Exceção: ${e.javaClass.simpleName}: ${e.message}")
            finish(job, JobState.FAILED, ctx.getString(R.string.err_generic, e.message ?: e.javaClass.simpleName))
        } finally {
            if (!success && outUri != null) {
                val deleted = Storage.deleteDocument(cr, outUri)
                job.log(if (deleted) "Arquivo parcial removido" else "Não foi possível remover o arquivo parcial")
                job.outputUri = null
            }
            pendingKey?.let { prefs.removePendingOutput(it) }
        }
    }

    private fun finish(job: Job, state: JobState, message: String?) {
        job.state = state
        job.message = message
        job.finishedAt = SystemClock.elapsedRealtime()
        job.log("Fim: ${state.name}${if (message != null) " — $message" else ""}")
        onUpdate(job)
    }

    private fun stateFor(code: Int) = if (code == ResultCodes.CANCELLED) JobState.CANCELLED else JobState.FAILED

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
                job.stageStartedAt = now
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
            if (stage == 0) job.bytesWritten = written
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
