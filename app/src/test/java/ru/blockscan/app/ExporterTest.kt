package ru.blockscan.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import ru.blockscan.app.data.ExportFormat
import ru.blockscan.app.data.Exporter
import ru.blockscan.app.domain.*

class ExporterTest {
    private val done = ItemRow("1.1.1.1", ItemState.FINISHED, Overall.COMPLETE,
        rec(DynamicStatus.DONE, fixtureResultRaws(), online = 37).copy(dynDoneAt = 1_700_000_000_000, responseCount = 37), 0)
    private val queued = ItemRow("1.1.1.2", ItemState.QUEUED, null, null, 0)
    private val note = ItemRow("1.1.1.3", ItemState.FINISHED, Overall.ERROR,
        CheckRecord("1.1.1.3", 0, staticError = "ошибка, \"кавычки\"", finishedAt = 1), 0)

    private fun out(f: ExportFormat, raw: Boolean) = StringBuilder().also { Exporter.write(it, f, sequenceOf(done, queued, note), raw) }.toString()

    @Test fun csvHasHeaderRowsAndTimestamps() {
        val lines = out(ExportFormat.CSV, true).trim().lines()
        assertTrue(lines[0].startsWith("target,overall,static_status,dynamic_status"))
        assertEquals(4, lines.size)
        assertTrue(lines[1].startsWith("1.1.1.1,COMPLETE,LISTED,DONE,ok,5,37,37,AS13335,\"Cloudflare, Inc.\","))
        assertTrue(lines[1].contains("2023-11-14T22:13:20Z"))
        assertTrue(lines[1].contains("Проверка по IP не включает SNI и DNS"))
        assertTrue(lines[2].startsWith("1.1.1.2,QUEUED,PENDING"))
        assertTrue(lines[3].contains("\"ошибка, \"\"кавычки\"\"\""))
    }

    @Test fun jsonContainsRawServiceResponsesWhenRequested() {
        val arr = Json.parseToJsonElement(out(ExportFormat.JSON, true)).jsonArray
        assertEquals(3, arr.size)
        val first = arr[0].jsonObject
        assertEquals("COMPLETE", first["overall"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", first["static_raw"]!!.jsonObject["target"]!!.jsonPrimitive.content)
        assertEquals(37, (first["probe_raw"] as JsonArray).size)
        assertEquals("2023-11-14T22:13:20Z", first["dynamic_finished_at"]!!.jsonPrimitive.content)
        assertTrue(first["ip_check_note"]!!.jsonPrimitive.content.contains("SNI и DNS"))
        assertEquals("QUEUED", arr[1].jsonObject["overall"]!!.jsonPrimitive.content)
    }

    @Test fun jsonWithoutRawOmitsOriginals() {
        val arr = Json.parseToJsonElement(out(ExportFormat.JSON, false)).jsonArray
        assertFalse((arr[0] as JsonObject).containsKey("static_raw"))
        assertFalse((arr[0] as JsonObject).containsKey("probe_raw"))
    }

    @Test fun txtIsTabSeparated() {
        val lines = out(ExportFormat.TXT, true).trim().lines()
        assertEquals(3, lines.size)
        assertEquals(8, lines[0].split('\t').size)
        assertTrue(lines[0].startsWith("1.1.1.1\t"))
        assertTrue(lines[0].contains("5/37"))
        assertTrue(lines[0].contains("Проверка по IP не включает SNI и DNS"))
    }
}
