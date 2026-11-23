package com.conversorchd.core

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Grava um JSON de forma atômica (AtomicFile: arquivo novo + rename), para que
 * um encerramento no meio da gravação nunca deixe o arquivo corrompido.
 */
private fun writeAtomic(file: File, text: String) {
    val af = AtomicFile(file)
    val out = af.startWrite()
    try {
        out.write(text.toByteArray(Charsets.UTF_8))
        af.finishWrite(out)
    } catch (e: Exception) {
        af.failWrite(out)
        throw e
    }
}

private fun readAtomic(file: File): String? = try {
    if (!file.exists() && !File(file.path + ".new").exists() && !File(file.path + ".bak").exists()) null
    else String(AtomicFile(file).readFully(), Charsets.UTF_8)
} catch (e: Exception) {
    null
}

/**
 * Fila persistente: salva os itens (sem o progresso transitório) a cada mudança
 * estrutural ou de estado. Ao abrir o app depois de um encerramento inesperado,
 * itens que estavam em andamento voltam como "Interrompido" e podem ser
 * reiniciados do zero. Não há retomada parcial: o formato CHD é gravado com o
 * mapa de hunks no fim, e um arquivo incompleto não é recuperável com segurança.
 */
object JobStore {
    private const val TAG = "CHDify"

    private fun file(ctx: Context) = File(ctx.filesDir, "fila.json")

    fun save(ctx: Context, jobs: List<Job>, nextId: Long) {
        try {
            val arr = JSONArray()
            jobs.forEach { arr.put(it.toJson()) }
            writeAtomic(file(ctx), JSONObject().put("nextId", nextId).put("jobs", arr).toString())
        } catch (e: Exception) {
            Log.w(TAG, "falha ao salvar a fila", e)
        }
    }

    data class Loaded(val jobs: List<Job>, val nextId: Long)

    fun load(ctx: Context): Loaded {
        val text = readAtomic(file(ctx)) ?: return Loaded(emptyList(), 1)
        return try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("jobs") ?: JSONArray()
            val jobs = (0 until arr.length()).mapNotNull { i ->
                runCatching { Job.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
            jobs.forEach { j ->
                when (j.state) {
                    JobState.RUNNING, JobState.VERIFYING -> {
                        j.state = JobState.INTERRUPTED
                        j.message = null
                        j.log("Interrompido: o app foi encerrado durante a operação")
                    }
                    else -> {}
                }
            }
            Loaded(jobs, maxOf(o.optLong("nextId", 1), (jobs.maxOfOrNull { it.id } ?: 0) + 1))
        } catch (e: Exception) {
            Log.w(TAG, "fila salva ilegível; começando vazia", e)
            Loaded(emptyList(), 1)
        }
    }
}

/** Uma entrada do histórico local (sem caminhos completos nem dados pessoais). */
data class HistoryEntry(
    val name: String,
    val operation: Operation,
    val timestamp: Long,      // System.currentTimeMillis()
    val durationMs: Long,
    val inputBytes: Long,
    val outputBytes: Long,
    val profile: Profile?,
    val result: JobState,
    val verified: Boolean,
    val note: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("op", operation.name).put("ts", timestamp).put("ms", durationMs)
        .put("in", inputBytes).put("out", outputBytes).put("profile", profile?.name ?: JSONObject.NULL)
        .put("result", result.name).put("verified", verified).put("note", note ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            o.getString("name"),
            runCatching { Operation.valueOf(o.getString("op")) }.getOrDefault(Operation.CONVERT),
            o.optLong("ts"), o.optLong("ms"), o.optLong("in"), o.optLong("out"),
            o.strOrNull("profile")?.let { runCatching { Profile.valueOf(it) }.getOrNull() },
            runCatching { JobState.valueOf(o.getString("result")) }.getOrDefault(JobState.DONE),
            o.optBoolean("verified"),
            o.strOrNull("note"),
        )

        fun of(job: Job) = HistoryEntry(
            job.displayName, job.operation, System.currentTimeMillis(), job.durationMs, job.inputBytes,
            job.outputBytes, job.profile, job.state, job.verified, job.message,
        )
    }
}

/** Histórico local (máx. [MAX] entradas, mais recentes primeiro). Funciona offline. */
object HistoryStore {
    private const val MAX = 300
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "historico.json")

    fun all(ctx: Context): List<HistoryEntry> = synchronized(lock) {
        val text = readAtomic(file(ctx)) ?: return emptyList()
        try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { runCatching { HistoryEntry.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, e: HistoryEntry) = synchronized(lock) {
        val list = (listOf(e) + all(ctx)).take(MAX)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { writeAtomic(file(ctx), arr.toString()) }
    }

    fun clear(ctx: Context) = synchronized(lock) {
        AtomicFile(file(ctx)).delete()
    }
}
