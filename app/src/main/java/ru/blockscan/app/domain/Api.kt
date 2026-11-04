package ru.blockscan.app.domain

import kotlinx.coroutines.flow.Flow

data class ServiceStatus(
    val domainCount: Long?,
    val v4Count: Long?,
    val lastUpdate: String?,
    val version: String?,
    val raw: String,
)

sealed interface ProbeEvent {
    data class Started(val id: String?, val online: Int?, val target: String?, val raw: String) : ProbeEvent
    data class Result(val result: ProbeResult, val raw: String) : ProbeEvent
    data class Done(val responseCount: Int?, val online: Int?, val raw: String) : ProbeEvent
    data class Malformed(val event: String, val raw: String) : ProbeEvent
    data class ServerError(val code: Int?, val message: String, val raw: String) : ProbeEvent
}

interface CheckApi {
    suspend fun status(): ServiceStatus
    suspend fun healthcheck(): Boolean

    /** Возвращает сырое тело ответа /api/v1/check. */
    suspend fun check(target: String): String

    /** Поток событий динамической проверки. Завершение подтверждается только событием Done. */
    fun probe(id: String): Flow<ProbeEvent>
}
