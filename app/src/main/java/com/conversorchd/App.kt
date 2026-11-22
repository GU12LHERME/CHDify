package com.conversorchd

import android.app.Application
import com.conversorchd.core.JobRepository
import com.conversorchd.core.WorkDir
import com.conversorchd.service.ConversionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ConversionService.createChannels(this)
        // restos de um processo encerrado no meio de uma conversão
        if (!JobRepository.processing) background.execute { WorkDir.cleanStale(this) }
    }

    companion object {
        /** Thread para análise de arquivos (leitura via SAF pode ser lenta). */
        val background: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "conversor-analise").apply { isDaemon = true }
        }
    }
}
