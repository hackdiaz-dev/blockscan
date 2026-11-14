package ru.blockscan.app.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Скользящее окно: не более N запусков динамической проверки за окно. Общее для всех воркеров.
 * Темп N задаёт пользователь. Если сервис ответил 429, темп снижается вдвое (не ниже 1/8 заданного):
 * так велит документация API (уменьшить частоту и повторить позже). Через 5 минут без 429 темп возвращается.
 */
class StartLimiter(private val now: () -> Long = { System.currentTimeMillis() }) {
    private val mutex = Mutex()
    private val stamps = ArrayDeque<Long>()
    @Volatile private var factor = 1.0
    @Volatile private var penaltyAt = 0L

    fun onRateLimited() {
        factor = (factor / 2).coerceAtLeast(MIN_FACTOR)
        penaltyAt = now()
    }

    suspend fun acquire(maxPerWindow: Int, windowMs: Long = 60_000L) {
        while (true) {
            val wait = mutex.withLock {
                val t = now()
                if (factor < 1.0 && t - penaltyAt >= RECOVER_MS) factor = 1.0
                val limit = (maxPerWindow * factor).toInt().coerceAtLeast(1)
                while (stamps.isNotEmpty() && t - stamps.first() >= windowMs) stamps.removeFirst()
                if (stamps.size < limit) {
                    stamps.addLast(t)
                    0L
                } else {
                    stamps.first() + windowMs - t + 50L
                }
            }
            if (wait <= 0L) return
            delay(wait)
        }
    }

    private companion object {
        const val MIN_FACTOR = 0.125
        const val RECOVER_MS = 300_000L
    }
}
