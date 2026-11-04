package ru.blockscan.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.blockscan.app.AppContainer
import ru.blockscan.app.core.Cidr
import ru.blockscan.app.core.IpClassifier
import ru.blockscan.app.core.IpParser
import ru.blockscan.app.core.PlanResult
import ru.blockscan.app.core.ScanPlanner
import ru.blockscan.app.core.SubnetCalc
import ru.blockscan.app.core.SubnetInfo
import ru.blockscan.app.data.ExportFormat
import ru.blockscan.app.data.Exporter
import ru.blockscan.app.data.RecordCodec
import ru.blockscan.app.domain.*

sealed interface ServiceState {
    data object Loading : ServiceState
    data class Ready(val ok: Boolean, val status: ServiceStatus) : ServiceState
    data class Failed(val message: String) : ServiceState
}

enum class InputKind { EMPTY, IP, CIDR, INVALID;
    companion object {
        fun of(text: String): InputKind {
            val t = text.trim()
            return when {
                t.isEmpty() -> EMPTY
                t.contains('/') -> if (Cidr.parse(t) != null) CIDR else INVALID
                IpParser.parse(t) != null -> IP
                else -> INVALID
            }
        }
    }
}

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _service = MutableStateFlow<ServiceState>(ServiceState.Loading)
    val service: StateFlow<ServiceState> = _service.asStateFlow()

    val last: StateFlow<HistoryEntry?> = c.db.observe { c.db.last() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val stats: StateFlow<HistoryStats> = c.db.observe { c.db.stats() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryStats(0, 0))

    init { refreshService() }

    fun refreshService() {
        viewModelScope.launch {
            _service.value = ServiceState.Loading
            _service.value = try {
                val ok = c.api.healthcheck()
                ServiceState.Ready(ok, c.api.status())
            } catch (e: ApiException) {
                ServiceState.Failed(IpChecker.describe(e))
            }
        }
    }
}

/** Живая проверка одного IP; переживает поворот экрана, результат пишется в историю. */
class CheckViewModel(private val c: AppContainer, val target: String) : ViewModel() {
    private val _record = MutableStateFlow(CheckRecord(target = target, startedAt = System.currentTimeMillis()))
    val record: StateFlow<CheckRecord> = _record.asStateFlow()
    private var job: Job? = null
    private var runId = 0L

    init { run() }

    val busy: Boolean get() = !_record.value.isTerminal

    fun run() {
        val thisRun = ++runId
        job?.cancel()
        job = viewModelScope.launch {
            var result = CheckRecord(target = target, startedAt = System.currentTimeMillis())
            _record.value = result
            try {
                result = c.checker.check(target) { update -> result = update; if (runId == thisRun) _record.value = update }
                if (runId == thisRun) _record.value = result
            } finally {
                if (runId == thisRun) withContext(NonCancellable + Dispatchers.IO) { c.db.addSingle(result) }
            }
        }
    }
}

sealed interface RecordSource {
    data class History(val id: Long) : RecordSource
    data class Item(val jobId: Long, val target: String) : RecordSource
}

class RecordViewModel(private val c: AppContainer, private val src: RecordSource) : ViewModel() {
    val record: StateFlow<CheckRecord?> = MutableStateFlow<CheckRecord?>(null).also { flow ->
        viewModelScope.launch(Dispatchers.IO) {
            flow.value = when (src) {
                is RecordSource.History -> RecordCodec.decode(c.db.get(src.id)?.recordJson)
                is RecordSource.Item -> c.db.getItem(src.jobId, src.target)?.record
            }
        }
    }.asStateFlow()
}

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val entries: StateFlow<List<HistoryEntry>> = c.db.observe { c.db.list() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.flow
    fun update(f: (AppSettings) -> AppSettings) = c.settings.update(f)
    fun clearHistory() { viewModelScope.launch(Dispatchers.IO) { c.db.clear() } }

    private val _service = MutableStateFlow<ServiceState>(ServiceState.Loading)
    val service: StateFlow<ServiceState> = _service.asStateFlow()
    init { refresh() }
    fun refresh() {
        viewModelScope.launch {
            _service.value = ServiceState.Loading
            _service.value = try {
                ServiceState.Ready(c.api.healthcheck(), c.api.status())
            } catch (e: ApiException) {
                ServiceState.Failed(IpChecker.describe(e))
            }
        }
    }
}

enum class RangeMode(val ru: String) {
    CIDR_ALL("Все адреса"),
    CIDR_TRIM("Без сети и broadcast"),
    RANGE("Свой диапазон"),
    LIST("Список IP"),
}

data class SetupState(
    val input: String = "",
    val mode: RangeMode = RangeMode.CIDR_ALL,
    val from: String = "",
    val to: String = "",
    val list: String = "",
)

class ScanViewModel(private val c: AppContainer, initialInput: String) : ViewModel() {
    private val _setup = MutableStateFlow(
        SetupState(
            input = initialInput,
            mode = if (c.settings.current.excludeEdges) RangeMode.CIDR_TRIM else RangeMode.CIDR_ALL,
        ),
    )
    val setup: StateFlow<SetupState> = _setup.asStateFlow()
    fun update(f: (SetupState) -> SetupState) { _setup.value = f(_setup.value) }

    val plan: StateFlow<PlanResult?> = _setup.map { computePlan(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, computePlan(_setup.value))

    val info: StateFlow<SubnetInfo?> = _setup.map { s ->
        if (s.mode == RangeMode.CIDR_ALL || s.mode == RangeMode.CIDR_TRIM) Cidr.parse(s.input)?.cidr?.let(SubnetCalc::info) else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _viewJob = MutableStateFlow<Long?>(null)
    val viewJob: StateFlow<Long?> = _viewJob.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { _viewJob.value = c.db.latestUnfinishedJob()?.id }
    }

    private fun computePlan(s: SetupState): PlanResult? = when (s.mode) {
        RangeMode.CIDR_ALL -> if (s.input.isBlank()) null else ScanPlanner.fromCidr(s.input, false)
        RangeMode.CIDR_TRIM -> if (s.input.isBlank()) null else ScanPlanner.fromCidr(s.input, true)
        RangeMode.RANGE -> if (s.from.isBlank() && s.to.isBlank()) null else ScanPlanner.fromRange(s.from, s.to)
        RangeMode.LIST -> if (s.list.isBlank()) null else ScanPlanner.fromList(s.list)
    }

    val settings get() = c.settings.current

    /** Первый адрес диапазона нерутируемый: динамика будет пропущена (если включена такая настройка). */
    fun looksPrivate(): Boolean {
        val s = _setup.value
        val first = when (s.mode) {
            RangeMode.CIDR_ALL, RangeMode.CIDR_TRIM -> Cidr.parse(s.input)?.cidr?.let { IpParser.parse(SubnetCalc.info(it).firstAddress) }
                ?: IpParser.parse(s.input)
            RangeMode.RANGE -> IpParser.parse(s.from)
            RangeMode.LIST -> IpParser.parse(s.list.trim().split(Regex("[\\s,;]+")).firstOrNull().orEmpty())
        }
        return first != null && IpClassifier.isNonPublic(first)
    }

    fun start() {
        val ok = plan.value as? PlanResult.Ok ?: return
        val title = when (_setup.value.mode) {
            RangeMode.CIDR_ALL, RangeMode.CIDR_TRIM -> _setup.value.input.trim()
            else -> ok.set.description
        }
        viewModelScope.launch(Dispatchers.IO) {
            val id = c.engine.createJob(title, ok.set)
            _viewJob.value = id
            c.startScanService(id)
        }
    }

    fun newScan() { _viewJob.value = null }
}

class JobViewModel(private val c: AppContainer, val jobId: Long) : ViewModel() {
    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()
    val filter = MutableStateFlow(ItemFilter.ALL)
    val search = MutableStateFlow("")
    val sort = MutableStateFlow(ItemSort.ORDER)

    val job: StateFlow<JobRow?> = c.db.observe { c.db.getJob(jobId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val counts: StateFlow<JobCounts> = c.db.observe { c.db.counts(jobId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), JobCounts())
    val engine: StateFlow<EngineState> = c.engine.state

    val items: StateFlow<List<ItemRow>> =
        combine(filter, search, sort) { f, s, o -> ItemQuery(f, s, o) }
            .flatMapLatest { q -> c.db.observe { c.db.queryItems(jobId, q) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun pause() = c.engine.pause(jobId)
    /** Запуск сервиса безопасен и для продолжения: движок сам решает, продолжить ли воркеры или стартовать заново. */
    fun resume() = c.startScanService(jobId)
    fun stop() = c.engine.stop(jobId)

    fun export(uri: Uri, format: ExportFormat) {
        viewModelScope.launch {
          try { withContext(Dispatchers.IO) {
            val raw = c.settings.current.exportRaw
            val rows = sequence {
                var off = 0
                while (true) {
                    val page = c.db.queryItems(jobId, ItemQuery(offset = off, limit = 500))
                    if (page.isEmpty()) break
                    yieldAll(page)
                    off += page.size
                }
            }
            val stream = c.context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("Не удалось открыть файл для записи")
            stream.bufferedWriter(Charsets.UTF_8).use {
                Exporter.write(it, format, rows, raw)
            }
          }; _exportMessage.value = "Экспорт завершён" } catch (e: CancellationException) {
              throw e
          } catch (e: Exception) {
              _exportMessage.value = "Ошибка экспорта: ${e.message ?: "не удалось сохранить файл"}"
          }
        }
    }
}
