package ru.blockscan.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.blockscan.app.App
import ru.blockscan.app.BuildConfig
import ru.blockscan.app.data.UpdateState
import ru.blockscan.app.data.UpdateStatus
import ru.blockscan.app.data.Updater
import ru.blockscan.app.domain.UpdateLogic

/** Блок «О приложении»: версия, ссылка на репозиторий и обновление с GitHub. */
@Composable
fun AboutCard(autoCheck: Boolean, onAutoCheck: (Boolean) -> Unit) {
    val updater = (LocalContext.current.applicationContext as App).container.updater
    val st by updater.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    SectionCard("О приложении", icon = Icons.Default.Info) {
        KeyValue("Версия", BuildConfig.VERSION_NAME)
        Text(
            "БлокСкан — неофициальный клиент. Данные проверок предоставляет сторонний сервис Cheburcheck (cheburcheck.ru), " +
                "запросы проверок идут только на этот хост. Для обновлений приложение обращается к GitHub.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { uri.openUri(UpdateLogic.REPO_URL) }) { Text("Репозиторий на GitHub") }
        HorizontalDivider()
        Text("Обновления", style = MaterialTheme.typography.titleSmall)
        UpdateStatusBlock(st, updater, uri)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Проверять обновления при запуске", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = autoCheck, onCheckedChange = onAutoCheck)
        }
    }
}

@Composable
private fun UpdateStatusBlock(st: UpdateState, updater: Updater, uri: UriHandler) {
    val rel = st.release
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (st.status) {
        UpdateStatus.IDLE -> {
            Text("Проверка обновлений ещё не выполнялась.", style = MaterialTheme.typography.bodySmall, color = muted)
            FilledTonalButton(onClick = updater::check) { Text("Проверить обновления") }
        }
        UpdateStatus.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Проверяю GitHub…")
        }
        UpdateStatus.UP_TO_DATE -> {
            Text(
                "Установлена последняя версия" + (rel?.let { " (${it.version})" } ?: "") + ".",
                style = MaterialTheme.typography.bodyMedium,
            )
            st.checkedAt?.let { Text("Проверено: ${Fmt.time(it)}", style = MaterialTheme.typography.bodySmall, color = muted) }
            FilledTonalButton(onClick = updater::check) { Text("Проверить снова") }
        }
        UpdateStatus.AVAILABLE -> {
            Text(
                "Доступна версия ${rel?.version.orEmpty()}",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            )
            rel?.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = updater::download, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Скачать обновление")
            }
            rel?.let { TextButton(onClick = { uri.openUri(it.pageUrl) }) { Text("Страница релиза") } }
        }
        UpdateStatus.DOWNLOADING -> {
            Text("Скачивание… ${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            ThickProgress(st.progress)
        }
        UpdateStatus.READY -> {
            Text(
                "Обновление ${rel?.version.orEmpty()} скачано, контрольная сумма и подпись проверены.",
                style = MaterialTheme.typography.bodyMedium,
            )
            st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            Button(onClick = updater::install, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Установить") }
        }
        UpdateStatus.INSTALLING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(st.message ?: "Установка…")
            }
        }
        UpdateStatus.FAILED -> {
            Text(st.message ?: "Ошибка", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = { if (rel != null) updater.download() else updater.check() }) { Text("Повторить") }
            rel?.let { TextButton(onClick = { uri.openUri(it.pageUrl) }) { Text("Страница релиза") } }
        }
    }
}

/** Плашка на главном экране: появляется, когда найдено обновление. */
@Composable
fun UpdateBanner(onClick: () -> Unit) {
    val updater = (LocalContext.current.applicationContext as App).container.updater
    val st by updater.state.collectAsStateWithLifecycle()
    val rel = st.release
    if ((st.status == UpdateStatus.AVAILABLE || st.status == UpdateStatus.READY) && rel != null) {
        ElevatedCard(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Доступно обновление ${rel.version}",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        "Откройте настройки, чтобы скачать и установить",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}
