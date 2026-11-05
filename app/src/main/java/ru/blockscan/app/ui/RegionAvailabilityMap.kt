package ru.blockscan.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.blockscan.app.domain.CheckRecord
import ru.blockscan.app.domain.ProbeResult
import ru.blockscan.app.domain.Verdict
import ru.blockscan.app.domain.VerdictLogic
import ru.blockscan.app.domain.probes
import ru.blockscan.app.domain.static
import java.util.Locale
import java.util.zip.GZIPInputStream

private const val MAP_WIDTH = 1000f
private const val MAP_HEIGHT = 390f
private val ACCESSIBLE = Color(0xFF36A872)
private val BLOCKED = Color(0xFFD64B4B)
private val MIXED = Color(0xFFD69A35)
private val NO_DATA = Color(0xFF536170)

private data class MapShape(val name: String, val path: Path)
private data class MapData(
    val districts: List<MapShape>,
    val crimea: MapShape?,
    val districtByRegion: Map<String, String>,
)
private data class RegionCount(val accessible: Int, val blocked: Int, val uncertain: Int) {
    val total get() = accessible + blocked + uncertain
    /** Точки показывают смешанный результат, округа — доступность хотя бы из одной точки. */
    val pointColor get() = when {
        accessible > 0 && blocked > 0 -> MIXED
        accessible > 0 -> ACCESSIBLE
        blocked > 0 -> BLOCKED
        else -> NO_DATA
    }
    val districtColor get() = when {
        accessible > 0 -> ACCESSIBLE
        blocked > 0 -> BLOCKED
        else -> NO_DATA
    }
}

private object RegionMapAsset {
    @Volatile private var cached: MapData? = null

    fun load(context: Context): MapData = cached ?: synchronized(this) {
        cached ?: context.assets.open("russia-federal-districts.mapdata").use { compressed ->
            val districts = mutableListOf<MapShape>()
            var crimea: MapShape? = null
            val mapping = mutableMapOf<String, String>()
            GZIPInputStream(compressed).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val columns = line.split('\t', limit = 3)
                    when (columns.firstOrNull()) {
                        "D" -> if (columns.size == 3) parseShape(columns[1], columns[2])?.let(districts::add)
                        "C" -> if (columns.size == 3) crimea = parseShape(columns[1], columns[2])
                        "R" -> if (columns.size == 3) mapping[normalizeRegion(columns[1])] = columns[2]
                    }
                }
            }
            MapData(districts, crimea, mapping).also { cached = it }
        }
    }

    private fun parseShape(name: String, encoded: String): MapShape {
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            encoded.split('|').forEach { ring ->
                val points = ring.split(';')
                if (points.size < 4) return@forEach
                points.forEachIndexed { index, point ->
                    val xy = point.split(',', limit = 2)
                    if (xy.size != 2) return@forEachIndexed
                    val x = xy[0].toFloatOrNull() ?: return@forEachIndexed
                    val y = xy[1].toFloatOrNull() ?: return@forEachIndexed
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
        return MapShape(name, path)
    }
}

@Composable
internal fun RegionAvailabilityMap(record: CheckRecord) {
    val context = LocalContext.current.applicationContext
    val map = produceState<MapData?>(null, context) {
        value = withContext(Dispatchers.IO) { RegionMapAsset.load(context) }
    }.value
    val isCdn = record.static()?.isCdn == true
    val districts = map?.districts.orEmpty()
    val probesByDistrict = mutableMapOf<String, MutableList<ProbeResult>>()
    val crimeaProbes = mutableListOf<ProbeResult>()
    val specialProbes = mutableMapOf("ДНР" to mutableListOf<ProbeResult>(), "ЛНР" to mutableListOf<ProbeResult>())
    val specialAliases = mapOf(
        "донецкаянароднаяреспублика" to "ДНР", "днр" to "ДНР",
        "луганскаянароднаяреспублика" to "ЛНР", "лнр" to "ЛНР",
    )
    val aliases = mapOf(
        normalizeRegion("Москва") to normalizeRegion("Город Москва столица Российской Федерации город федерального значения"),
        normalizeRegion("Санкт-Петербург") to normalizeRegion("Город Санкт-Петербург город федерального значения"),
        normalizeRegion("Севастополь") to normalizeRegion("Город федерального значения Севастополь"),
        normalizeRegion("Крым") to normalizeRegion("Республика Крым"),
        normalizeRegion("Республика Башкортостан") to normalizeRegion("Республика Башкирия"),
        normalizeRegion("Республика Тыва") to normalizeRegion("Республика Тува"),
        normalizeRegion("Республика Северная Осетия — Алания") to normalizeRegion("Республика Северная Осетия"),
        normalizeRegion("Кемеровская область — Кузбасс") to normalizeRegion("Кемеровская область"),
        normalizeRegion("Ханты-Мансийский автономный округ — Югра") to normalizeRegion("Ханты-Мансийский АО"),
        normalizeRegion("Ямало-Ненецкий автономный округ") to normalizeRegion("Ямало-Ненецкий АО"),
    )
    record.probes().forEach { probe ->
        val normalized = normalizeRegion(probe.region.orEmpty())
        val special = specialAliases[normalized]
        when {
            special != null -> specialProbes.getValue(special).add(probe)
            normalized == normalizeRegion("Крым") || normalized == normalizeRegion("Республика Крым") ||
                normalized == normalizeRegion("Севастополь") || normalized == normalizeRegion("Город федерального значения Севастополь") -> crimeaProbes.add(probe)
            else -> {
                val key = aliases[normalized] ?: normalized
                map?.districtByRegion?.get(key)?.let { district -> probesByDistrict.getOrPut(district) { mutableListOf() }.add(probe) }
            }
        }
    }
    // Имена округов в GeoJSON и таблице субъектов различаются регистром.
    val counts = probesByDistrict.mapKeys { normalizeRegion(it.key) }.mapValues { countRegion(it.value, isCdn) }
    val crimeaCount = countRegion(crimeaProbes, isCdn)
    val specialCounts = specialProbes.mapValues { countRegion(it.value, isCdn) }
    val districtStates = districts.map { it to counts[normalizeRegion(it.name)] }
    val available = districtStates.count { it.second?.accessible?.let { n -> n > 0 } == true }
    val blocked = districtStates.count { it.second?.let { c -> c.accessible == 0 && c.blocked > 0 } == true }
    val noData = districts.size - districtStates.count { it.second?.let { c -> c.accessible + c.blocked > 0 } == true }

    SectionCard("Доступность по федеральным округам") {
        Text(
            "Округ зелёный, если хотя бы один сканер в нём достучался; красный, если доступных ответов нет, но есть блокировки. Неясные ответы не учитываются.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val mapHeight = maxWidth * (MAP_HEIGHT / MAP_WIDTH)
            Canvas(Modifier.fillMaxWidth().height(mapHeight)) {
                val sx = size.width / MAP_WIDTH
                val sy = size.height / MAP_HEIGHT
                withTransform({ scale(sx, sy, Offset.Zero) }) {
                    districtStates.forEach { (shape, state) ->
                        drawPath(shape.path, state?.districtColor ?: NO_DATA, alpha = if (state == null) 0.55f else 1f)
                        drawPath(shape.path, Color(0xFF15212A), style = Stroke(width = 1.1f / sx.coerceAtLeast(0.1f)))
                    }
                    map?.crimea?.let { shape ->
                        drawPath(shape.path, crimeaCount.districtColor)
                        drawPath(shape.path, Color(0xFF15212A), style = Stroke(width = 1.1f / sx.coerceAtLeast(0.1f)))
                    }
                    // Приблизительные точки смещены влево от исходного положения, чтобы не закрывать контур округа.
                    listOf(Offset(82f, 315f) to "ДНР", Offset(96f, 304f) to "ЛНР").forEach { (point, name) ->
                        val color = specialCounts[name]?.pointColor ?: NO_DATA
                        drawCircle(color.copy(alpha = 0.35f), radius = 10f, center = point)
                        drawCircle(color, radius = 5f, center = point)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LegendDot(ACCESSIBLE, "Доступно $available")
            LegendDot(BLOCKED, "Блок $blocked")
            LegendDot(NO_DATA, "Нет данных $noData")
        }
        Text(
            "ДНР и ЛНР отмечены точками: зелёная — доступно, красная — блокировка, жёлтая — смешанные ответы. Из-за спорного статуса и отсутствия на OpenStreetMap они не нанесены на карту.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Границы округов: OpenStreetMap, обработка timurkanaz/Russia_geojson_OSM. Крым и Севастополь: alt-dmitry/RussianMap (OSM/GIS-Lab).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

private fun countRegion(probes: List<ProbeResult>, isCdn: Boolean): RegionCount {
    var accessible = 0
    var blocked = 0
    var uncertain = 0
    probes.forEach { probe ->
        when (VerdictLogic.top(probe, isCdn)) {
            Verdict.TSPU_BLOCK, Verdict.SNI_BLOCK, Verdict.DNS_SPOOFING, Verdict.CDN_BLOCK -> blocked++
            Verdict.OK, Verdict.WHITELIST -> accessible++
            else -> uncertain++
        }
    }
    return RegionCount(accessible, blocked, uncertain)
}

private fun normalizeRegion(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace('ё', 'е')
    .filter(Char::isLetterOrDigit)
