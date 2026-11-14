package ru.blockscan.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.blockscan.app.domain.AppSettings
import ru.blockscan.app.domain.ThemeMode

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    val current: AppSettings get() = _flow.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(_flow.value).sanitized()
        prefs.edit()
            .putString("theme", n.theme.name)
            .putLong("delay", n.requestDelayMs)
            .putInt("conc", n.concurrency)
            .putInt("timeout", n.probeTimeoutSec)
            .putInt("retries", n.retries)
            .putInt("perMin", n.probeStartsPerMinute)
            .putBoolean("edges", n.excludeEdges)
            .putBoolean("skipPriv", n.skipDynamicForNonPublic)
            .putBoolean("raw", n.exportRaw)
            .putInt("confirm", n.confirmThreshold)
            .putString("apiToken", n.apiToken)
            .putBoolean("dynamic", n.dynamicColor)
            .apply()
        _flow.value = n
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            requestDelayMs = prefs.getLong("delay", d.requestDelayMs),
            concurrency = prefs.getInt("conc", d.concurrency),
            probeTimeoutSec = prefs.getInt("timeout", d.probeTimeoutSec),
            retries = prefs.getInt("retries", d.retries),
            probeStartsPerMinute = prefs.getInt("perMin", d.probeStartsPerMinute),
            excludeEdges = prefs.getBoolean("edges", d.excludeEdges),
            skipDynamicForNonPublic = prefs.getBoolean("skipPriv", d.skipDynamicForNonPublic),
            exportRaw = prefs.getBoolean("raw", d.exportRaw),
            confirmThreshold = prefs.getInt("confirm", d.confirmThreshold),
            apiToken = prefs.getString("apiToken", d.apiToken) ?: d.apiToken,
            dynamicColor = prefs.getBoolean("dynamic", d.dynamicColor),
        ).sanitized()
    }
}
