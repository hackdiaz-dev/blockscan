package ru.blockscan.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.blockscan.app.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class IpCheckerTest {
    private val cfg = AppSettings(requestDelayMs = 300, retries = 2, probeTimeoutSec = 30, probeStartsPerMinute = 5)

    private fun TestScope.checker(api: FakeApi, settings: AppSettings = cfg): IpChecker {
        val clock = { testScheduler.currentTime }
        return IpChecker(api, StartLimiter(clock), { settings }, clock)
    }

    private fun realApi() = FakeApi(
        onCheck = { Res.text("check_1.1.1.1.json") },
        onProbe = { Fixtures.probeFlow(perEventDelayMs = 10) },
    )

    @Test fun fullCycleWithRealStreamEndsOnlyAfterDone() = runTest {
        val api = realApi()
        val updates = ArrayList<CheckRecord>()
        val r = checker(api).check("1.1.1.1") { updates += it }
        assertEquals(DynamicStatus.DONE, r.dyn)
        assertEquals(37, r.probeRaw.size)
        assertEquals(37, r.online)
        assertEquals(37, r.responseCount)
        assertEquals(Overall.COMPLETE, r.overall())
        assertEquals(listOf("test-id-1"), api.probeIds)
        assertTrue(r.isTerminal)
        // Статус DONE встречается только в последнем обновлении
        val firstDone = updates.indexOfFirst { it.dyn == DynamicStatus.DONE }
        assertEquals(updates.size - 1, firstDone)
        // Порядок состояний: QUEUED -> RUNNING -> DONE
        val order = updates.map { it.dyn }.distinct()
        assertEquals(listOf(DynamicStatus.NOT_REQUESTED, DynamicStatus.QUEUED, DynamicStatus.RUNNING, DynamicStatus.DONE), order)
    }

    @Test fun waitTimeIsDrivenByStreamNotFixedTimer() = runTest {
        val api = FakeApi(onProbe = { Fixtures.smallProbeFlow(delayMs = 100) })
        val t0 = testScheduler.currentTime
        checker(api).check("8.8.8.8")
        val spent = testScheduler.currentTime - t0
        assertTrue("слишком долго для потока на 100 мс: $spent", spent < 2_000L)
    }

    @Test fun privateAddressSkipsDynamicAndSaysSo() = runTest {
        val api = FakeApi(onCheck = { Res.text("check_192.168.1.1.json") })
        val r = checker(api).check("192.168.1.1")
        assertEquals(DynamicStatus.UNAVAILABLE, r.dyn)
        assertTrue(api.probeIds.isEmpty())
        assertEquals(Overall.NOT_LISTED, r.overall())
        assertNotEquals(Overall.COMPLETE, r.overall())
    }

    @Test fun privateAddressIsProbedWhenSkipDisabled() = runTest {
        val api = FakeApi(onCheck = { Res.text("check_192.168.1.1.json") })
        val r = checker(api, cfg.copy(skipDynamicForNonPublic = false)).check("192.168.1.1")
        assertEquals(DynamicStatus.DONE, r.dyn)
        assertEquals(1, api.probeIds.size)
    }

    @Test fun domainIsSentToServiceAndRunsDynamicWithoutLocalDnsExpansion() = runTest {
        val domainResponse = """{"id":"domain-id","target":"discord.com","target_type":"Домен","blocked":true,"rkn_domain":"discord.com","ips":["162.159.128.233","2606:4700::1111"],"blocked_subnets":[],"cdn_providers":{"cloudflare":[{"provider":"cloudflare","cidr":"162.159.128.0/20","region":null}]},"geo":{"asn":"AS13335","country_code":null,"organisation":"Cloudflare, Inc.","city_geo_name_id":null,"location":"-"},"reverse_lookup":[],"complaints":[]}"""
        val api = FakeApi(onCheck = { domainResponse }, onProbe = { Fixtures.smallProbeFlow() })
        val result = checker(api).check("HTTPS://Discord.com:443/path")

        assertEquals(listOf("discord.com"), api.checkTargets)
        assertEquals(listOf("domain-id"), api.probeIds)
        assertEquals(DynamicStatus.DONE, result.dyn)
        assertEquals(listOf("162.159.128.233", "2606:4700::1111"), result.static()!!.ips)
    }

    @Test fun missingIdMeansDynamicUnavailable() = runTest {
        val api = FakeApi(onCheck = { Fixtures.checkJson("8.8.8.8", id = null) })
        val r = checker(api).check("8.8.8.8")
        assertEquals(DynamicStatus.UNAVAILABLE, r.dyn)
        assertTrue(api.probeIds.isEmpty())
    }

    @Test fun staticServerErrorIsRetriedThenSucceeds() = runTest {
        var n = 0
        val api = FakeApi(onCheck = {
            if (n++ == 0) throw ApiException(ApiException.Kind.SERVER, "HTTP 503", httpCode = 503)
            Fixtures.checkJson(it)
        })
        val r = checker(api).check("8.8.8.8")
        assertEquals(2, api.checkTargets.size)
        assertNull(r.staticError)
        assertEquals(DynamicStatus.DONE, r.dyn)
    }

    @Test fun staticFailureAfterAllRetriesIsAnError() = runTest {
        val api = FakeApi(onCheck = { throw ApiException(ApiException.Kind.NETWORK, "down") })
        val r = checker(api).check("8.8.8.8")
        assertEquals(cfg.retries + 1, api.checkTargets.size)
        assertNotNull(r.staticError)
        assertEquals(Overall.ERROR, r.overall())
        assertTrue(api.probeIds.isEmpty())
    }

    @Test fun notFoundIsNotRetried() = runTest {
        val api = FakeApi(onCheck = { throw ApiException(ApiException.Kind.NOT_FOUND, "HTTP 404", httpCode = 404) })
        val r = checker(api).check("8.8.8.8")
        assertEquals(1, api.checkTargets.size)
        assertTrue(r.staticError!!.contains("404"))
    }

    @Test fun invalidJsonIsRetriedAsBadResponse() = runTest {
        val api = FakeApi(onCheck = { "<html>oops</html>" })
        val r = checker(api).check("8.8.8.8")
        assertEquals(cfg.retries + 1, api.checkTargets.size)
        assertTrue(r.staticError!!.contains("некорректный"))
    }

    @Test fun streamWithoutDoneKeepsPartialResultsAndIsNotComplete() = runTest {
        val raws = fixtureResultRaws().take(2)
        val api = FakeApi(onProbe = {
            flow {
                emit(ProbeEvent.Started("j", 37, null, "{}"))
                raws.forEach { emit(ProbeEvent.Result(ProbeParser.parseResult(it), it)) }
            }
        })
        val updates = ArrayList<CheckRecord>()
        val r = checker(api).check("8.8.8.8") { updates += it }
        assertEquals(DynamicStatus.PARTIAL, r.dyn)
        assertEquals(2, r.probeRaw.size)
        assertEquals(Overall.DYNAMIC_PARTIAL, r.overall())
        assertTrue(updates.none { it.dyn == DynamicStatus.DONE })
        assertEquals(1, api.probeIds.size)
    }

    @Test fun silentStreamIsRetriedThenFails() = runTest {
        val api = FakeApi(onProbe = { flow<ProbeEvent> { } })
        val r = checker(api).check("8.8.8.8")
        assertEquals(cfg.retries + 1, api.probeIds.size)
        assertEquals(DynamicStatus.FAILED, r.dyn)
        assertEquals(Overall.ERROR, r.overall())
    }

    @Test fun timeoutKeepsWhatArrived() = runTest {
        val raw = fixtureResultRaws().first()
        val api = FakeApi(onProbe = {
            flow {
                emit(ProbeEvent.Started("j", 37, null, "{}"))
                emit(ProbeEvent.Result(ProbeParser.parseResult(raw), raw))
                awaitCancellation()
            }
        })
        val t0 = testScheduler.currentTime
        val r = checker(api).check("8.8.8.8")
        assertEquals(DynamicStatus.PARTIAL, r.dyn)
        assertTrue(r.dynNote!!.contains("таймаут"))
        assertTrue(testScheduler.currentTime - t0 >= cfg.probeTimeoutSec * 1000L)
    }

    @Test fun timeoutWithNoResultsIsFailedNotComplete() = runTest {
        val api = FakeApi(onProbe = { flow<ProbeEvent> { awaitCancellation() } })
        val r = checker(api, cfg.copy(retries = 0)).check("8.8.8.8")
        assertEquals(DynamicStatus.FAILED, r.dyn)
        assertNotEquals(DynamicStatus.DONE, r.dyn)
    }

    @Test fun inStreamRateLimitWaitsAndRetries() = runTest {
        var n = 0
        val api = FakeApi(onProbe = {
            if (n++ == 0) flow<ProbeEvent> { emit(ProbeEvent.ServerError(429, "Too Many Requests", "{}")) } else Fixtures.smallProbeFlow()
        })
        val t0 = testScheduler.currentTime
        val r = checker(api).check("8.8.8.8")
        assertEquals(DynamicStatus.DONE, r.dyn)
        assertEquals(2, api.probeIds.size)
        assertTrue(testScheduler.currentTime - t0 >= 20_000L)
    }

    @Test fun httpRateLimitHonoursRetryAfter() = runTest {
        var n = 0
        val api = FakeApi(onProbe = {
            if (n++ == 0) flow<ProbeEvent> { throw ApiException(ApiException.Kind.RATE_LIMITED, "HTTP 429", 429, retryAfterMs = 45_000) } else Fixtures.smallProbeFlow()
        })
        val t0 = testScheduler.currentTime
        val r = checker(api).check("8.8.8.8")
        assertEquals(DynamicStatus.DONE, r.dyn)
        assertTrue(testScheduler.currentTime - t0 >= 45_000L)
    }

    @Test fun twoChecksNeverExceedStartLimit() = runTest {
        val api = FakeApi()
        val c = checker(api, cfg.copy(probeStartsPerMinute = 1))
        c.check("8.8.8.8")
        val t1 = testScheduler.currentTime
        c.check("8.8.4.4")
        assertTrue(testScheduler.currentTime - t1 >= 59_000L)
    }
}
