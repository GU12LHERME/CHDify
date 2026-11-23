package com.conversorchd.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.conversorchd.R
import com.conversorchd.core.ConversionRunner
import com.conversorchd.core.Format
import com.conversorchd.core.HistoryEntry
import com.conversorchd.core.HistoryStore
import com.conversorchd.core.M3uWriter
import com.conversorchd.core.Operation
import com.conversorchd.core.Job
import com.conversorchd.core.JobRepository
import com.conversorchd.core.JobState
import com.conversorchd.core.Prefs
import com.conversorchd.nativebridge.NativeChd
import com.conversorchd.ui.MainActivity

/**
 * Serviço em primeiro plano (tipo dataSync) que processa a fila em sequência.
 * Mantém um wake lock parcial durante a conversão para que a CPU não durma com
 * a tela apagada, e mostra o progresso numa notificação.
 */
class ConversionService : Service() {

    private val stateLock = Any()
    private var worker: Thread? = null
    private var restartRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                requestCancel()
                if (worker == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ITEM -> {
                NativeChd.cancel()
                return START_NOT_STICKY
            }
            else -> startQueue()
        }
        return START_NOT_STICKY
    }

    private fun startQueue() {
        val notification = progressNotification(null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_PROGRESS, notification)
            }
        } catch (e: Exception) {
            // Android 12+: início em segundo plano não permitido. A interface mostra o erro.
            Log.e(TAG, "startForeground falhou", e)
            JobRepository.processing = false
            stopSelf()
            return
        }
        synchronized(stateLock) {
            JobRepository.stopRequested = false
            if (worker == null) JobRepository.startBatch()
            if (worker != null) {
                // a thread atual verá os novos itens; se estiver terminando, recomeça
                restartRequested = true
                return
            }
            JobRepository.processing = true
            acquireWakeLock()
            worker = Thread({ processQueue() }, "conversor-chd").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
        }
    }

    private fun processQueue() {
        // Prioridade um pouco abaixo do normal: a interface continua fluida e as
        // threads de compressão do MAME (criadas a partir desta) herdam o valor.
        // Valores >= 10 (THREAD_PRIORITY_BACKGROUND) moveriam as threads para o
        // grupo de segundo plano do Android, restrito aos núcleos de eficiência.
        val prefs = Prefs(this)
        try {
            android.os.Process.setThreadPriority(prefs.cpuMode.priority())
        } catch (e: Exception) {
        }
        val runner = ConversionRunner(this, prefs) { job -> onJobUpdate(job) }
        var done = 0
        var failed = 0
        try {
            do {
                while (!JobRepository.stopRequested) {
                    val job = JobRepository.nextReady() ?: break
                    if (job.id !in JobRepository.batch) JobRepository.batch = JobRepository.batch + job.id
                    runner.run(job)
                    HistoryStore.add(this, HistoryEntry.of(job))
                    when (job.state) {
                        JobState.DONE -> done++
                        JobState.FAILED -> {
                            failed++
                            notifyFailure(job)
                        }
                        else -> {}
                    }
                    // um erro isolado não interrompe a fila; um cancelamento de item também não
                    M3uWriter.writeCompleted(this, prefs)
                }
                val again = synchronized(stateLock) {
                    if (restartRequested && !JobRepository.stopRequested && JobRepository.hasReady()) {
                        restartRequested = false
                        true
                    } else {
                        worker = null
                        false
                    }
                }
            } while (again)
        } catch (t: Throwable) {
            Log.e(TAG, "erro na fila", t)
            synchronized(stateLock) { worker = null }
        } finally {
            JobRepository.finishBatch()
            showSummary(done, failed)
            synchronized(stateLock) {
                // se um novo início chegou enquanto esta thread terminava, a nova thread assume
                if (worker == null) {
                    JobRepository.processing = false
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun onJobUpdate(job: Job) {
        JobRepository.changed()
        val now = SystemClock.elapsedRealtime()
        val force = job.state != JobState.RUNNING && job.state != JobState.VERIFYING
        if (force || now - lastNotify >= 1000) {
            lastNotify = now
            if (job.isActive) nm.notify(NOTIF_PROGRESS, progressNotification(job))
        }
    }

    private fun requestCancel() {
        JobRepository.stopRequested = true
        NativeChd.cancel()
        JobRepository.changed()
    }

    /** Android 15+: limite de tempo de serviços dataSync. Encerra com segurança. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "tempo limite do serviço em primeiro plano atingido")
        requestCancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ wake lock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CHDify:conversao").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // limite de segurança: 12 h
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
    }

    // ------------------------------------------------------------------ notificações

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(job: Job?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelItem = PendingIntent.getService(
            this, 2, Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL_ITEM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.action_cancel_item), cancelItem,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.action_cancel_queue), cancel,
                ).build(),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (job == null) {
            b.setContentTitle(getString(R.string.notif_starting)).setProgress(0, 0, true)
        } else {
            val title = getString(
                when {
                    job.state == JobState.VERIFYING -> R.string.notif_verifying
                    job.operation == Operation.EXTRACT -> R.string.notif_extracting
                    job.operation == Operation.RECOMPRESS -> R.string.notif_recompressing
                    else -> R.string.notif_converting
                },
                job.displayName,
            )
            val remaining = if (job.speedBps > 0) ((job.bytesTotal - job.bytesDone) / job.speedBps).toLong() else -1L
            val text = getString(
                R.string.notif_progress_line, Format.percent(job.fraction), Format.speed(job.speedBps), Format.duration(remaining),
            )
            b.setContentTitle(title).setContentText(text).setProgress(1000, (job.fraction * 1000).toInt(), false)
        }
        return b.build()
    }

    private fun notifyFailure(job: Job) {
        if (!canNotify()) return
        val n = Notification.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_failed_title, job.displayName))
            .setContentText(job.message ?: "")
            .setStyle(Notification.BigTextStyle().bigText(job.message ?: ""))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_RESULT + 1 + (job.id % 1000).toInt(), n)
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun showSummary(done: Int, failed: Int) {
        if (done == 0 && failed == 0) return
        if (!canNotify()) return
        val n = Notification.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_done_title))
            .setContentText(getString(R.string.notif_done_text, done, failed))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_RESULT, n)
    }

    companion object {
        private const val TAG = "CHDify"
        const val ACTION_START = "com.conversorchd.action.START"
        const val ACTION_CANCEL = "com.conversorchd.action.CANCEL"
        const val ACTION_CANCEL_ITEM = "com.conversorchd.action.CANCEL_ITEM"
        private const val CHANNEL_PROGRESS = "progresso"
        private const val CHANNEL_RESULT = "resultado"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_RESULT = 2

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, ctx.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, ctx.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ConversionService::class.java).setAction(ACTION_START))
        }

        /** Cancela o item atual e para a fila (os demais itens continuam prontos). */
        fun cancel(ctx: Context) {
            JobRepository.stopRequested = true
            NativeChd.cancel()
            if (JobRepository.processing) {
                ctx.startService(Intent(ctx, ConversionService::class.java).setAction(ACTION_CANCEL))
            }
        }

        /** Cancela só o item atual; a fila segue para o próximo. */
        fun cancelCurrent() {
            NativeChd.cancel()
            JobRepository.changed()
        }
    }
}
