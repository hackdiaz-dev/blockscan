package ru.blockscan.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.blockscan.app.data.HttpCheckApi
import ru.blockscan.app.domain.*

/**
 * Проверка на реальном cheburcheck.ru. Запускается только при CHEBURCHECK_LIVE=1
 * (CHEBURCHECK_LIVE=1 gradle :app:testDebugUnitTest --tests "*LiveApiTest*" -i). Делает 3 обычных запроса и один запуск сканеров.
 */
class LiveApiTest {
    private val live = System.getenv("CHEBURCHECK_LIVE") == "1"
    private val liveDomain = System.getenv("CHEBURCHECK_LIVE_DOMAIN") == "1"

    @Test fun realServiceRoundTrip() = runBlocking {
        assumeTrue("CHEBURCHECK_LIVE не задан", live)
        val api = HttpCheckApi()
        assertTrue(api.healthcheck())
        val st = api.status()
        assertNotNull(st.version)
        assertNotNull(st.lastUpdate)

        val checker = IpChecker(api, StartLimiter(), { AppSettings(probeTimeoutSec = 120) })
        val r = checker.check("1.1.1.1")
        println("LIVE: overall=${r.overall()} dyn=${r.dyn} note=${r.dynNote} probes=${r.probeRaw.size} online=${r.online} done=${r.responseCount}")
        assertNull(r.staticError)
        assertNotNull(r.static())
        assertEquals("динамика должна завершиться событием done: ${r.dynNote}", DynamicStatus.DONE, r.dyn)
        assertTrue(r.probeRaw.isNotEmpty())
        assertNotEquals(Overall.UNKNOWN, r.overall())
    }

    @Test fun realDomainRoundTrip() = runBlocking {
        assumeTrue("CHEBURCHECK_LIVE_DOMAIN не задан", liveDomain)
        val checker = IpChecker(HttpCheckApi(), StartLimiter(), { AppSettings(probeTimeoutSec = 120) })
        val record = checker.check("discord.com")
        assertNull(record.staticError)
        assertEquals("Домен", record.static()?.targetType)
        assertTrue("API должен вернуть хотя бы один A/AAAA адрес", record.static()?.ips?.isNotEmpty() == true)
        assertEquals("динамика домена должна завершиться событием done: ${record.dynNote}", DynamicStatus.DONE, record.dyn)
        assertTrue(record.probeRaw.isNotEmpty())
    }
}
