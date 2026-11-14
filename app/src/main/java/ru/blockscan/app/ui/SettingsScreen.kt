package ru.blockscan.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.blockscan.app.BuildConfig
import ru.blockscan.app.domain.ThemeMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import ru.blockscan.app.App
import ru.blockscan.app.domain.ApiException
import ru.blockscan.app.domain.IpChecker

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val service by vm.service.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf(s.apiToken) }
    var tokenStatus by remember { mutableStateOf<String?>(null) }
    var tokenChecking by remember { mutableStateOf(false) }

    AppScaffold(title = "Настройки", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("API-токен Cheburcheck") {
                Text(
                    "Нужен для запуска сканеров (динамическая проверка). Без токена работает проверка по спискам. " +
                        "Токен хранится только на этом устройстве и отправляется только на cheburcheck.ru. " +
                        "Запуски сканеров могут расходовать кредиты токена. Токен выдаёт администратор сервиса по заявке.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it; tokenStatus = null },
                    label = { Text("API-токен") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !tokenChecking && tokenInput.isNotBlank(),
                    onClick = {
                        val t = tokenInput.trim()
                        vm.update { it.copy(apiToken = t) }
                        tokenChecking = true
                        tokenStatus = "Проверка…"
                        scope.launch {
                            tokenStatus = try {
                                val n = (appContext as App).container.api.credits()
                                "Токен принят. Кредитов: $n"
                            } catch (e: ApiException) {
                                if (e.httpCode == 401) "Токен не принят (401)" else "Токен сохранён, проверить не удалось: ${IpChecker.describe(e)}"
                            }
                            tokenChecking = false
                        }
                    },
                ) { Text("Сохранить и проверить") }
                TextButton(onClick = { tokenInput = ""; vm.update { it.copy(apiToken = "") }; tokenStatus = "Токен удалён" }) { Text("Удалить токен") }
                tokenStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            SectionCard("Тема") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to "Системная", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная").forEach { (m, l) ->
                        FilterChip(selected = s.theme == m, onClick = { vm.update { it.copy(theme = m) } }, label = { Text(l) })
                    }
                }
            }
            SectionCard("Нагрузка на сервис") {
                Text(
                    "Значения ограничены безопасными пределами: приложение не позволяет превысить лимиты сервиса.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SliderRow("Интервал между запросами", "${s.requestDelayMs} мс", s.requestDelayMs.toFloat(), 300f..5000f, 46) { v ->
                    vm.update { it.copy(requestDelayMs = (v / 100).toInt() * 100L) }
                }
                SliderRow("Параллельных проверок", "${s.concurrency}", s.concurrency.toFloat(), 1f..3f, 1) { v ->
                    vm.update { it.copy(concurrency = v.toInt()) }
                }
                SliderRow("Запусков сканеров в минуту", "${s.probeStartsPerMinute}", s.probeStartsPerMinute.toFloat(), 1f..5f, 3) { v ->
                    vm.update { it.copy(probeStartsPerMinute = v.toInt()) }
                }
                SliderRow("Таймаут ожидания сканеров", "${s.probeTimeoutSec} с", s.probeTimeoutSec.toFloat(), 20f..300f, 27) { v ->
                    vm.update { it.copy(probeTimeoutSec = (v / 10).toInt() * 10) }
                }
                SliderRow("Повторных попыток", "${s.retries}", s.retries.toFloat(), 0f..6f, 5) { v ->
                    vm.update { it.copy(retries = v.toInt()) }
                }
            }
            SectionCard("Подсети") {
                SwitchRow("Исключать адрес сети и broadcast по умолчанию", s.excludeEdges) { b -> vm.update { it.copy(excludeEdges = b) } }
                SwitchRow("Пропускать динамику для частных адресов", s.skipDynamicForNonPublic) { b -> vm.update { it.copy(skipDynamicForNonPublic = b) } }
            }
            SectionCard("Экспорт") {
                SwitchRow("Включать исходные ответы сервиса в JSON", s.exportRaw) { b -> vm.update { it.copy(exportRaw = b) } }
                Text("CSV и TXT содержат сводку и метки времени; полные исходные ответы только в JSON.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Данные") {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Очистить историю") }
                Text("Активное сканирование не удаляется.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Сервис Cheburcheck") {
                when (val sv = service) {
                    ServiceState.Loading -> Text("Загрузка…")
                    is ServiceState.Failed -> Text("Недоступен: ${sv.message}", color = MaterialTheme.colorScheme.error)
                    is ServiceState.Ready -> {
                        KeyValue("Состояние", if (sv.ok) "работает" else "нет ответа OK")
                        KeyValue("Версия API", sv.status.version)
                        KeyValue("Базы обновлены", Fmt.isoLocal(sv.status.lastUpdate))
                        KeyValue("Доменов / IPv4", "${Fmt.num(sv.status.domainCount)} / ${Fmt.num(sv.status.v4Count)}")
                    }
                }
                TextButton(onClick = vm::refresh) { Text("Обновить") }
            }
            SectionCard("О приложении") {
                KeyValue("Версия", BuildConfig.VERSION_NAME)
                Text(
                    "БлокСкан — неофициальный клиент. Данные предоставляет сторонний сервис Cheburcheck (cheburcheck.ru). " +
                        "Приложение обращается только к этому хосту.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Будут удалены завершённые проверки и результаты сканирований.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SliderRow(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = current.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
