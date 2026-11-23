package com.conversorchd

import android.app.Application
import android.net.Uri
import android.util.Log
import com.conversorchd.core.Analyzer
import com.conversorchd.core.JobState
import com.conversorchd.core.Prefs
import com.conversorchd.core.Storage
import com.conversorchd.core.JobRepository
import com.conversorchd.core.WorkDir
import com.conversorchd.service.ConversionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ConversionService.createChannels(this)
        JobRepository.init(this)
        // restos de um processo encerrado no meio de uma conversão: diretório de
        // trabalho e arquivos .chd.parcial que ficaram no destino
        if (!JobRepository.processing) background.execute {
            WorkDir.cleanStale(this)
            JobRepository.snapshot().filter { it.state == JobState.ANALYZING }.forEach { Analyzer.analyzeAndApply(this, it) }
            JobRepository.persist()
            val prefs = Prefs(this)
            for (u in prefs.pendingOutputs()) {
                val deleted = Storage.deleteDocument(contentResolver, Uri.parse(u))
                Log.i("CHDify", "saída incompleta de execução anterior ${if (deleted) "removida" else "não encontrada"}: $u")
                prefs.removePendingOutput(u)
            }
        }
    }

    companion object {
        /** Thread para análise de arquivos (leitura via SAF pode ser lenta). */
        val background: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "chdify-analise").apply { isDaemon = true }
        }
    }
}
