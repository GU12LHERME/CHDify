package com.conversorchd.core

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Modo de criação do CHD, equivalente aos comandos do chdman. */
enum class DiscMode { CD, DVD }

/** Tipo de entrada escolhida pelo usuário. */
enum class SourceKind { IMAGE, CUE, GDI, CHD }

/** Operação de um item da fila. */
enum class Operation {
    CONVERT,     // ISO/CUE/GDI -> CHD
    EXTRACT,     // CHD -> ISO / BIN+CUE / GDI
    RECOMPRESS,  // CHD -> CHD com outro perfil
    VERIFY,      // só verificação de integridade
}

enum class JobState { ANALYZING, READY, BLOCKED, RUNNING, VERIFYING, DONE, FAILED, CANCELLED, INTERRUPTED, SKIPPED }

/** Situação da exclusão do original de um item concluído. */
enum class DeleteState {
    NONE,            // opção desligada ou não se aplica
    PENDING_CONFIRM, // aguardando confirmação do usuário
    DELETED,         // originais apagados
    KEPT,            // mantidos por segurança (ver deleteNote)
    FAILED,          // tentativa falhou (permissão, provedor); nada foi perdido
}

/** Arquivo físico de entrada (via SAF). */
data class InputFile(val uri: Uri, val name: String, val size: Long) {
    fun toJson(): JSONObject = JSONObject().put("uri", uri.toString()).put("name", name).put("size", size)

    companion object {
        fun fromJson(o: JSONObject) = InputFile(Uri.parse(o.getString("uri")), o.getString("name"), o.optLong("size", -1))
    }
}

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
        fun error(code: Int, message: String) = NativeResult(code, message, 0, 0, "", "", "", 0)

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
            error(ResultCodes.INTERNAL_ERROR, "resposta inválida do núcleo: $json")
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

/** Informações de um CHD (chdInfo nativo). */
data class ChdInfo(
    val ok: Boolean,
    val error: String,
    val version: Int,
    val type: String,          // "dvd", "cd", "gd" ou "raw"
    val tracks: Int,
    val logicalBytes: Long,
    val fileBytes: Long,
    val hunkBytes: Int,
    val unitBytes: Int,
    val compression: String,
    val sha1: String,
    val rawSha1: String,
    val metadata: List<String>,
) {
    companion object {
        fun parse(json: String): ChdInfo {
            val o = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
            val meta = o.optJSONArray("metadata")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            return ChdInfo(
                ok = o.optBoolean("ok", false),
                error = o.optString("error"),
                version = o.optInt("version"),
                type = o.optString("type", "raw"),
                tracks = o.optInt("tracks"),
                logicalBytes = o.optLong("logicalBytes"),
                fileBytes = o.optLong("fileBytes"),
                hunkBytes = o.optInt("hunkBytes"),
                unitBytes = o.optInt("unitBytes"),
                compression = o.optString("compression"),
                sha1 = o.optString("sha1"),
                rawSha1 = o.optString("rawSha1"),
                metadata = meta,
            )
        }
    }
}

/** Resultado da análise: o que pode ser feito com a entrada e por quê. */
data class Analysis(
    val allowedModes: Set<DiscMode>,
    val recommended: DiscMode?,
    val summary: String,
    val warnings: List<String>,
    val error: String?,
    val tracks: Int = 0,
    val platform: String = "unknown",
    /** Estimativa conservadora do espaço ocupado pela saída (bytes). */
    val outputEstimate: Long = 0,
    /** Tipo do CHD de entrada ("dvd", "cd", "gd"), quando a entrada é um CHD. */
    val chdType: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("allowed", JSONArray(allowedModes.map { it.name }))
        .put("recommended", recommended?.name ?: JSONObject.NULL)
        .put("summary", summary)
        .put("warnings", JSONArray(warnings))
        .put("error", error ?: JSONObject.NULL)
        .put("tracks", tracks)
        .put("platform", platform)
        .put("estimate", outputEstimate)
        .put("chdType", chdType)

    companion object {
        fun fromJson(o: JSONObject): Analysis {
            val allowed = o.optJSONArray("allowed")?.let { a -> (0 until a.length()).map { DiscMode.valueOf(a.getString(it)) }.toSet() } ?: emptySet()
            val warnings = o.optJSONArray("warnings")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            return Analysis(
                allowed,
                o.strOrNull("recommended")?.let { DiscMode.valueOf(it) },
                o.optString("summary"),
                warnings,
                o.strOrNull("error"),
                o.optInt("tracks"),
                o.optString("platform", "unknown"),
                o.optLong("estimate"),
                o.optString("chdType"),
            )
        }
    }
}

/** Texto de um campo JSON, ou null se ausente, nulo ou vazio. */
internal fun JSONObject.strOrNull(k: String): String? =
    if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

/** Um item da fila. Campos mutáveis são lidos pela interface e escritos pela thread do serviço. */
class Job(
    val id: Long,
    val kind: SourceKind,
    val main: InputFile,
    val tracks: List<TrackRef>,
    val operation: Operation = if (kind == SourceKind.CHD) Operation.EXTRACT else Operation.CONVERT,
    /** Pasta (árvore SAF) de onde o item foi adicionado, se veio de "Adicionar pasta". */
    val sourceTree: Uri? = null,
) {
    @Volatile var state: JobState = JobState.ANALYZING
    @Volatile var analysis: Analysis? = null
    @Volatile var mode: DiscMode? = null

    // progresso
    @Volatile var stage: Int = 0               // 0 compressão, 1 verificação, 2 extração
    @Volatile var fraction: Double = 0.0
    @Volatile var ratio: Double = 1.0
    @Volatile var bytesDone: Long = 0
    @Volatile var bytesTotal: Long = 0
    @Volatile var bytesWritten: Long = 0
    @Volatile var speedBps: Double = 0.0
    @Volatile var startedAt: Long = 0          // relógio monotônico (elapsedRealtime)
    @Volatile var durationMs: Long = 0
    @Volatile var profile: Profile? = null

    // resultado
    @Volatile var outputName: String? = null
    @Volatile var outputUri: Uri? = null
    @Volatile var outputFolder: String? = null // subpasta dentro do destino, se houver
    @Volatile var outputBytes: Long = 0
    @Volatile var verified: Boolean = false
    @Volatile var message: String? = null
    @Volatile var deleteState: DeleteState = DeleteState.NONE
    @Volatile var deleteNote: String? = null
    /** Arquivos de entrada que seriam apagados (preenchido quando a exclusão é permitida). */
    @Volatile var deleteCandidates: List<InputFile> = emptyList()
    /** Grupo de playlist M3U (multidisco) a que o item pertence. */
    @Volatile var m3uGroup: String? = null

    /** Tamanho total dos arquivos de entrada. */
    val inputBytes: Long
        get() = if (kind == SourceKind.CUE || kind == SourceKind.GDI) main.size + tracks.sumOf { it.file?.size ?: 0L } else main.size

    val displayName: String get() = main.name

    /** Todos os documentos de entrada (folha + trilhas, ou o arquivo único). */
    val inputFiles: List<InputFile>
        get() = listOf(main) + tracks.mapNotNull { it.file }

    private val logLines = ArrayDeque<String>()

    fun log(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 400) logLines.removeFirst()
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    val isFinished get() = state == JobState.DONE || state == JobState.FAILED || state == JobState.CANCELLED || state == JobState.SKIPPED
    val isActive get() = state == JobState.RUNNING || state == JobState.VERIFYING

    /** Volta ao estado inicial para ser executado de novo (após falha, cancelamento ou interrupção). */
    fun resetForRetry() {
        state = if (analysis != null && analysis?.error == null) JobState.READY else JobState.BLOCKED
        message = null
        fraction = 0.0
        bytesDone = 0
        bytesWritten = 0
        speedBps = 0.0
        verified = false
        outputUri = null
        outputBytes = 0
        deleteState = DeleteState.NONE
        deleteNote = null
        deleteCandidates = emptyList()
    }

    // ------------------------------------------------------------------ persistência

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("operation", operation.name)
        .put("main", main.toJson())
        .put("tracks", JSONArray(tracks.map { t -> JSONObject().put("name", t.nameInSheet).put("file", t.file?.toJson() ?: JSONObject.NULL) }))
        .put("sourceTree", sourceTree?.toString() ?: JSONObject.NULL)
        .put("state", state.name)
        .put("analysis", analysis?.toJson() ?: JSONObject.NULL)
        .put("mode", mode?.name ?: JSONObject.NULL)
        .put("message", message ?: JSONObject.NULL)
        .put("outputName", outputName ?: JSONObject.NULL)
        .put("outputUri", outputUri?.toString() ?: JSONObject.NULL)
        .put("outputFolder", outputFolder ?: JSONObject.NULL)
        .put("outputBytes", outputBytes)
        .put("verified", verified)
        .put("durationMs", durationMs)
        .put("profile", profile?.name ?: JSONObject.NULL)
        .put("deleteState", deleteState.name)
        .put("deleteNote", deleteNote ?: JSONObject.NULL)
        .put("deleteCandidates", JSONArray(deleteCandidates.map { it.toJson() }))
        .put("m3uGroup", m3uGroup ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Job {
            val tracksJson = o.optJSONArray("tracks") ?: JSONArray()
            val tracks = (0 until tracksJson.length()).map { i ->
                val t = tracksJson.getJSONObject(i)
                TrackRef(t.getString("name"), t.optJSONObject("file")?.let { InputFile.fromJson(it) })
            }
            val job = Job(
                o.getLong("id"),
                SourceKind.valueOf(o.getString("kind")),
                InputFile.fromJson(o.getJSONObject("main")),
                tracks,
                runCatching { Operation.valueOf(o.getString("operation")) }.getOrDefault(Operation.CONVERT),
                o.strOrNull("sourceTree")?.let { Uri.parse(it) },
            )
            job.state = runCatching { JobState.valueOf(o.getString("state")) }.getOrDefault(JobState.ANALYZING)
            job.analysis = o.optJSONObject("analysis")?.let { Analysis.fromJson(it) }
            job.mode = o.strOrNull("mode")?.let { DiscMode.valueOf(it) }
            job.message = o.strOrNull("message")
            job.outputName = o.strOrNull("outputName")
            job.outputUri = o.strOrNull("outputUri")?.let { Uri.parse(it) }
            job.outputFolder = o.strOrNull("outputFolder")
            job.outputBytes = o.optLong("outputBytes")
            job.verified = o.optBoolean("verified")
            job.durationMs = o.optLong("durationMs")
            job.profile = o.strOrNull("profile")?.let { runCatching { Profile.valueOf(it) }.getOrNull() }
            job.deleteState = runCatching { DeleteState.valueOf(o.getString("deleteState")) }.getOrDefault(DeleteState.NONE)
            job.deleteNote = o.strOrNull("deleteNote")
            job.deleteCandidates = o.optJSONArray("deleteCandidates")?.let { a -> (0 until a.length()).map { InputFile.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            job.m3uGroup = o.strOrNull("m3uGroup")
            return job
        }
    }
}
