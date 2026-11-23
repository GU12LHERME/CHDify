package com.conversorchd.ui

import android.content.Context
import com.conversorchd.R
import com.conversorchd.core.DiscMode
import com.conversorchd.core.Job
import com.conversorchd.core.JobState
import com.conversorchd.core.Operation
import com.conversorchd.core.Profile

/** Textos derivados do estado (um só lugar para manter a interface consistente). */
object Labels {
    fun operation(ctx: Context, op: Operation): String = ctx.getString(
        when (op) {
            Operation.CONVERT -> R.string.op_convert
            Operation.EXTRACT -> R.string.op_extract
            Operation.RECOMPRESS -> R.string.op_recompress
            Operation.VERIFY -> R.string.op_verify
        },
    )

    fun profile(ctx: Context, p: Profile?): String = ctx.getString(
        when (p) {
            Profile.FAST -> R.string.profile_fast
            Profile.MAX -> R.string.profile_max
            else -> R.string.profile_balanced
        },
    )

    fun mode(ctx: Context, job: Job): String? = when {
        job.analysis?.chdType == "gd" -> "GD-ROM"
        job.mode == DiscMode.CD -> ctx.getString(R.string.mode_cd)
        job.mode == DiscMode.DVD -> ctx.getString(R.string.mode_dvd)
        else -> null
    }

    /** Destino da operação: "→ CHD", "→ ISO", "→ BIN/CUE", "→ GDI". */
    fun target(job: Job): String = when (job.operation) {
        Operation.CONVERT, Operation.RECOMPRESS -> "CHD"
        Operation.EXTRACT -> when (job.analysis?.chdType) {
            "dvd" -> "ISO"
            "gd" -> "GDI"
            "cd" -> "BIN/CUE"
            else -> "?"
        }
        Operation.VERIFY -> "SHA-1"
    }

    /** Estado curto com símbolo (a interface é monocromática). */
    fun state(ctx: Context, job: Job): String = when (job.state) {
        JobState.ANALYZING -> ctx.getString(R.string.state_analyzing)
        JobState.READY -> ctx.getString(R.string.state_ready)
        JobState.BLOCKED -> "✕ " + ctx.getString(R.string.state_blocked)
        JobState.RUNNING -> ctx.getString(R.string.state_running, com.conversorchd.core.Format.percent(job.fraction))
        JobState.VERIFYING -> ctx.getString(R.string.state_verifying, com.conversorchd.core.Format.percent(job.fraction))
        JobState.DONE -> "✓ " + ctx.getString(R.string.state_done_short)
        JobState.FAILED -> "✕ " + ctx.getString(R.string.state_failed)
        JobState.CANCELLED -> "– " + ctx.getString(R.string.state_cancelled)
        JobState.INTERRUPTED -> "! " + ctx.getString(R.string.state_interrupted)
        JobState.SKIPPED -> "– " + ctx.getString(R.string.state_skipped)
    }
}
