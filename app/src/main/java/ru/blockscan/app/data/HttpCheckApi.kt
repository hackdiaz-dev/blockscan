package ru.blockscan.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.blockscan.app.core.SseParser
import ru.blockscan.app.domain.ApiException
import ru.blockscan.app.domain.CheckApi
import ru.blockscan.app.domain.ProbeEvent
import ru.blockscan.app.domain.ProbeParser
import ru.blockscan.app.domain.ServiceStatus
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Клиент публичного API cheburcheck.ru. Единственный хост, с которым работает приложение. */
class HttpCheckApi(
    private val baseUrl: String = "https://cheburcheck.ru",
    client: OkHttpClient = OkHttpClient(),
) : CheckApi {

    private val http = client.newBuilder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    // Для SSE общего таймаута вызова нет (его задаёт вызывающий код), только простой между событиями.
    private val sse = client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun url(vararg segments: String, query: Pair<String, String>? = null): HttpUrl {
        val b = baseUrl.toHttpUrl().newBuilder()
        segments.forEach { b.addPathSegment(it) }
        if (query != null) b.addQueryParameter(query.first, query.second)
        return b.build()
    }

    private fun request(u: HttpUrl, accept: String) = Request.Builder()
        .url(u)
        .header("Accept", accept)
        .header("User-Agent", USER_AGENT)
        .build()

    private suspend fun getText(u: HttpUrl, accept: String): String {
        val resp = try {
            http.newCall(request(u, accept)).await()
        } catch (e: IOException) {
            throw mapIo(e)
        }
        return resp.use { r ->
            if (!r.isSuccessful) throw httpError(r)
            try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                throw mapIo(e)
            }
        }
    }

    override suspend fun status(): ServiceStatus =
        ProbeParser.parseStatus(getText(url("api", "v1", "status"), "application/json"))

    override suspend fun healthcheck(): Boolean =
        getText(url("api", "v1", "healthcheck"), "text/plain").trim().equals("OK", ignoreCase = true)

    override suspend fun check(target: String): String =
        getText(url("api", "v1", "check", query = "target" to target), "application/json")

    @OptIn(InternalCoroutinesApi::class)
    override fun probe(id: String): Flow<ProbeEvent> = flow {
        val call = sse.newCall(request(url("api", "v1", "probe", id), "text/event-stream"))
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        try {
            val resp = try {
                withContext(Dispatchers.IO) { call.execute() }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw mapIo(e)
            }
            resp.use { r ->
                if (!r.isSuccessful) throw httpError(r)
                val source = r.body?.source() ?: throw ApiException(ApiException.Kind.BAD_RESPONSE, "пустое тело SSE")
                val parser = SseParser()
                while (true) {
                    val line: String? = try {
                        withContext(Dispatchers.IO) { source.readUtf8Line() }
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw mapIo(e)
                    }
                    if (line == null) break
                    parser.feed(line)?.let { ev -> ProbeParser.mapEvent(ev.event, ev.data)?.let { emit(it) } }
                }
                parser.flush()?.let { ev -> ProbeParser.mapEvent(ev.event, ev.data)?.let { emit(it) } }
            }
        } finally {
            handle?.dispose()
        }
    }

    private fun mapIo(e: IOException): ApiException =
        if (e is SocketTimeoutException) ApiException(ApiException.Kind.TIMEOUT, "таймаут", cause = e)
        else ApiException(ApiException.Kind.NETWORK, e.message ?: "сетевая ошибка", cause = e)

    private fun httpError(r: Response): ApiException {
        val code = r.code
        val retryAfter = r.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000L }
        val kind = when {
            code == 429 -> ApiException.Kind.RATE_LIMITED
            code == 404 -> ApiException.Kind.NOT_FOUND
            code >= 500 -> ApiException.Kind.SERVER
            else -> ApiException.Kind.CLIENT
        }
        return ApiException(kind, "HTTP $code", httpCode = code, retryAfterMs = retryAfter)
    }

    companion object {
        const val USER_AGENT = "BlockScan-Android/1.0 (unofficial client of cheburcheck.ru)"
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}
