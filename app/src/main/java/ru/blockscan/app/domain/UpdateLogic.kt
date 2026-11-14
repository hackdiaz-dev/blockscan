package ru.blockscan.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Сведения о релизе на GitHub, нужные для обновления. */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** SHA-256 из поля digest в ответе GitHub, если есть. */
    val digest: String?,
    /** Ссылка на файл .sha256 из релиза, если есть. */
    val sha256Url: String?,
)

/** Чистая логика обновлений: разбор ответа GitHub, сравнение версий, проверка адресов. Без Android. */
object UpdateLogic {
    const val REPO_URL = "https://github.com/hackdiaz-dev/blockscan"
    const val LATEST_API = "https://api.github.com/repos/hackdiaz-dev/blockscan/releases/latest"

    @Serializable
    internal data class GhAsset(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
        val size: Long = 0,
        val digest: String? = null,
    )

    @Serializable
    internal data class GhRelease(
        @SerialName("tag_name") val tag: String = "",
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val hex64 = Regex("[0-9a-f]{64}")

    /** Загружать можно только с github.com и его CDN (*.githubusercontent.com) по https. */
    fun isTrustedUrl(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme == "https" && (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }

    fun parseRelease(body: String): ReleaseInfo? {
        val r = runCatching { json.decodeFromString<GhRelease>(body) }.getOrNull() ?: return null
        if (r.draft || r.prerelease || r.tag.isBlank()) return null
        val apk = r.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) && isTrustedUrl(it.url) } ?: return null
        val sum = r.assets.firstOrNull { it.name == apk.name + ".sha256" && isTrustedUrl(it.url) }
        val digest = apk.digest?.removePrefix("sha256:")?.trim()?.lowercase()?.takeIf { hex64.matches(it) }
        return ReleaseInfo(
            tag = r.tag,
            version = r.tag.trim().removePrefix("v"),
            title = r.name?.takeIf { it.isNotBlank() } ?: r.tag,
            notes = r.body.orEmpty().trim(),
            pageUrl = r.htmlUrl,
            apkName = apk.name,
            apkUrl = apk.url,
            apkSize = apk.size,
            digest = digest,
            sha256Url = sum?.url,
        )
    }

    private fun parts(v: String): List<Int> {
        val core = v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val p = core.split('.').map { it.toIntOrNull() ?: 0 }
        return p + List(maxOf(0, 3 - p.size)) { 0 }
    }

    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(current: String, latest: String): Boolean = compare(latest, current) > 0

    /** Первое 64-значное шестнадцатеричное число в тексте (формат файлов sha256sum). */
    fun parseSha256(text: String): String? =
        Regex("\\b[0-9a-fA-F]{64}\\b").find(text)?.value?.lowercase()
}
