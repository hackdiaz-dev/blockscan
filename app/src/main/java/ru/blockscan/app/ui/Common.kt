package ru.blockscan.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.blockscan.app.App
import ru.blockscan.app.AppContainer
import ru.blockscan.app.domain.Overall
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline build: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as App
    return viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = build(app.container) as T
        },
    )
}

object Fmt {
    fun time(ms: Long?): String =
        if (ms == null) "—" else SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru")).format(Date(ms))

    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return "—"
        val s = ms / 1000
        return when {
            s < 60 -> "$s с"
            s < 3600 -> "${s / 60} мин ${s % 60} с"
            else -> "${s / 3600} ч ${(s % 3600) / 60} мин"
        }
    }

    fun agoIso(iso: String?, now: Long = System.currentTimeMillis()): String? {
        val t = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return null
        val h = (now - t) / 3_600_000
        return when {
            h < 1 -> "менее часа назад"
            h < 48 -> "$h ч назад"
            else -> "${h / 24} дн назад"
        }
    }

    fun isoLocal(iso: String?): String? =
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()?.let { time(it) }

    fun num(n: Long?): String = n?.let { String.format(Locale("ru"), "%,d", it) } ?: "—"
}

@Composable
fun overallColors(o: Overall): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (o) {
        Overall.BLOCKED -> if (dark) Color(0xFF5C1F1F) to Color(0xFFFFDAD6) else Color(0xFFFFDAD6) to Color(0xFF7A1B14)
        Overall.COMPLETE -> if (dark) Color(0xFF14412E) to Color(0xFFBDF2D2) else Color(0xFFD3F4E1) to Color(0xFF0B4A2E)
        Overall.NOT_LISTED -> if (dark) Color(0xFF263B52) to Color(0xFFD0E4FF) else Color(0xFFDCEBFA) to Color(0xFF123B66)
        Overall.DYNAMIC_PENDING, Overall.DYNAMIC_PARTIAL ->
            if (dark) Color(0xFF52410F) to Color(0xFFFFE9A8) else Color(0xFFFFF0C2) to Color(0xFF5A4300)
        Overall.ERROR -> if (dark) Color(0xFF5A3112) to Color(0xFFFFDCC2) else Color(0xFFFFDCC2) to Color(0xFF6B3200)
        Overall.UNKNOWN -> if (dark) Color(0xFF30393D) to Color(0xFFDDE5E8) else Color(0xFFE3E9EB) to Color(0xFF30393D)
    }
}

/** Статус всегда показан и символом, и текстом: цвет не единственный носитель смысла. */
@Composable
fun OverallChip(o: Overall, modifier: Modifier = Modifier, compact: Boolean = false) {
    val (bg, fg) = overallColors(o)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 3.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(o.glyph, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Text(o.ru, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Щит с галочкой, нарисованный кодом. */
@Composable
fun AppLogo(tint: Color, check: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.05f)
            lineTo(w * 0.9f, h * 0.2f)
            lineTo(w * 0.9f, h * 0.5f)
            cubicTo(w * 0.9f, h * 0.76f, w * 0.7f, h * 0.9f, w * 0.5f, h * 0.96f)
            cubicTo(w * 0.3f, h * 0.9f, w * 0.1f, h * 0.76f, w * 0.1f, h * 0.5f)
            lineTo(w * 0.1f, h * 0.2f)
            close()
        }
        drawPath(shield, tint)
        val tick = Path().apply {
            moveTo(w * 0.31f, h * 0.5f)
            lineTo(w * 0.45f, h * 0.64f)
            lineTo(w * 0.7f, h * 0.36f)
        }
        drawPath(tick, check, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Крупная карточка с градиентом: главный акцент экрана. */
@Composable
fun HeroCard(brush: Brush, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            Modifier.background(brush).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** Карточка с цветной полосой слева: цвет повторяет статус результата. */
@Composable
fun StripeCard(
    stripe: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val body: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(stripe))
            Column(
                Modifier.weight(1f).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
    if (onClick != null) {
        ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, content = body)
    } else {
        ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, content = body)
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String?, mono: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(
            value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
            fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val container = accent?.copy(alpha = 0.14f) ?: MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = container, tonalElevation = 1.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent ?: MaterialTheme.colorScheme.onSurface)
            Text(
                label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 2,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("∅", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Пульсирующий индикатор «идёт сканирование». */
@Composable
fun ScanningPulse(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    val s by t.animateFloat(0.8f, 1.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
    Box(modifier.size(14.dp).scale(s).alpha(a).background(MaterialTheme.colorScheme.primary, CircleShape))
}

/** Толстая скруглённая полоса прогресса. */
@Composable
fun ThickProgress(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    )
}

@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = actions,
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}
