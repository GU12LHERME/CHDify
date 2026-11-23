package com.conversorchd.core

import android.content.Context
import com.conversorchd.R
import com.conversorchd.nativebridge.NativeChd
import org.json.JSONObject

/**
 * Valida a entrada e decide quais modos (CD/DVD) são adequados.
 *
 * Regras principais:
 *  - ISO de 2048 bytes/setor sem metadados de trilhas: o modo DVD preserva os
 *    dados integralmente e é o padrão. O modo CD só é oferecido quando a imagem
 *    cabe num CD, e sempre com o aviso de que vira uma única trilha de dados.
 *  - Plataformas cujos discos costumam ter trilhas de áudio ou dados em modo 2
 *    (PS1, Saturn, Sega CD...) exigem o .cue/.bin original para um CHD fiel.
 *  - .cue/.gdi: sempre modo CD, interpretado pelo parser oficial do MAME.
 */
object Analyzer {

    private const val CD_MAX_BYTES = 99L * 60 * 75 * 2048 // 99 minutos

    fun platformName(ctx: Context, p: String): String? = when (p) {
        "ps2" -> "PlayStation 2"
        "ps1" -> "PlayStation"
        "psp" -> "PSP (UMD)"
        "playstation" -> "PlayStation"
        "gamecube" -> "GameCube"
        "wii" -> "Wii"
        "xbox" -> "Xbox"
        "saturn" -> "Sega Saturn"
        "segacd" -> "Sega CD"
        "dreamcast" -> "Dreamcast"
        else -> null
    }

    /** Analisa e aplica o resultado ao item (pronto ou bloqueado, com o motivo no log). */
    fun analyzeAndApply(ctx: Context, job: Job) {
        val a = analyze(ctx, job)
        job.analysis = a
        job.mode = a.recommended
        val usable = a.error == null && (job.operation != Operation.CONVERT || a.recommended != null)
        job.state = if (usable) JobState.READY else JobState.BLOCKED
        job.log("Análise: ${a.summary}")
        a.warnings.forEach { job.log("Aviso: $it") }
        a.error?.let { job.log("Erro: $it") }
    }

    fun analyze(ctx: Context, job: Job): Analysis = try {
        when (job.kind) {
            SourceKind.IMAGE -> analyzeImage(ctx, job).let { it.copy(outputEstimate = job.inputBytes) }
            SourceKind.CUE, SourceKind.GDI -> analyzeSheet(ctx, job).let { it.copy(outputEstimate = job.inputBytes) }
            SourceKind.CHD -> analyzeChd(ctx, job)
        }
    } catch (e: Exception) {
        Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_analysis, e.message ?: e.javaClass.simpleName))
    }

    private fun analyzeImage(ctx: Context, job: Job): Analysis {
        val pfd = ctx.contentResolver.openFileDescriptor(job.main.uri, "r")
            ?: return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_open))
        val probe = pfd.use { p ->
            if (Storage.regularFileSize(p) < 0)
                return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_not_regular))
            ProbeInfo.parse(NativeChd.probeImage(p.fd))
        }
        if (!probe.ok) return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_open))
        if (probe.size <= 0) return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_empty))

        val warnings = ArrayList<String>()
        val parts = ArrayList<String>()
        platformName(ctx, probe.platform)?.let { parts.add(it) }
        when {
            probe.iso9660 && probe.udf -> parts.add("ISO9660 + UDF")
            probe.udf -> parts.add("UDF")
            probe.iso9660 -> parts.add("ISO9660")
        }
        if (probe.volumeId.isNotBlank()) parts.add("“${probe.volumeId}”")

        // --- layout de setores ---
        when (probe.layout) {
            "unknown" -> return Analysis(emptySet(), null, parts.joinToString(" · "), emptyList(), ctx.getString(R.string.err_layout))
            "2352", "2336" -> {
                parts.add(ctx.getString(R.string.info_raw_sectors, probe.layout))
                warnings.add(ctx.getString(R.string.warn_raw_no_cue))
                return Analysis(setOf(DiscMode.CD), DiscMode.CD, parts.joinToString(" · "), warnings, null)
            }
        }

        // --- 2048 bytes/setor ---
        if (probe.truncated) {
            val declared = probe.volumeSectors * 2048
            return Analysis(
                emptySet(), null, parts.joinToString(" · "), emptyList(),
                ctx.getString(R.string.err_truncated, Format.bytes(declared), Format.bytes(probe.size)),
            )
        }
        if (!probe.iso9660 && !probe.udf && probe.platform !in setOf("gamecube", "wii", "xbox")) {
            warnings.add(ctx.getString(R.string.warn_no_fs))
        }
        val fitsCd = !probe.exceedsCd && probe.size <= CD_MAX_BYTES
        val platform = probe.platform
        val cdWarning = ctx.getString(R.string.warn_cd_from_iso)

        return platformAnalysis(probe, parts, warnings, fitsCd, cdWarning, ctx).copy(platform = platform)
    }

    private fun platformAnalysis(
        probe: ProbeInfo,
        parts: ArrayList<String>,
        warnings: ArrayList<String>,
        fitsCd: Boolean,
        cdWarning: String,
        ctx: Context,
    ): Analysis {
        return when (probe.platform) {
            "psp" -> Analysis(setOf(DiscMode.DVD), DiscMode.DVD, parts.joinToString(" · "), warnings + ctx.getString(R.string.info_psp), null)
            "ps2" -> {
                if (fitsCd && probe.size <= 700L * 1024 * 1024) warnings.add(ctx.getString(R.string.warn_ps2_small))
                Analysis(if (fitsCd) setOf(DiscMode.DVD, DiscMode.CD) else setOf(DiscMode.DVD), DiscMode.DVD,
                    parts.joinToString(" · "), warnings, null)
            }
            "ps1", "saturn", "segacd", "dreamcast" -> {
                warnings.add(ctx.getString(R.string.warn_needs_cue, platformName(ctx, probe.platform)))
                Analysis(setOf(DiscMode.CD), DiscMode.CD, parts.joinToString(" · "), warnings, null)
            }
            "gamecube", "wii", "xbox" -> {
                warnings.add(ctx.getString(R.string.warn_no_chd_support, platformName(ctx, probe.platform)))
                Analysis(setOf(DiscMode.DVD), DiscMode.DVD, parts.joinToString(" · "), warnings, null)
            }
            else -> {
                if (!fitsCd) {
                    parts.add(ctx.getString(R.string.info_exceeds_cd))
                    Analysis(setOf(DiscMode.DVD), DiscMode.DVD, parts.joinToString(" · "), warnings, null)
                } else {
                    // sem plataforma identificada: DVD preserva os dados; CD fica disponível com aviso
                    warnings.add(cdWarning)
                    Analysis(setOf(DiscMode.DVD, DiscMode.CD), DiscMode.DVD, parts.joinToString(" · "), warnings, null)
                }
            }
        }
    }

    private fun analyzeSheet(ctx: Context, job: Job): Analysis {
        val missing = job.tracks.filter { it.file == null }.map { it.nameInSheet }
        if (job.tracks.isEmpty())
            return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_sheet_empty))
        if (missing.isNotEmpty())
            return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_missing_tracks, missing.joinToString(", ")))

        WorkDir.prepareCd(ctx, job).use { prepared ->
            val o = JSONObject(NativeChd.probeToc(prepared.tocPath))
            if (!o.optBoolean("ok"))
                return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_sheet_invalid, o.optString("error")))
            val tracks = o.optJSONArray("tracks")
            val n = o.optInt("numTracks")
            var audio = 0
            val types = LinkedHashSet<String>()
            if (tracks != null) for (i in 0 until tracks.length()) {
                val t = tracks.getJSONObject(i)
                if (t.optBoolean("audio")) audio++ else types.add(t.optString("type"))
            }
            val frames = o.optLong("totalFrames")
            val summary = buildString {
                if (o.optBoolean("gdrom")) append("GD-ROM · ")
                append(ctx.resources.getQuantityString(R.plurals.tracks, n, n))
                if (audio > 0) append(" · ").append(ctx.resources.getQuantityString(R.plurals.audio_tracks, audio, audio))
                if (types.isNotEmpty()) append(" · ").append(types.joinToString("/"))
                append(" · ").append(Format.msf(frames))
            }
            return Analysis(setOf(DiscMode.CD), DiscMode.CD, summary, emptyList(), null, n,
                platform = if (o.optBoolean("gdrom")) "dreamcast" else "unknown")
        }
    }

    /**
     * CHD de entrada: lê cabeçalho e metadados (o conteúdo é verificado por
     * completo na própria operação). O tipo define a extração: DVD -> ISO,
     * CD -> BIN/CUE, GD-ROM -> GDI.
     */
    private fun analyzeChd(ctx: Context, job: Job): Analysis {
        val pfd = ctx.contentResolver.openFileDescriptor(job.main.uri, "r")
            ?: return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_open))
        val info = pfd.use { p ->
            if (Storage.regularFileSize(p) < 0)
                return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_not_regular))
            ChdInfo.parse(NativeChd.chdInfo(p.fd))
        }
        if (!info.ok) return Analysis(emptySet(), null, "", emptyList(), ctx.getString(R.string.err_chd_invalid, info.error))
        val typeName = when (info.type) {
            "dvd" -> "DVD"
            "cd" -> "CD"
            "gd" -> "GD-ROM"
            else -> ctx.getString(R.string.chd_type_other)
        }
        val summary = buildString {
            append("CHD v${info.version} · $typeName")
            if (info.tracks > 0) append(" · ").append(ctx.resources.getQuantityString(R.plurals.tracks, info.tracks, info.tracks))
            append(" · ").append(Format.bytes(info.logicalBytes))
            if (info.compression.isNotBlank()) append(" · ").append(info.compression)
        }
        val error = when {
            info.version < 5 && job.operation == Operation.RECOMPRESS -> null // o chdman copy converte versões antigas para v5
            info.type == "raw" && job.operation == Operation.EXTRACT -> ctx.getString(R.string.err_chd_type_unsupported)
            else -> null
        }
        val mode = when (info.type) {
            "dvd" -> DiscMode.DVD
            "cd", "gd" -> DiscMode.CD
            else -> null
        }
        val estimate = when (job.operation) {
            Operation.EXTRACT -> info.logicalBytes
            Operation.RECOMPRESS -> info.fileBytes
            else -> 0L
        }
        return Analysis(
            if (mode != null) setOf(mode) else emptySet(), mode, summary, emptyList(), error, info.tracks,
            platform = if (info.type == "gd") "dreamcast" else "unknown",
            outputEstimate = estimate,
            chdType = info.type,
        )
    }
}
