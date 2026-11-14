package ru.blockscan.app

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.blockscan.app.data.HttpCheckApi
import ru.blockscan.app.domain.ApiException
import ru.blockscan.app.domain.AppSettings
import ru.blockscan.app.domain.DynamicStatus
import ru.blockscan.app.domain.IpChecker
import ru.blockscan.app.domain.ProbeEvent
import ru.blockscan.app.domain.StartLimiter

class ApiAuthTest {
    private val seen = mutableListOf<Request>()

    private fun api(token: String, body: String = "OK"): HttpCheckApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/plain".toMediaType()))
                .build()
        }.build()
        return HttpCheckApi("https://example.test", client, tokenProvider = { token })
    }

    @Test fun probeSendsBearerHeaderNotUrlToken() {
        val a = api("secret-token")
        runBlocking { runCatching { a.probe("id1").toList() } }
        val r = seen.last()
        assertEquals("Bearer secret-token", r.header("Authorization"))
        assertNull(r.url.query)
        assertTrue(r.url.encodedPath.endsWith("/probe/id1"))
    }

    @Test fun blankTokenSendsNoAuthorization() {
        val a = api("   ")
        runBlocking { runCatching { a.probe("id1").toList() } }
        assertNull(seen.last().header("Authorization"))
    }

    @Test fun checkNeverSendsToken() {
        val a = api("secret-token")
        runBlocking { a.check("1.1.1.1") }
        assertNull(seen.last().header("Authorization"))
        assertNull(seen.last().url.queryParameter("token"))
    }

    @Test fun creditsParsesBalanceAndSendsBearer() {
        val a = api("secret-token", """{"credits":42}""")
        val n = runBlocking { a.credits() }
        assertEquals(42, n)
        assertEquals("Bearer secret-token", seen.last().header("Authorization"))
    }

    @Test fun withoutTokenScannersAreNotStarted() {
        val fake = FakeApi()
        val checker = IpChecker(fake, StartLimiter(), { AppSettings() }, requireToken = true)
        val rec = runBlocking { checker.check("8.8.8.8") }
        assertEquals(DynamicStatus.UNAVAILABLE, rec.dyn)
        assertTrue(fake.probeIds.isEmpty())
    }

    @Test fun withTokenScannersAreStarted() {
        val fake = FakeApi()
        val checker = IpChecker(fake, StartLimiter(), { AppSettings(apiToken = "t") }, requireToken = true)
        runBlocking { checker.check("8.8.8.8") }
        assertEquals(1, fake.probeIds.size)
    }

    @Test fun rejectedTokenStopsFurtherScannerStarts() {
        val fake = FakeApi(onProbe = { flow<ProbeEvent> { throw ApiException(ApiException.Kind.CLIENT, "HTTP 401", httpCode = 401) } })
        val checker = IpChecker(fake, StartLimiter(), { AppSettings(apiToken = "bad") }, requireToken = true)
        val first = runBlocking { checker.check("8.8.8.8") }
        assertEquals(DynamicStatus.FAILED, first.dyn)
        assertEquals(1, fake.probeIds.size)
        val second = runBlocking { checker.check("8.8.4.4") }
        assertEquals(DynamicStatus.UNAVAILABLE, second.dyn)
        assertEquals(1, fake.probeIds.size)
    }
}
