package com.conversorchd.core

import android.net.Uri
import org.json.JSONObject

/** Modo de criação do CHD, equivalente aos comandos do chdman. */
enum class DiscMode { CD, DVD }

/** Tipo de entrada escolhida pelo usuário. */
enum class SourceKind { IMAGE, CUE, GDI }

enum class JobState { ANALYZING, READY, BLOCKED, RUNNING, VERIFYING, DONE, FAILED, CANCELLED }

/** Arquivo físico de entrada (via SAF). */
data class InputFile(val uri: Uri, val name: String, val size: Long)

/** Arquivo de trilha referenciado por um .cue/.gdi. */
data class TrackRef(val nameInSheet: String, val file: InputFile?)

/** Códigos devolvidos pelo motor nativo (chd_engine.h). */
object ResultCodes {
    const val OK = 0
    const val CANCELLED = 1
    const val INVALID_INPUT = 2
    const val NO_SPACE = 3
    const val IO_ERROR = 4
    const val CHD_ERROR = 5
    const val VERIFY_FAILED = 6
    const val INTERNAL_ERROR = 7
}

data class NativeResult(
    val code: Int,
    val message: String,
    val logicalBytes: Long,
    val outputBytes: Long,
    val compression: String,
    val rawSha1: String,
    val sha1: String,
    val tracks: Int,
) {
    val ok get() = code == ResultCodes.OK

    companion object {
        fun parse(json: String): NativeResult = try {
            val o = JSONObject(json)
            NativeResult(
                code = o.optInt("code", ResultCodes.INTERNAL_ERROR),
                message = o.optString("message"),
                logicalBytes = o.optLong("logicalBytes"),
                outputBytes = o.optLong("outputBytes"),
                compression = o.optString("compression"),
                rawSha1 = o.optString("rawSha1"),
                sha1 = o.optString("sha1"),
                tracks = o.optInt("tracks"),
            )
        } catch (e: Exception) {
            NativeResult(ResultCodes.INTERNAL_ERROR, "resposta inválida do núcleo: $json", 0, 0, "", "", "", 0)
        }
    }
}

/** Fatos sobre a imagem obtidos pelo probe nativo. */
data class ProbeInfo(
    val ok: Boolean,
    val error: String,
    val size: Long,
    val layout: String,
    val sectors: Long,
    val exceedsCd: Boolean,
    val iso9660: Boolean,
    val udf: Boolean,
    val systemId: String,
    val volumeId: String,
    val volumeSectors: Long,
    val truncated: Boolean,
    val platform: String,
    val boot: String,
) {
    companion object {
        fun parse(json: String): ProbeInfo {
            val o = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
            return ProbeInfo(
                ok = o.optBoolean("ok", false),
                error = o.optString("error"),
                size = o.optLong("size"),
                layout = o.optString("layout", "unknown"),
                sectors = o.optLong("sectors"),
                exceedsCd = o.optBoolean("exceedsCd"),
                iso9660 = o.optBoolean("iso9660"),
                udf = o.optBoolean("udf"),
                systemId = o.optString("systemId"),
                volumeId = o.optString("volumeId"),
                volumeSectors = o.optLong("volumeSectors"),
                truncated = o.optBoolean("truncated"),
                platform = o.optString("platform", "unknown"),
                boot = o.optString("boot"),
            )
        }
    }
}

/** Resultado da análise: o que pode ser feito com a imagem e por quê. */
data class Analysis(
    val allowedModes: Set<DiscMode>,
    val recommended: DiscMode?,
    val summary: String,
    val warnings: List<String>,
    val error: String?,
    val tracks: Int = 0,
)

/** Um item da fila de conversão. Campos mutáveis são protegidos pelo JobRepository. */
class Job(
    val id: Long,
    val kind: SourceKind,
    val main: InputFile,
    val tracks: List<TrackRef>,
) {
    @Volatile var state: JobState = JobState.ANALYZING
    @Volatile var analysis: Analysis? = null
    @Volatile var mode: DiscMode? = null

    // progresso
    @Volatile var stage: Int = 0
    @Volatile var fraction: Double = 0.0
    @Volatile var ratio: Double = 1.0
    @Volatile var bytesDone: Long = 0
    @Volatile var bytesTotal: Long = 0
    /** Bytes já gravados no CHD durante a compressão (tamanho comprimido real até o momento). */
    @Volatile var bytesWritten: Long = 0
    @Volatile var profile: Profile? = null
    @Volatile var speedBps: Double = 0.0
    @Volatile var startedAt: Long = 0

    // resultado
    @Volatile var outputName: String? = null
    @Volatile var outputUri: Uri? = null
    @Volatile var outputBytes: Long = 0
    @Volatile var verified: Boolean = false
    @Volatile var message: String? = null

    /** Tamanho total dos arquivos de entrada. */
    val inputBytes: Long
        get() = if (kind == SourceKind.IMAGE) main.size else main.size + tracks.sumOf { it.file?.size ?: 0L }

    val displayName: String get() = main.name

    private val logLines = ArrayDeque<String>()

    fun log(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 400) logLines.removeFirst()
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    val isFinished get() = state == JobState.DONE || state == JobState.FAILED || state == JobState.CANCELLED
    val isActive get() = state == JobState.RUNNING || state == JobState.VERIFYING
}
