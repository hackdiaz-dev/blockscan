package ru.blockscan.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.blockscan.app.core.PlanResult
import ru.blockscan.app.data.ExportFormat
import ru.blockscan.app.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Экран «Массовое сканирование»: настройка диапазона, либо панель текущего/последнего задания. */
@Composable
fun ScanScreen(initialInput: String, onBack: () -> Unit, onOpenItem: (Long, String) -> Unit) {
    val vm = containerViewModel(key = "scan") { ScanViewModel(it, initialInput) }
    val viewJob by vm.viewJob.collectAsStateWithLifecycle()
    AppScaffold(title = "Массовое сканирование", onBack = onBack) { pad ->
        Box(Modifier.padding(pad)) {
            val id = viewJob
            if (id == null) SetupPanel(vm) else JobPanel(id, onNew = vm::newScan, onOpenItem = onOpenItem)
        }
    }
}

/** Экран задания из истории. */
@Composable
fun JobScreen(jobId: Long, onBack: () -> Unit, onOpenItem: (Long, String) -> Unit) {
    AppScaffold(title = "Сканирование", onBack = onBack) { pad ->
        Box(Modifier.padding(pad)) { JobPanel(jobId, onNew = null, onOpenItem = onOpenItem) }
    }
}

@Composable
private fun SetupPanel(vm: ScanViewModel) {
    val s by vm.setup.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    fun begin() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val ok = plan as? PlanResult.Ok
    val count = ok?.set?.count ?: 0L
    val cfg = vm.settings
    val dynamicAll = !(vm.looksPrivate() && cfg.skipDynamicForNonPublic)
    val minSeconds = if (dynamicAll) count * 60L / cfg.probeStartsPerMinute else count * cfg.requestDelayMs / 1000L

    LazyColumn(
        Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(
                "Каждый адрес проверяется отдельным запросом: сначала списки, затем сканеры. " +
                    "Подсеть целиком в сервис не отправляется.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RangeMode.entries.toList()) { m ->
                    FilterChip(selected = s.mode == m, onClick = { vm.update { it.copy(mode = m) } }, label = { Text(m.ru) })
                }
            }
        }
        item {
            when (s.mode) {
                RangeMode.CIDR_ALL, RangeMode.CIDR_TRIM -> OutlinedTextField(
                    value = s.input, onValueChange = { v -> vm.update { it.copy(input = v) } },
                    label = { Text("Подсеть CIDR или IP") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                RangeMode.RANGE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s.from, onValueChange = { v -> vm.update { it.copy(from = v) } },
                        label = { Text("С IP") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = s.to, onValueChange = { v -> vm.update { it.copy(to = v) } },
                        label = { Text("По IP") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                RangeMode.LIST -> OutlinedTextField(
                    value = s.list, onValueChange = { v -> vm.update { it.copy(list = v) } },
                    label = { Text("IP-адреса (по одному в строке)") }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        (plan as? PlanResult.Error)?.let { e -> item { Text(e.message, color = MaterialTheme.colorScheme.error) } }
        ok?.warnings?.forEach { w -> item { Text("• $w", style = MaterialTheme.typography.bodySmall) } }

        info?.let { i ->
            item {
                SectionCard("Калькулятор подсети") {
                    KeyValue("Адрес сети", i.network, mono = true)
                    KeyValue("Маска", i.mask, mono = true)
                    KeyValue("Broadcast", i.broadcast, mono = true)
                    KeyValue("Диапазон", "${i.firstAddress} – ${i.lastAddress}", mono = true)
                    KeyValue("Первый / последний хост", if (i.firstHost != null) "${i.firstHost} / ${i.lastHost}" else null, mono = true)
                    KeyValue("Всего адресов", i.total.toString())
                    KeyValue("Будет проверено", count.toString())
                }
            }
        }
        if (ok != null) {
            item {
                SectionCard("План") {
                    KeyValue("Адресов к проверке", count.toString())
                    KeyValue(
                        "Не менее",
                        Fmt.duration(minSeconds * 1000L) +
                            if (dynamicAll) " (лимит ${cfg.probeStartsPerMinute} запусков сканеров в минуту)" else " (динамика пропускается)",
                    )
                    if (!dynamicAll) Text(
                        "Частные и служебные адреса: по данным сторонних клиентов сервис не запускает для них сканеры, " +
                            "поэтому будет только проверка по спискам. Отключить пропуск можно в настройках.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Button(onClick = { if (count > cfg.confirmThreshold) confirm = true else begin() }, enabled = ok != null, modifier = Modifier.fillMaxWidth()) {
                Text("Начать сканирование")
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Большое сканирование") },
            text = {
                Text(
                    "Будет проверено $count адресов, это займёт не менее ${Fmt.duration(minSeconds * 1000L)}. " +
                        "Сервис чужой: убедитесь, что нагрузка оправдана.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; begin() }) { Text("Запустить") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun JobPanel(jobId: Long, onNew: (() -> Unit)?, onOpenItem: (Long, String) -> Unit) {
    val vm = containerViewModel(key = "job:$jobId") { JobViewModel(it, jobId) }
    val job by vm.job.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val rows by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val exportMessage by vm.exportMessage.collectAsStateWithLifecycle()
    val j = job
    val mine = engine.jobId == jobId

    var menu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(ExportFormat.CSV) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u -> u?.let { vm.export(it, ExportFormat.CSV) } }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { vm.export(it, ExportFormat.JSON) } }
    val txt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { u -> u?.let { vm.export(it, ExportFormat.TXT) } }
    fun export(f: ExportFormat) {
        pending = f
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val base = "blockscan_" + (j?.input ?: "scan").replace(Regex("[^A-Za-z0-9._-]"), "_") + "_" + stamp + "." + f.ext
        when (f) { ExportFormat.CSV -> csv.launch(base); ExportFormat.JSON -> json.launch(base); ExportFormat.TXT -> txt.launch(base) }
    }

    LazyColumn(
        Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SectionCard("Прогресс") {
                if (j == null) {
                    Text("Загрузка…")
                } else {
                    Text(j.input, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    KeyValue("Диапазон", j.rangeDesc)
                    KeyValue("Всего IP", counts.total.toString())
                    val frac = if (counts.total == 0) 0f else counts.finished.toFloat() / counts.total
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (j.state == JobState.RUNNING) { ScanningPulse(); Spacer(Modifier.width(8.dp)) }
                        Text("Проверено ${counts.finished} из ${counts.total} • " + stateText(j.state, mine, engine))
                    }
                    if (mine && engine.status == RunStatus.RUNNING) {
                        engine.etaMs?.let { KeyValue("Осталось примерно", Fmt.duration(it)) }
                    }
                    if (mine && engine.current.isNotEmpty()) KeyValue("Сейчас проверяются", engine.current.joinToString(", "), mono = true)
                    KeyValue("Создано", Fmt.time(j.createdAt))
                    j.finishedAt?.let { KeyValue("Закрыто", Fmt.time(it)) }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(counts.queued.toString(), "ожидает", Modifier.weight(1f))
                StatTile(counts.running.toString(), "выполняется", Modifier.weight(1f))
                StatTile(counts.blocked.toString(), "блокировок", Modifier.weight(1f))
                StatTile(counts.errors.toString(), "ошибок", Modifier.weight(1f))
            }
        }
        exportMessage?.let { message -> item { Text(message, color = if (message.startsWith("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(counts.notBlocked.toString(), "не заблок.", Modifier.weight(1f))
                StatTile(counts.partial.toString(), "частично", Modifier.weight(1f))
                StatTile(counts.unknown.toString(), "неизвестно", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                when (j?.state) {
                    JobState.RUNNING -> {
                        FilledTonalButton(onClick = vm::pause, modifier = Modifier.weight(1f)) { Text("Пауза") }
                        OutlinedButton(onClick = vm::stop, modifier = Modifier.weight(1f)) { Text("Остановить") }
                    }
                    JobState.PAUSED -> {
                        Button(onClick = vm::resume, modifier = Modifier.weight(1f)) { Text("Продолжить") }
                        OutlinedButton(onClick = vm::stop, modifier = Modifier.weight(1f)) { Text("Остановить") }
                    }
                    else -> if (onNew != null) Button(onClick = onNew, modifier = Modifier.weight(1f)) { Text("Новое сканирование") }
                }
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text("Экспорт") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ExportFormat.entries.forEach { f ->
                            DropdownMenuItem(text = { Text(f.ru) }, onClick = { menu = false; export(f) })
                        }
                    }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ItemFilter.entries.toList()) { f ->
                    FilterChip(selected = filter == f, onClick = { vm.filter.value = f }, label = { Text(f.ru) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = search, onValueChange = { vm.search.value = it }, singleLine = true,
                    label = { Text("Поиск по IP") }, modifier = Modifier.weight(1f),
                )
                var sm by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { sm = true }) { Text(sort.ru) }
                    DropdownMenu(expanded = sm, onDismissRequest = { sm = false }) {
                        ItemSort.entries.forEach { o -> DropdownMenuItem(text = { Text(o.ru) }, onClick = { sm = false; vm.sort.value = o }) }
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            item { EmptyState("Нет адресов", "Под выбранный фильтр ничего не подходит") }
        }
        items(rows, key = { it.target }) { row -> ResultRow(row) { onOpenItem(jobId, row.target) } }
    }
}

private fun stateText(s: JobState, mine: Boolean, e: EngineState): String = when (s) {
    JobState.RUNNING -> "идёт"
    JobState.PAUSED -> if (mine && e.status == RunStatus.PAUSED && e.current.isNotEmpty()) "приостанавливается, завершаем ${e.current.size}" else "пауза"
    JobState.STOPPED -> "остановлено"
    JobState.FINISHED -> "завершено"
}

@Composable
private fun ResultRow(row: ItemRow, onClick: () -> Unit) {
    val rec = row.record
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(row.target, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                when {
                    row.overall != null -> OverallChip(row.overall, compact = true)
                    row.state == ItemState.RUNNING -> Text("⟳ Выполняется", style = MaterialTheme.typography.labelMedium)
                    else -> Text("· Ожидает", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (rec != null) {
                Text(
                    "Статика: ${rec.staticStatus().ru} • Динамика: ${rec.dyn.ru}" +
                        (if (row.state == ItemState.RUNNING && rec.probeRaw.isNotEmpty()) " (${rec.probeRaw.size})" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rec.summary()?.let { sm ->
                    if (rec.dyn == DynamicStatus.DONE) Text("Сканеры: ${sm.winner?.ru ?: "неясно"}, блок у ${sm.blockingProbes} из ${sm.totalProbes}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
