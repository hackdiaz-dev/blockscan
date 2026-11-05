package ru.blockscan.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.blockscan.app.core.IpParser
import ru.blockscan.app.domain.*

/** Экран живой проверки IP или домена. */
@Composable
fun CheckScreen(target: String, onBack: () -> Unit) {
    val vm = containerViewModel(key = "check:$target") { CheckViewModel(it, target) }
    val rec by vm.record.collectAsStateWithLifecycle()
    AppScaffold(
        title = "Проверка IP или домена", onBack = onBack,
        actions = {
            IconButton(onClick = vm::run, enabled = rec.isTerminal) {
                Icon(Icons.Default.Refresh, contentDescription = "Перепроверить")
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) { checkBody(rec) }
    }
}

/** Экран сохранённого результата (из истории или из массового сканирования). */
@Composable
fun StoredRecordScreen(source: RecordSource, key: String, onBack: () -> Unit) {
    val vm = containerViewModel(key = key) { RecordViewModel(it, source) }
    val rec by vm.record.collectAsStateWithLifecycle()
    AppScaffold(title = "Результат проверки", onBack = onBack) { pad ->
        val r = rec
        if (r == null) {
            EmptyState("Нет данных", "Сохранённый результат не найден или ещё загружается", Modifier.padding(pad))
        } else {
            LazyColumn(
                Modifier.padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) { checkBody(r) }
        }
    }
}

private fun LazyListScope.checkBody(rec: CheckRecord) {
    item(key = "regions") { RegionAvailabilityMap(rec) }
    item(key = "header") { HeaderCard(rec) }
    item(key = "static") { StaticCard(rec) }
    item(key = "dynamic") { DynamicCard(rec) }
    item(key = "probes") { ProbeListCard(rec) }
    item(key = "raw") { RawCard(rec) }
}

@Composable
private fun HeaderCard(rec: CheckRecord) {
    val overall = remember(rec) { rec.overall() }
    val summary = remember(rec) { rec.summary() }
    val static = remember(rec.staticRaw) { rec.static() }
    SectionCard("Итог") {
        Text(rec.target, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
        OverallChip(overall)
        if (rec.staticStatus() != StaticStatus.ERROR && rec.staticStatus() != StaticStatus.PENDING) {
            Text("Основание: ${rec.basis()}", style = MaterialTheme.typography.bodyMedium)
        }
        if (summary != null && summary.blockingProbes > 0 && overall == Overall.COMPLETE) {
            Text(
                "Внимание: блокировку увидели ${summary.blockingProbes} из ${summary.totalProbes} сканеров, " +
                    "хотя большинство показывает «${summary.winner?.ru}».",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            )
        }
        if (rec.dyn == DynamicStatus.DONE && static?.rknDomain != null && summary?.winner?.blocking == false) {
            Text(
                "Домен найден в списках РКН, но большинство сканеров показывает доступность.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            )
        }
        if (rec.dyn != DynamicStatus.DONE && rec.staticStatus() != StaticStatus.ERROR) {
            Text(
                "Результат динамической проверки не подтверждён: «${rec.dyn.ru}». " +
                    "Отсутствие в списках не означает, что адрес доступен.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        KeyValue("Проверка начата", Fmt.time(rec.startedAt))
        KeyValue("Статика получена", rec.staticAt?.let(Fmt::time))
        KeyValue("Ответы сканеров получены", rec.dynDoneAt?.let(Fmt::time))
        rec.finishedAt?.let { KeyValue("Длительность", Fmt.duration(it - rec.startedAt)) }
    }
}

@Composable
private fun StaticCard(rec: CheckRecord) {
    val st = remember(rec.staticRaw) { rec.static() }
    SectionCard("Статическая проверка (списки)") {
        KeyValue("Статус", rec.staticStatus().ru)
        rec.staticError?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
        if (st == null) return@SectionCard
        Text(
            "Поле blocked означает только наличие адреса в списках (в том числе в списках CDN). " +
                "Реальную доступность показывают сканеры.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (st.rknDomain != null) {
            Text("В списках РКН", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        }
        if (IpParser.parse(rec.target) != null) {
            Text(
                "Проверка по IP не включает SNI и DNS: для сайтов проверяйте домен.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KeyValue("Тип цели", st.targetType)
        KeyValue("Адреса", st.ips.joinToString(", ").ifEmpty { "—" })
        KeyValue("Размер подсети", st.subnetSize)
        KeyValue("Заблокированные подсети РКН", st.blockedSubnets.joinToString(", ").ifEmpty { "не найдены" })
        KeyValue("Реестр РКН (домен)", st.rknDomain)
        KeyValue("CDN", st.cdn.joinToString("; ") { h -> h.name + (h.cidr?.let { " ($it)" } ?: "") + (h.region?.let { " [$it]" } ?: "") }
            .ifEmpty { "не найден" })
        KeyValue("Белый список", st.whitelist ?: "нет")
        val isDomain = st.targetType?.contains("домен", ignoreCase = true) == true
        KeyValue(if (isDomain) "ASN (по первому IP)" else "ASN", st.asn)
        KeyValue(if (isDomain) "Организация (по первому IP)" else "Организация / провайдер", st.organisation)
        KeyValue("Страна", st.countryCode)
        KeyValue("Регион / город", st.city)
        KeyValue("Расположение", st.location?.takeIf { it != "-" })
        KeyValue("Обратный DNS", st.reverse.joinToString(", "))
        if (st.complaints.isNotEmpty()) {
            val total = st.complaints.sumOf { it.count }
            Text("Жалобы за ${st.complaints.size} дн.: $total", style = MaterialTheme.typography.bodyMedium)
            ComplaintsChart(st.complaints.map { it.count })
        }
        if (st.unknownKeys.isNotEmpty()) {
            Text("Неизвестные поля ответа (сохранены в исходном JSON): ${st.unknownKeys.joinToString()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ComplaintsChart(values: List<Int>) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val max = maxOf(1, values.maxOrNull() ?: 1)
        val w = size.width / values.size
        values.forEachIndexed { i, v ->
            drawRect(track, Offset(i * w + 2f, size.height - 3f), Size(w - 4f, 3f))
            if (v > 0) {
                val h = (size.height - 3f) * v / max
                drawRect(color, Offset(i * w + 2f, size.height - h), Size(w - 4f, h))
            }
        }
    }
}

@Composable
private fun DynamicCard(rec: CheckRecord) {
    val summary = remember(rec.probeRaw.size, rec.staticRaw) { rec.summary() }
    val probes = remember(rec.probeRaw.size) { rec.probes() }
    var showDnsDetails by rememberSaveable(rec.target) { mutableStateOf(false) }
    SectionCard("Динамическая проверка (сканеры)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rec.dyn == DynamicStatus.RUNNING || rec.dyn == DynamicStatus.QUEUED) {
                ScanningPulse(); Spacer(Modifier.width(10.dp))
            }
            Text(rec.dyn.ru, style = MaterialTheme.typography.titleMedium)
        }
        rec.dynNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val got = rec.probeRaw.size
        if (rec.dyn == DynamicStatus.RUNNING || rec.dyn == DynamicStatus.PARTIAL || rec.dyn == DynamicStatus.DONE) {
            val online = rec.online
            if (online != null && online > 0 && rec.dyn != DynamicStatus.DONE) {
                LinearProgressIndicator(progress = { (got.toFloat() / online).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            KeyValue("Сканеров онлайн", online?.toString())
            KeyValue("Ответов получено", got.toString())
            if (rec.dyn == DynamicStatus.DONE) KeyValue("Подтверждено сервисом (response_count)", rec.responseCount?.toString())
        }
        if (summary != null) {
            HorizontalDivider()
            KeyValue("Итог по большинству", summary.winner?.ru ?: "нет однозначного итога")
            KeyValue("Сканеров видят блокировку", "${summary.blockingProbes} из ${summary.totalProbes}")
            summary.counts.entries.sortedBy { it.key.ordinal }.forEach { (v, n) -> KeyValue(v.ru, n.toString()) }
        }
        val dnsProbes = probes.mapNotNull { probe -> probe.dns?.let { probe to it } }
        if (dnsProbes.isNotEmpty()) {
            HorizontalDivider()
            Text("DNS сканеров", style = MaterialTheme.typography.titleSmall)
            KeyValue("Подмена DNS обнаружена", "${dnsProbes.count { it.second.spoofingDetected == true }} из ${dnsProbes.size} сканеров")
            dnsProbes.mapNotNull { it.second.suspiciousProviderCount }.maxOrNull()?.let {
                KeyValue("Подозрительных DNS-провайдеров (максимум)", it.toString())
            }
            dnsProbes.firstOrNull()?.second?.let { dns ->
                if (dns.verdictThreshold != null || dns.samplesPerProtocol != null) {
                    KeyValue("DNS: порог / образцов на протокол", "${dns.verdictThreshold ?: "—"} / ${dns.samplesPerProtocol ?: "—"}")
                }
            }
            val observedAddresses = dnsProbes.flatMap { (_, dns) -> dns.observations.flatMap { it.addresses } }.distinct()
            KeyValue("Адреса в DNS-ответах", observedAddresses.joinToString(", ").ifEmpty { "—" })
            val disagreeing = dnsProbes.count { (_, dns) ->
                dns.observations.filter { it.outcomeType != null }.map {
                    "${it.outcomeType}:${it.addresses.sorted().joinToString(",")}:${it.errorMessage.orEmpty()}"
                }.distinct().size > 1
            }
            KeyValue("Разные ответы внутри сканера", "$disagreeing из ${dnsProbes.size}")
            TextButton(onClick = { showDnsDetails = !showDnsDetails }) {
                Text(if (showDnsDetails) "Скрыть DNS-наблюдения" else "Показать DNS-наблюдения")
            }
            if (showDnsDetails) {
                dnsProbes.forEach { (probe, dns) ->
                    val rows = dns.observations.joinToString("; ") { observation ->
                        val outcome = when (observation.outcomeType) {
                            "Answer" -> observation.addresses.joinToString(",").ifEmpty { "Answer" }
                            "Error" -> "Error${observation.errorMessage?.let { ": $it" }.orEmpty()}"
                            else -> observation.outcomeType ?: "—"
                        }
                        val metadata = buildList {
                            if (observation.responseCodes.isNotEmpty()) add("codes=${observation.responseCodes.joinToString(",")}")
                            observation.ipv4Count?.let { add("IPv4=$it") }
                            observation.ipv6Count?.let { add("IPv6=$it") }
                        }.joinToString(" ")
                        "${observation.provider ?: "?"}/${observation.protocol ?: "?"}: $outcome" +
                            (if (metadata.isNotEmpty()) " [$metadata]" else "") +
                            (if (observation.suspectedSpoofing == true) " (suspected_spoofing)" else "")
                    }
                    Text("${probe.provider ?: "Сканер"}: $rows", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val evidenceCounts = probes.flatMap { it.hostResults }.groupingBy { it.evidenceType ?: "(без типа)" }.eachCount()
        if (evidenceCounts.isNotEmpty()) {
            HorizontalDivider()
            KeyValue("host_results по probe_evidence.type", evidenceCounts.entries
                .sortedBy { it.key }.joinToString(", ") { "${it.key}: ${it.value}" })
        }
        KeyValue("Динамика запущена", rec.dynStartedAt?.let(Fmt::time))
        if (rec.dyn == DynamicStatus.DONE && rec.dynStartedAt != null && rec.dynDoneAt != null) {
            KeyValue("Время ожидания сканеров", Fmt.duration(rec.dynDoneAt - rec.dynStartedAt))
        }
    }
}

@Composable
private fun ProbeListCard(rec: CheckRecord) {
    val probes = remember(rec.probeRaw.size) { rec.probes() }
    if (probes.isEmpty()) return
    val isCdn = remember(rec.staticRaw) { rec.static()?.isCdn == true }
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard("Ответы сканеров (${probes.size})") {
        val shown = if (expanded) probes else probes.take(8)
        shown.forEach { p ->
            val top = VerdictLogic.top(p, isCdn)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(p.provider ?: "—", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(p.region, p.asn).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        (if (top?.blocking == true) "✕ " else "") + (top?.ru ?: "Неясно"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (top?.blocking == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (p.dpiHop != null) Text("DPI хоп ${p.dpiHop}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (probes.size > 8) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Свернуть" else "Показать все") }
    }
}

@Composable
private fun RawCard(rec: CheckRecord) {
    val clip = LocalClipboardManager.current
    var shown by remember { mutableStateOf(false) }
    SectionCard("Исходные ответы сервиса") {
        Text("Сохраняются без изменений для диагностики.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clip.setText(AnnotatedString(rec.staticRaw ?: "")) }, enabled = rec.staticRaw != null) { Text("Копировать check") }
            OutlinedButton(
                onClick = { clip.setText(AnnotatedString(rec.probeRaw.joinToString("\n"))) },
                enabled = rec.probeRaw.isNotEmpty(),
            ) { Text("Копировать probe") }
        }
        TextButton(onClick = { shown = !shown }) { Text(if (shown) "Скрыть JSON" else "Показать JSON (check)") }
        AnimatedVisibility(shown) {
            Text(rec.staticRaw ?: "—", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
