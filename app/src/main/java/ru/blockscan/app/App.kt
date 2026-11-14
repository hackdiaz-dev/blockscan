package ru.blockscan.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.blockscan.app.data.HttpCheckApi
import ru.blockscan.app.data.SettingsStore
import ru.blockscan.app.data.Updater
import ru.blockscan.app.data.SqliteStore
import ru.blockscan.app.domain.IpChecker
import ru.blockscan.app.domain.ScanEngine
import ru.blockscan.app.domain.StartLimiter
import ru.blockscan.app.service.ScanService

/** Ручная DI: один экземпляр на процесс. Движок сканирования живёт здесь, а не в Activity. */
class AppContainer(val context: Context) {
    val settings = SettingsStore(context)
    val db = SqliteStore(context)
    val api = HttpCheckApi(tokenProvider = { settings.current.apiToken })
    val updater = Updater(context, settings)
    val limiter = StartLimiter()
    val checker = IpChecker(api, limiter, { settings.current }, requireToken = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = ScanEngine(checker, db, db, { settings.current }, scope)

    fun startScanService(jobId: Long) = ScanService.start(context, jobId)
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(Dispatchers.IO).launch { container.engine.recoverInterrupted() }
        container.updater.autoCheckIfDue()
    }
}
