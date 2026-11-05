package ru.blockscan.app.domain

import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Минимальный интервал между запросами /check. */
    val requestDelayMs: Long = 600,
    val concurrency: Int = 1,
    /** Максимальное ожидание ответов сканеров для одного IP. */
    val probeTimeoutSec: Int = 90,
    val retries: Int = 3,
    /** Запусков динамической проверки в минуту (сервис, по данным сторонних клиентов, пускает 5). */
    val probeStartsPerMinute: Int = 4,
    val excludeEdges: Boolean = false,
    val skipDynamicForNonPublic: Boolean = true,
    val exportRaw: Boolean = true,
    val confirmThreshold: Int = 512,
) {
    /** Пределы защищают сервис: настройки не позволяют превысить его лимиты. */
    fun sanitized() = copy(
        requestDelayMs = requestDelayMs.coerceIn(300L, 60_000L),
        concurrency = concurrency.coerceIn(1, 3),
        probeTimeoutSec = probeTimeoutSec.coerceIn(20, 300),
        retries = retries.coerceIn(0, 6),
        probeStartsPerMinute = probeStartsPerMinute.coerceIn(1, 5),
        confirmThreshold = confirmThreshold.coerceIn(16, 65_536),
    )
}

@Serializable
enum class DynamicStatus(val ru: String) {
    NOT_REQUESTED("Не запрашивалась"),
    UNAVAILABLE("Динамическая проверка недоступна"),
    QUEUED("Ожидание очереди"),
    RUNNING("Сканеры отвечают"),
    PARTIAL("Частично завершена"),
    DONE("Завершена"),
    FAILED("Ошибка динамической проверки"),
}

enum class StaticStatus(val ru: String) {
    PENDING("Выполняется"),
    LISTED("Найден в списках"),
    NOT_LISTED("Не обнаружен в списках"),
    UNKNOWN("Признак блокировки не получен"),
    ERROR("Ошибка"),
}

/** Итоговые состояния из ТЗ (раздел 9). Не сводятся к «заблокирован / не заблокирован». */
enum class Overall(val ru: String, val glyph: String) {
    BLOCKED("Заблокирован", "✕"),
    NOT_LISTED("Не обнаружен в списках", "–"),
    DYNAMIC_PENDING("Динамическая проверка ожидается", "…"),
    DYNAMIC_PARTIAL("Динамическая проверка частично завершена", "◐"),
    COMPLETE("Проверка завершена", "✓"),
    ERROR("Ошибка проверки", "!"),
    UNKNOWN("Результат неизвестен", "?"),
}

/**
 * Единица хранения: только исходные ответы сервиса и метки времени.
 * Всё остальное вычисляется из них, поэтому оригиналы всегда доступны для диагностики.
 */
@Serializable
data class CheckRecord(
    val target: String,
    val startedAt: Long,
    val staticRaw: String? = null,
    val staticAt: Long? = null,
    val staticError: String? = null,
    val dyn: DynamicStatus = DynamicStatus.NOT_REQUESTED,
    val dynNote: String? = null,
    val probeRaw: List<String> = emptyList(),
    val online: Int? = null,
    val responseCount: Int? = null,
    val dynStartedAt: Long? = null,
    val dynDoneAt: Long? = null,
    val finishedAt: Long? = null,
) {
    /** Запись закончена: статика получена (или провалилась), динамика не в процессе. */
    val isTerminal: Boolean
        get() = (staticRaw != null || staticError != null) &&
            dyn != DynamicStatus.QUEUED && dyn != DynamicStatus.RUNNING
}

data class CdnHit(val name: String, val cidr: String?, val region: String?)
data class DayCount(val date: String, val count: Int)

data class StaticResult(
    val id: String?,
    val target: String?,
    val targetType: String?,
    /** Поле blocked из ответа: только членство в списках, не подтверждение недоступности. */
    val listed: Boolean?,
    val ips: List<String>,
    val blockedSubnets: List<String>,
    val cdn: List<CdnHit>,
    val asn: String?,
    val organisation: String?,
    val countryCode: String?,
    val city: String?,
    val location: String?,
    val reverse: List<String>,
    val complaints: List<DayCount>,
    val subnetSize: String?,
    val rknDomain: String?,
    val whitelist: String?,
    /** Имена полей верхнего уровня, которых приложение не знает (сохраняются в сыром JSON). */
    val unknownKeys: List<String>,
) {
    val isCdn: Boolean get() = cdn.isNotEmpty()
}

enum class Verdict(val wire: String, val ru: String, val blocking: Boolean) {
    TSPU_BLOCK("tspu_block", "ТСПУ-блок", true),
    SNI_BLOCK("sni_block", "SNI-блок", true),
    DNS_SPOOFING("dns_spoofing", "Подмена DNS", true),
    WHITELIST("whitelist", "Исключение для CDN", false),
    CDN_BLOCK("cdn_block", "CDN-блок", true),
    OK("ok", "Доступен", false),
    UNCERTAIN("uncertain", "Неясно", false);

    companion object {
        fun fromWire(s: String): Verdict = entries.firstOrNull { it.wire == s } ?: UNCERTAIN
    }
}

data class ProbeResult(
    val jobId: String?,
    val probeId: String?,
    val region: String?,
    val provider: String?,
    val asn: String?,
    val verdicts: List<Verdict>,
    val cdnUnblocked: Boolean?,
    /** null — поле отсутствует или null; 0 — пустой массив (так для проверки по IP). */
    val hostResultsSize: Int?,
    val targetHop: Int?,
    val dpiHop: Int?,
    val dns: ProbeDnsInfo? = null,
    val hostResults: List<ProbeHostResult> = emptyList(),
)

data class ProbeDnsInfo(
    val spoofingDetected: Boolean?,
    val suspiciousProviderCount: Int?,
    val verdictThreshold: Int?,
    val samplesPerProtocol: Int?,
    val observations: List<ProbeDnsObservation>,
)

data class ProbeDnsObservation(
    val provider: String?,
    val protocol: String?,
    val suspectedSpoofing: Boolean?,
    val responseCodes: List<String>,
    val ipv4Count: Int?,
    val ipv6Count: Int?,
    /** Raw API values such as Answer, NoRecords, Error; deliberately not interpreted. */
    val outcomeType: String?,
    val addresses: List<String>,
    val errorMessage: String?,
)

data class ProbeHostResult(
    val hostId: String?,
    val host: String?,
    /** Raw probe_evidence.type string (Good, ClientHello, ConnectionError, DataTimeout, etc.). */
    val evidenceType: String?,
)

class ApiException(
    val kind: Kind,
    message: String,
    val httpCode: Int? = null,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { NETWORK, TIMEOUT, RATE_LIMITED, NOT_FOUND, SERVER, BAD_RESPONSE, CLIENT }

    val retriable: Boolean get() = kind != Kind.NOT_FOUND && kind != Kind.CLIENT
}
