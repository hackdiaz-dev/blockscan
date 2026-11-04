package ru.blockscan.app.domain

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import ru.blockscan.app.core.IpClassifier
import ru.blockscan.app.core.IpParser

/**
 * Полный цикл проверки одного IP:
 *  1) /check (статика, получение id), 2) ожидание слота лимитера, 3) SSE /probe/{id} до события done.
 * Время ожидания определяется потоком событий, а не фиксированной паузой; таймаут нужен только как предохранитель.
 */
class IpChecker(
    private val api: CheckApi,
    private val limiter: StartLimiter,
    private val settings: () -> AppSettings,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val spaceMutex = Mutex()
    private var lastRequestAt = 0L

    private suspend fun spaced(minMs: Long) {
        spaceMutex.withLock {
            val wait = lastRequestAt + minMs - clock()
            if (wait > 0) delay(wait)
            lastRequestAt = clock()
        }
    }

    private suspend fun <T> withRetries(cfg: AppSettings, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ApiException) {
                if (!e.retriable || attempt >= cfg.retries) throw e
                attempt++
                val backoff = if (e.kind == ApiException.Kind.RATE_LIMITED) 20_000L * attempt else backoffMs(attempt)
                delay(maxOf(e.retryAfterMs ?: 0L, backoff))
            }
        }
    }

    internal fun backoffMs(attempt: Int): Long = 1_500L * (1L shl (attempt - 1).coerceIn(0, 5))

    suspend fun check(target: String, onUpdate: suspend (CheckRecord) -> Unit = {}): CheckRecord {
        val cfg = settings().sanitized()
        var rec = CheckRecord(target = target, startedAt = clock())
        onUpdate(rec)

        val staticPair: Pair<String, StaticResult> = try {
            withRetries(cfg) {
                spaced(cfg.requestDelayMs)
                val body = api.check(target)
                body to CheckParser.parse(body)
            }
        } catch (e: ApiException) {
            rec = rec.copy(staticError = describe(e), finishedAt = clock())
            onUpdate(rec)
            return rec
        }

        rec = rec.copy(staticRaw = staticPair.first, staticAt = clock())
        val id = staticPair.second.id
        val ip = IpParser.parse(target)
        rec = when {
            id.isNullOrBlank() -> rec.copy(
                dyn = DynamicStatus.UNAVAILABLE,
                dynNote = "Сервис не вернул идентификатор проверки (id)",
            )
            ip == null -> rec.copy(
                dyn = DynamicStatus.UNAVAILABLE,
                dynNote = "Динамическая проверка выполняется только для одиночных IP",
            )
            cfg.skipDynamicForNonPublic && IpClassifier.isNonPublic(ip) -> rec.copy(
                dyn = DynamicStatus.UNAVAILABLE,
                dynNote = "Частный или служебный адрес: по данным сторонних клиентов сервис не запускает для него " +
                    "динамическую проверку (можно отключить в настройках)",
            )
            else -> rec.copy(dyn = DynamicStatus.QUEUED)
        }
        onUpdate(rec)
        if (rec.dyn != DynamicStatus.QUEUED) {
            rec = rec.copy(finishedAt = clock())
            onUpdate(rec)
            return rec
        }
        rec = runDynamic(id!!, rec, cfg, onUpdate)
        return rec
    }

    private sealed interface End {
        data object Done : End
        data object Closed : End
        data object Timeout : End
        data class Malformed(val event: String) : End
        data class Server(val code: Int?, val message: String) : End
        data class Api(val ex: ApiException) : End
    }

    private suspend fun runDynamic(
        id: String,
        start: CheckRecord,
        cfg: AppSettings,
        onUpdate: suspend (CheckRecord) -> Unit,
    ): CheckRecord {
        var rec = start
        var attempt = 0
        while (true) {
            rec = rec.copy(
                dyn = DynamicStatus.QUEUED,
                dynNote = if (attempt > 0) "Повторный запуск $attempt из ${cfg.retries}" else null,
            )
            onUpdate(rec)
            limiter.acquire(cfg.probeStartsPerMinute)
            rec = rec.copy(
                dyn = DynamicStatus.RUNNING, dynStartedAt = clock(), dynDoneAt = null, dynNote = null,
                probeRaw = emptyList(), online = null, responseCount = null,
            )
            onUpdate(rec)

            var end: End = End.Closed
            var malformedEvent = false
            try {
                withTimeout(cfg.probeTimeoutSec * 1000L) {
                    api.probe(id)
                        .transformWhile { emit(it); it !is ProbeEvent.Done && it !is ProbeEvent.ServerError }
                        .collect { ev ->
                            when (ev) {
                                is ProbeEvent.Started -> rec = rec.copy(online = ev.online)
                                is ProbeEvent.Result -> rec = rec.copy(probeRaw = rec.probeRaw + ev.raw)
                                is ProbeEvent.Done -> {
                                    rec = rec.copy(responseCount = ev.responseCount, online = ev.online ?: rec.online)
                                    end = if (!malformedEvent && ev.responseCount != null && ev.responseCount == rec.probeRaw.size) End.Done
                                    else End.Malformed("done: response_count=${ev.responseCount}, получено=${rec.probeRaw.size}")
                                }
                                is ProbeEvent.ServerError -> end = End.Server(ev.code, ev.message)
                                is ProbeEvent.Malformed -> { malformedEvent = true; end = End.Malformed("битое SSE-событие ${ev.event}") }
                            }
                            onUpdate(rec)
                        }
                }
            } catch (e: TimeoutCancellationException) {
                end = End.Timeout
            } catch (e: ApiException) {
                end = End.Api(e)
            }

            val e = end
            val got = rec.probeRaw.size
            if (e is End.Done) {
                rec = rec.copy(dyn = DynamicStatus.DONE, dynDoneAt = clock(), dynNote = null, finishedAt = clock())
                onUpdate(rec)
                return rec
            }
            val rateLimited = (e is End.Server && e.code == 429) ||
                (e is End.Api && e.ex.kind == ApiException.Kind.RATE_LIMITED)
            val retryAfter = (e as? End.Api)?.ex?.retryAfterMs
            val reason = reasonOf(e, cfg)

            if (got > 0 || e is End.Malformed) {
                // Частичные результаты сохраняем, но завершённой проверку не считаем.
                rec = rec.copy(
                    dyn = DynamicStatus.PARTIAL,
                    dynNote = "$reason. Получено ответов сканеров: $got" + (rec.online?.let { " из $it" } ?: ""),
                    finishedAt = clock(),
                )
                onUpdate(rec)
                return rec
            }
            if (e is End.Api && !e.ex.retriable) {
                rec = rec.copy(dyn = DynamicStatus.FAILED, dynNote = "Сканеры не ответили: $reason", finishedAt = clock())
                onUpdate(rec)
                return rec
            }
            if (attempt >= cfg.retries) {
                rec = rec.copy(dyn = DynamicStatus.FAILED, dynNote = "Сканеры не ответили: $reason", finishedAt = clock())
                onUpdate(rec)
                return rec
            }
            attempt++
            val wait = if (rateLimited) maxOf(retryAfter ?: 0L, 20_000L * attempt) else maxOf(retryAfter ?: 0L, backoffMs(attempt))
            rec = rec.copy(
                dyn = DynamicStatus.QUEUED,
                dynNote = (if (rateLimited) "Лимит сервиса, повтор через ${wait / 1000} с" else "$reason, повтор через ${wait / 1000} с"),
            )
            onUpdate(rec)
            delay(wait)
        }
    }

    private fun reasonOf(e: End, cfg: AppSettings): String = when (e) {
        End.Done -> "завершено"
        End.Closed -> "поток закрыт сервером до события done"
        End.Timeout -> "таймаут ${cfg.probeTimeoutSec} с"
        is End.Malformed -> e.event
        is End.Server -> "сервис вернул ошибку" + (e.code?.let { " $it" } ?: "") + ": ${e.message}"
        is End.Api -> describe(e.ex)
    }

    companion object {
        fun describe(e: ApiException): String = when (e.kind) {
            ApiException.Kind.NETWORK -> "нет соединения с сервисом"
            ApiException.Kind.TIMEOUT -> "таймаут запроса"
            ApiException.Kind.RATE_LIMITED -> "лимит запросов сервиса (429)"
            ApiException.Kind.NOT_FOUND -> "сервис не принял цель (404)"
            ApiException.Kind.SERVER -> "ошибка сервера" + (e.httpCode?.let { " $it" } ?: "")
            ApiException.Kind.BAD_RESPONSE -> "некорректный ответ сервиса"
            ApiException.Kind.CLIENT -> "запрос отклонён" + (e.httpCode?.let { " ($it)" } ?: "")
        }
    }
}
