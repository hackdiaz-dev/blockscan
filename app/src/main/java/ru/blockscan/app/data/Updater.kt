package ru.blockscan.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.blockscan.app.BuildConfig
import ru.blockscan.app.domain.ReleaseInfo
import ru.blockscan.app.domain.UpdateLogic
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, INSTALLING, FAILED }

data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val release: ReleaseInfo? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val checkedAt: Long? = null,
)

/**
 * Обновление приложения с GitHub Releases: проверка, скачивание, сверка SHA-256 и подписи, установка
 * через системный PackageInstaller. Обращается только к api.github.com и github.com.
 */
class Updater(private val context: Context, private val settings: SettingsStore) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var apkFile: File? = null
    private var receiverRegistered = false

    /** Проверка при запуске: не чаще раза в 12 часов и только если включена в настройках. */
    fun autoCheckIfDue(now: Long = System.currentTimeMillis()) {
        if (!settings.current.autoUpdateCheck) return
        if (now - prefs.getLong("last", 0L) < CHECK_INTERVAL_MS) return
        check()
    }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState(UpdateStatus.CHECKING)
            val now = System.currentTimeMillis()
            try {
                val body = fetchText(UpdateLogic.LATEST_API, "application/vnd.github+json")
                prefs.edit().putLong("last", now).apply()
                val rel = UpdateLogic.parseRelease(body)
                _state.value = when {
                    rel == null -> UpdateState(
                        UpdateStatus.FAILED, checkedAt = now,
                        message = "Не удалось разобрать ответ GitHub или в релизе нет APK",
                    )
                    UpdateLogic.isNewer(BuildConfig.VERSION_NAME, rel.version) ->
                        UpdateState(UpdateStatus.AVAILABLE, release = rel, checkedAt = now)
                    else -> UpdateState(UpdateStatus.UP_TO_DATE, release = rel, checkedAt = now)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val m = e.message.orEmpty()
                val text = if (m.startsWith("HTTP 403") || m.startsWith("HTTP 429")) {
                    "GitHub ограничил число запросов, попробуйте позже"
                } else {
                    "Не удалось связаться с GitHub" + (if (m.isNotEmpty()) " ($m)" else "")
                }
                _state.value = UpdateState(UpdateStatus.FAILED, checkedAt = now, message = text)
            }
        }
    }

    fun download() {
        val rel = _state.value.release ?: return
        val st = _state.value.status
        if (st == UpdateStatus.DOWNLOADING || st == UpdateStatus.INSTALLING || st == UpdateStatus.CHECKING) return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState(UpdateStatus.DOWNLOADING, release = rel)
            try {
                val dir = File(context.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, rel.apkName)
                val expected = rel.digest
                    ?: rel.sha256Url?.let { UpdateLogic.parseSha256(fetchText(it, "text/plain")) }
                    ?: throw IOException("в релизе нет контрольной суммы")
                val actual = downloadTo(file, rel)
                if (!actual.equals(expected, ignoreCase = true)) {
                    file.delete()
                    throw IOException("контрольная сумма не совпала")
                }
                signatureProblem(file)?.let {
                    file.delete()
                    throw IOException(it)
                }
                apkFile = file
                _state.value = UpdateState(UpdateStatus.READY, release = rel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState(
                    UpdateStatus.FAILED, release = rel,
                    message = "Не удалось скачать обновление: ${e.message}",
                )
            }
        }
    }

    fun install() {
        val rel = _state.value.release
        val file = apkFile
        if (rel == null || file == null || !file.exists()) {
            _state.value = UpdateState(UpdateStatus.FAILED, release = rel, message = "Файл обновления не найден, скачайте его заново")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            _state.value = UpdateState(
                UpdateStatus.READY, release = rel,
                message = "Разрешите установку из этого приложения в настройках и нажмите «Установить» ещё раз",
            )
            return
        }
        scope.launch {
            try {
                _state.value = UpdateState(UpdateStatus.INSTALLING, release = rel, message = "Подтвердите установку в системном окне")
                registerReceiver()
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(context.packageName)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("update.apk", 0, file.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val intent = Intent(ACTION_RESULT).setPackage(context.packageName)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
                }
            } catch (e: Exception) {
                unregisterReceiver()
                _state.value = UpdateState(UpdateStatus.FAILED, release = rel, message = "Не удалось начать установку: ${e.message}")
            }
        }
    }

    private fun fetchText(url: String, accept: String): String {
        val req = Request.Builder().url(url).header("Accept", accept).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    private fun downloadTo(file: File, rel: ReleaseInfo): String {
        val md = MessageDigest.getInstance("SHA-256")
        val req = Request.Builder().url(rel.apkUrl).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: rel.apkSize
            var done = 0L
            var lastPublish = 0L
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val t = System.currentTimeMillis()
                        if (total > 0 && t - lastPublish > 120) {
                            lastPublish = t
                            _state.value = UpdateState(
                                UpdateStatus.DOWNLOADING, release = rel,
                                progress = (done.toFloat() / total).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
        }
        return hex(md.digest())
    }

    /** Заранее сообщает о несовпадении подписи (например, стоит отладочная сборка), чтобы не ждать отказа установщика. */
    @Suppress("DEPRECATION", "InlinedApi")
    private fun signatureProblem(file: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "файл не является корректным APK"
        if (archive.packageName != context.packageName) return "в файле другое приложение (${archive.packageName})"
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return null
        val a = certs(archive)
        val b = certs(installed)
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) {
            return "подпись обновления не совпадает с установленной версией (например, стоит отладочная сборка)"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun certs(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = confirmIntent(intent)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        c.startActivity(confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    unregisterReceiver()
                    _state.value = UpdateState(UpdateStatus.IDLE)
                }
                else -> {
                    unregisterReceiver()
                    val text = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                        "Установка отменена"
                    } else {
                        "Установка не удалась" + (message?.let { ": $it" } ?: "")
                    }
                    _state.value = UpdateState(UpdateStatus.FAILED, release = _state.value.release, message = text)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra(Intent.EXTRA_INTENT)

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    companion object {
        private const val ACTION_RESULT = "ru.blockscan.app.UPDATE_RESULT"
        private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
        private val USER_AGENT = "BlockScan-Android/${BuildConfig.VERSION_NAME} (unofficial client; self-update from GitHub)"
    }
}
