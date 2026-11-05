package ru.blockscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.blockscan.app.domain.HistoryEntry
import ru.blockscan.app.domain.HistoryKind

@Composable
fun HomeScreen(
    onCheck: (String) -> Unit,
    onScan: (String) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onOpenEntry: (HistoryEntry) -> Unit,
) {
    val vm = containerViewModel { HomeViewModel(it) }
    val service by vm.service.collectAsStateWithLifecycle()
    val last by vm.last.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val kind = remember(text) { InputKind.of(text) }

    AppScaffold(
        title = "БлокСкан",
        onBack = null,
        actions = {
            IconButton(onClick = onHistory) { Icon(Icons.Default.List, contentDescription = "История") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Настройки") }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("🛡", style = MaterialTheme.typography.headlineMedium) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Проверка IP, доменов и подсетей", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Клиент сервиса Cheburcheck",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("IP, домен или подсеть CIDR") },
                singleLine = true,
                isError = kind == InputKind.INVALID,
                supportingText = {
                    when (kind) {
                        InputKind.INVALID -> Text("Некорректный IP, домен или CIDR")
                        InputKind.CIDR -> Text("Подсеть: каждый адрес проверяется отдельно")
                        InputKind.DOMAIN -> Text("Домен проверяется целиком; массовое сканирование доступно только для IP и CIDR")
                        else -> Text("Например 1.1.1.1 или 203.0.113.0/24")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onCheck(text.trim()) },
                    enabled = kind == InputKind.IP || kind == InputKind.DOMAIN,
                    modifier = Modifier.weight(1f),
                ) { Text("Проверить") }
                FilledTonalButton(
                    onClick = { onScan(text.trim()) },
                    enabled = kind != InputKind.INVALID && kind != InputKind.DOMAIN,
                    modifier = Modifier.weight(1f),
                ) { Text("Массово") }
            }

            SectionCard("Последняя проверка") {
                val e = last
                if (e == null) {
                    Text("Проверок пока не было", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LastCard(e, onOpen = { onOpenEntry(e) })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(Fmt.num(stats.checked), "проверено адресов", Modifier.weight(1f))
                StatTile(Fmt.num(stats.blocked), "блокировок найдено", Modifier.weight(1f))
            }

            SectionCard("Сервис Cheburcheck") {
                when (val s = service) {
                    ServiceState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Проверяю доступность…")
                    }
                    is ServiceState.Failed -> Text("Недоступен: ${s.message}", color = MaterialTheme.colorScheme.error)
                    is ServiceState.Ready -> {
                        KeyValue("Состояние", if (s.ok) "работает (healthcheck OK)" else "healthcheck не вернул OK")
                        KeyValue("Версия API", s.status.version)
                        val upd = Fmt.isoLocal(s.status.lastUpdate)
                        KeyValue("Базы обновлены", upd?.let { u -> Fmt.agoIso(s.status.lastUpdate)?.let { "$u ($it)" } ?: u })
                        KeyValue("Доменов в базе", Fmt.num(s.status.domainCount))
                        KeyValue("IPv4 в базе", Fmt.num(s.status.v4Count))
                    }
                }
                TextButton(onClick = vm::refreshService) {
                    Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Обновить")
                }
            }

            Text(
                "Данные предоставляет сторонний сервис Cheburcheck (cheburcheck.ru). " +
                    "Приложение неофициальное и не связано с его авторами.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LastCard(e: HistoryEntry, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(e.target, style = MaterialTheme.typography.titleMedium)
        e.overall?.let { OverallChip(it) }
        Text(e.summary, style = MaterialTheme.typography.bodyMedium)
        Text(
            (if (e.kind == HistoryKind.SCAN) "Сканирование • " else "") + Fmt.time(e.ts) + " • " + e.completion,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
