package ru.blockscan.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.blockscan.app.App
import ru.blockscan.app.MainActivity
import ru.blockscan.app.domain.EngineState
import ru.blockscan.app.domain.RunStatus

/**
 * Foreground-сервис держит процесс живым во время сканирования и показывает уведомление
 * с прогрессом и кнопками «Пауза / Продолжить / Стоп». Сами проверки выполняет ScanEngine из AppContainer.
 */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as App).container.engine
        val jobId = intent?.getLongExtra(EXTRA_JOB, -1L) ?: -1L
        ensureChannel()
        when (intent?.action) {
            ACTION_START -> {
                goForeground(notification(engine.state.value, jobId, starting = true))
                if (engine.start(jobId)) watch(jobId) else stopSelf(startId)
            }
            ACTION_PAUSE -> engine.pause(jobId)
            ACTION_RESUME -> engine.resume(jobId)
            ACTION_STOP -> engine.stop(jobId)
            else -> if (!foreground) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun watch(jobId: Long) {
        watcher?.cancel()
        val engine = (application as App).container.engine
        watcher = scope.launch {
            engine.state.collect { st ->
                if (st.jobId != jobId) return@collect
                when (st.status) {
                    RunStatus.RUNNING, RunStatus.PAUSED -> notify(notification(st, jobId, starting = false))
                    RunStatus.FINISHED, RunStatus.STOPPED, RunStatus.IDLE -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        foreground = false
                        notifyFinal(st)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        foreground = true
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notify(n: Notification) = nm().notify(NOTIF_ID, n)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            nm().createNotificationChannel(
                NotificationChannel(CHANNEL, "Сканирование подсетей", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun action(label: String, action: String, jobId: Long, code: Int): NotificationCompat.Action {
        val i = Intent(this, ScanService::class.java).setAction(action).putExtra(EXTRA_JOB, jobId)
        val pi = PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(st: EngineState, jobId: Long, starting: Boolean): Notification {
        val c = st.counts
        val paused = st.status == RunStatus.PAUSED
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Сканирование " + st.title.ifEmpty { "подсети" })
            .setContentText(
                if (starting) "Запуск…" else
                    "Проверено ${c.finished} из ${c.total} • блок: ${c.blocked}" + if (paused) " • пауза" else "",
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setProgress(c.total.coerceAtLeast(1), c.finished, starting || c.total == 0)
        if (paused) b.addAction(action("Продолжить", ACTION_RESUME, jobId, 2)) else b.addAction(action("Пауза", ACTION_PAUSE, jobId, 1))
        b.addAction(action("Стоп", ACTION_STOP, jobId, 3))
        return b.build()
    }

    private fun notifyFinal(st: EngineState) {
        val c = st.counts
        val title = if (st.status == RunStatus.FINISHED) "Сканирование завершено" else "Сканирование остановлено"
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText("${st.title}: проверено ${c.finished} из ${c.total}, заблокировано ${c.blocked}, ошибок ${c.errors}")
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        nm().notify(NOTIF_ID + 1, n)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ru.blockscan.app.START"
        const val ACTION_PAUSE = "ru.blockscan.app.PAUSE"
        const val ACTION_RESUME = "ru.blockscan.app.RESUME"
        const val ACTION_STOP = "ru.blockscan.app.STOP"
        const val EXTRA_JOB = "job"
        private const val CHANNEL = "scan"
        private const val NOTIF_ID = 1001

        fun start(context: Context, jobId: Long) {
            val i = Intent(context, ScanService::class.java).setAction(ACTION_START).putExtra(EXTRA_JOB, jobId)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
