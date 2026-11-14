package ru.blockscan.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.blockscan.app.domain.StartLimiter

@OptIn(ExperimentalCoroutinesApi::class)
class StartLimiterAdaptiveTest {
    @Test fun rateLimitHalvesPace() = runTest {
        val l = StartLimiter { testScheduler.currentTime }
        l.onRateLimited()
        repeat(5) { l.acquire(10) }
        assertEquals(0L, testScheduler.currentTime)
        l.acquire(10)
        assertTrue(testScheduler.currentTime >= 60_000L)
    }

    @Test fun paceRecoversAfterFiveMinutes() = runTest {
        val l = StartLimiter { testScheduler.currentTime }
        l.onRateLimited()
        delay(300_000L)
        repeat(10) { l.acquire(10) }
        assertEquals(300_000L, testScheduler.currentTime)
    }
}
