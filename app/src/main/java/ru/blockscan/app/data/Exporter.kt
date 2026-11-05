package ru.blockscan.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.blockscan.app.core.IpParser
import ru.blockscan.app.domain.*
import java.time.Instant

enum class ExportFormat(val ext: String, val mime: String, val ru: String) {
    CSV("csv", "text/csv", "CSV"),
    JSON("json", "application/json", "JSON"),
    TXT("txt", "text/plain", "TXT"),
}

/**
 * Экспорт построчно в Appendable (память не растёт с размером результата).
 * JSON содержит исходные ответы сервиса (static_raw, probe_raw) при includeRaw; CSV и TXT — только сводку и метки времени.
 */
object Exporter {
    private const val IP_SCOPE_NOTE = "Проверка по IP не включает SNI и DNS: для сайтов проверяйте домен."
    private fun iso(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).toString() } ?: ""

    private fun csv(s: String?): String {
        val v = s ?: ""
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    private fun rawJson(s: String): JsonElement = runCatching { Json.parseToJsonElement(s) }.getOrElse { JsonPrimitive(s) }

    private class Flat(val row: ItemRow) {
        val rec = row.record
        val overall = row.overall ?: rec?.overall()
        val st = rec?.static()
        val sum = rec?.summary()
    }

    fun write(out: Appendable, format: ExportFormat, rows: Sequence<ItemRow>, includeRaw: Boolean) {
        when (format) {
            ExportFormat.CSV -> {
                out.append("target,overall,static_status,dynamic_status,scanner_verdict,blocking_scanners,total_scanners,")
                    .append("online_scanners,asn,organisation,static_checked_at,dynamic_finished_at,note,ip_check_note\n")
                for (r in rows) {
                    val f = Flat(r)
                    val rec = f.rec
                    out.append(listOf(
                        r.target, f.overall?.name ?: "QUEUED", rec?.staticStatus()?.name ?: "PENDING",
                        rec?.dyn?.name ?: "NOT_REQUESTED", f.sum?.winner?.wire ?: "",
                        f.sum?.blockingProbes?.toString() ?: "", f.sum?.totalProbes?.toString() ?: "",
                        rec?.online?.toString() ?: "", f.st?.asn ?: "", f.st?.organisation?.let { org ->
                            if (org.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$org" else org
                        } ?: "",
                        iso(rec?.staticAt), iso(rec?.dynDoneAt), rec?.dynNote ?: rec?.staticError ?: "",
                        if (IpParser.parse(r.target) != null) IP_SCOPE_NOTE else "",
                    ).joinToString(",") { csv(it) }).append('\n')
                }
            }
            ExportFormat.TXT -> {
                for (r in rows) {
                    val f = Flat(r)
                    val rec = f.rec
                    out.append(listOf(
                        r.target, f.overall?.ru ?: "Ожидает", rec?.staticStatus()?.ru ?: "—", rec?.dyn?.ru ?: "—",
                        f.sum?.let { "${it.blockingProbes}/${it.totalProbes} блок" } ?: "—", iso(rec?.staticAt), iso(rec?.dynDoneAt),
                        if (IpParser.parse(r.target) != null) IP_SCOPE_NOTE else "",
                    ).joinToString("\t")).append('\n')
                }
            }
            ExportFormat.JSON -> {
                out.append("[")
                var first = true
                for (r in rows) {
                    val f = Flat(r)
                    val rec = f.rec
                    val o = buildJsonObject {
                        put("target", r.target)
                        put("overall", f.overall?.name ?: "QUEUED")
                        put("static_status", rec?.staticStatus()?.name ?: "PENDING")
                        put("dynamic_status", rec?.dyn?.name ?: "NOT_REQUESTED")
                        put("scanner_verdict", f.sum?.winner?.wire)
                        put("blocking_scanners", f.sum?.blockingProbes)
                        put("total_scanners", f.sum?.totalProbes)
                        put("online_scanners", rec?.online)
                        put("response_count", rec?.responseCount)
                        put("started_at", iso(rec?.startedAt))
                        put("static_checked_at", iso(rec?.staticAt))
                        put("dynamic_started_at", iso(rec?.dynStartedAt))
                        put("dynamic_finished_at", iso(rec?.dynDoneAt))
                        put("note", rec?.dynNote ?: rec?.staticError)
                        put("ip_check_note", if (IpParser.parse(r.target) != null) IP_SCOPE_NOTE else null)
                        if (includeRaw && rec != null) {
                            put("static_raw", rec.staticRaw?.let { rawJson(it) } ?: JsonNull)
                            put("probe_raw", JsonArray(rec.probeRaw.map { rawJson(it) }))
                        }
                    }
                    out.append(if (first) "\n" else ",\n").append(o.toString())
                    first = false
                }
                out.append("\n]\n")
            }
        }
    }
}
