package ru.blockscan.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonElement?.text(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonElement?.intOrNull(): Int? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toIntOrNull()

internal fun JsonElement?.boolOrNull(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toBooleanStrictOrNull()

internal fun JsonElement?.textList(): List<String> =
    (this as? JsonArray)?.mapNotNull { it.text() ?: it.takeIf { e -> e !is JsonNull }?.toString() } ?: emptyList()

internal fun parseObject(raw: String, what: String): JsonObject {
    val el = try {
        Json.parseToJsonElement(raw)
    } catch (e: Exception) {
        throw ApiException(ApiException.Kind.BAD_RESPONSE, "$what: ответ не является JSON", cause = e)
    }
    return el as? JsonObject
        ?: throw ApiException(ApiException.Kind.BAD_RESPONSE, "$what: ожидался JSON-объект")
}

/** Разбор ответа /api/v1/check по дереву JSON: неизвестные и отсутствующие поля не ломают разбор. */
object CheckParser {
    val knownKeys = setOf(
        "id", "target", "target_type", "blocked", "ips", "blocked_subnets", "cdn_providers", "geo",
        "reverse_lookup", "complaints", "subnet_size", "rkn_domain", "whitelist",
    )

    fun parse(raw: String): StaticResult {
        val o = parseObject(raw, "check")
        val geo = o["geo"] as? JsonObject
        val cdn = ArrayList<CdnHit>()
        (o["cdn_providers"] as? JsonObject)?.forEach { (name, v) ->
            val arr = v as? JsonArray
            if (arr == null || arr.isEmpty()) {
                cdn += CdnHit(name, null, null)
            } else {
                arr.forEach { item ->
                    val io = item as? JsonObject
                    cdn += CdnHit(io?.get("provider").text() ?: name, io?.get("cidr").text(), io?.get("region").text())
                }
            }
        }
        val complaints = (o["complaints"] as? JsonArray)?.mapNotNull {
            val io = it as? JsonObject ?: return@mapNotNull null
            val d = io["date"].text() ?: return@mapNotNull null
            DayCount(d, io["count"].intOrNull() ?: 0)
        } ?: emptyList()
        return StaticResult(
            id = o["id"].text(),
            target = o["target"].text(),
            targetType = o["target_type"].text(),
            listed = o["blocked"].boolOrNull(),
            ips = o["ips"].textList(),
            blockedSubnets = o["blocked_subnets"].textList(),
            cdn = cdn,
            asn = geo?.get("asn").text(),
            organisation = geo?.get("organisation").text(),
            countryCode = geo?.get("country_code").text(),
            city = geo?.get("city_geo_name_id").text(),
            location = geo?.get("location").text(),
            reverse = o["reverse_lookup"].textList(),
            complaints = complaints,
            subnetSize = o["subnet_size"].text(),
            rknDomain = o["rkn_domain"].text(),
            whitelist = o["whitelist"]?.takeIf { it !is JsonNull }?.let { it.text() ?: it.toString() },
            unknownKeys = o.keys.filter { it !in knownKeys },
        )
    }
}

object ProbeParser {
    fun parseResult(raw: String): ProbeResult {
        val o = parseObject(raw, "probe result")
        return ProbeResult(
            jobId = o["job_id"].text(),
            probeId = o["probe_id"].text(),
            region = o["region"].text(),
            provider = o["provider"].text(),
            asn = o["asn"].text(),
            verdicts = (o["verdicts"] as? JsonArray)?.mapNotNull { it.text()?.let(Verdict::fromWire) } ?: emptyList(),
            cdnUnblocked = o["cdn_unblocked"].boolOrNull(),
            hostResultsSize = (o["host_results"] as? JsonArray)?.size,
            targetHop = o["target_hop"].intOrNull(),
            dpiHop = o["dpi_hop"].intOrNull(),
            dns = (o["dns"] as? JsonObject)?.let { dns ->
                val observations = (dns["observations"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val observation = item as? JsonObject ?: return@mapNotNull null
                    val metadata = observation["metadata"] as? JsonObject
                    val outcome = observation["outcome"] as? JsonObject
                    ProbeDnsObservation(
                        provider = observation["provider"].text(),
                        protocol = observation["protocol"].text(),
                        suspectedSpoofing = observation["suspected_spoofing"].boolOrNull(),
                        responseCodes = metadata?.get("response_codes").textList(),
                        ipv4Count = metadata?.get("ipv4_count").intOrNull(),
                        ipv6Count = metadata?.get("ipv6_count").intOrNull(),
                        outcomeType = outcome?.get("type").text(),
                        addresses = outcome?.get("addresses").textList(),
                        errorMessage = outcome?.get("message").text(),
                    )
                }
                ProbeDnsInfo(
                    spoofingDetected = dns["spoofing_detected"].boolOrNull(),
                    suspiciousProviderCount = dns["suspicious_provider_count"].intOrNull(),
                    verdictThreshold = dns["verdict_threshold"].intOrNull(),
                    samplesPerProtocol = dns["samples_per_protocol"].intOrNull(),
                    observations = observations,
                )
            },
            hostResults = (o["host_results"] as? JsonArray).orEmpty().mapNotNull { item ->
                val host = item as? JsonObject ?: return@mapNotNull null
                val evidence = host["probe_evidence"] as? JsonObject
                ProbeHostResult(
                    hostId = host["host_id"].text(),
                    host = host["host"].text(),
                    evidenceType = evidence?.get("type").text(),
                )
            },
        )
    }

    fun parseStatus(raw: String): ServiceStatus {
        val o = parseObject(raw, "status")
        return ServiceStatus(
            domainCount = o["domain_count"].text()?.toLongOrNull(),
            v4Count = o["v4_count"].text()?.toLongOrNull(),
            lastUpdate = o["last_update"].text(),
            version = o["version"].text(),
            raw = raw,
        )
    }

    /** Сопоставляет SSE-событие сервиса с доменным событием. Неизвестные типы и битые данные дают null. */
    fun mapEvent(name: String, data: String): ProbeEvent? = try {
        when (name) {
            "started" -> {
                val o = parseObject(data, "started")
                ProbeEvent.Started(o["id"].text(), o["online_probes"].intOrNull(), o["target"].text(), data)
            }
            "result" -> ProbeEvent.Result(parseResult(data), data)
            "done" -> {
                val o = parseObject(data, "done")
                ProbeEvent.Done(o["response_count"].intOrNull(), o["online_probes"].intOrNull(), data)
            }
            "error" -> {
                val o = runCatching { parseObject(data, "error") }.getOrNull()
                ProbeEvent.ServerError(
                    o?.get("code").intOrNull(),
                    o?.get("info").text() ?: o?.get("message").text() ?: data.take(200),
                    data,
                )
            }
            else -> null
        }
    } catch (e: ApiException) {
        if (name == "result" || name == "done") ProbeEvent.Malformed(name, data) else null
    }
}
