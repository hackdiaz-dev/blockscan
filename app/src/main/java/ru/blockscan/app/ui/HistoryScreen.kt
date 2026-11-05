package ru.blockscan.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.blockscan.app.domain.HistoryEntry
import ru.blockscan.app.domain.HistoryKind

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (HistoryEntry) -> Unit) {
    val vm = containerViewModel { HistoryViewModel(it) }
    val list by vm.entries.collectAsStateWithLifecycle()
    AppScaffold(title = "История", onBack = onBack) { pad ->
        if (list.isEmpty()) {
            EmptyState("История пуста", "Здесь появятся одиночные проверки и массовые сканирования", Modifier.padding(pad))
        } else {
            LazyColumn(
                Modifier.padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(list, key = { it.id }) { e ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpen(e) }, shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(e.target, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                e.overall?.let { OverallChip(it, compact = true) }
                            }
                            Text(
                                (if (e.kind == HistoryKind.SCAN) "Сканирование подсети" else "Проверка IP/домена") + " • " + Fmt.time(e.ts),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(e.summary, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${e.completion} • длительность ${Fmt.duration(e.durationMs)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
