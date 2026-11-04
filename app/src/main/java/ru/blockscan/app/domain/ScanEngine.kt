package ru.blockscan.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.blockscan.app.core.TargetSet
import java.util.concurrent.ConcurrentHashMap

enum class RunStatus { IDLE, RUNNING, PAUSED, STOPPED, FINISHED }

data class EngineState(
    val jobId: Long? = null,
    val status: RunStatus = RunStatus.IDLE,
    val counts: JobCounts = JobCounts(),
    val current: List<String> = emptyList(),
    /** Оценка оставшегося времени по фактической скорости последних проверок; null, если данных мало. */
    val etaMs: Long? = null,
    val title: String = "",
)

/**
 * Очередь массового сканирования. Состояние каждого адреса пишется в хранилище, поэтому
 * прогресс переживает сворачивание и перезапуск процесса. Лимит запусков проб общий (StartLimiter).
 */
class ScanEngine(
    private val checker: IpChecker,
    private val store: ScanStore,
    private val history: HistoryStore,
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var workers: Job? = null
    private var activeJobId: Long? = null
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val lastPersist = ConcurrentHashMap<String, Long>()
    private val times = ArrayDeque<Long>()

    fun createJob(input: String, set: TargetSet): Long {
        val id = store.createJob(input, set.description, set.sequence(), set.count.toInt())
        history.addScan(id, input, set.description, clock())
        return id
    }

    /** Вызывается при старте процесса: прерванное сканирование переводится в паузу, незавершённые адреса — в очередь. */
    fun recoverInterrupted() {
        val job = store.latestUnfinishedJob() ?: return
        if (job.state == JobState.RUNNING) {
            store.resetRunning(job.id)
            store.setJobState(job.id, JobState.PAUSED)
            updateHistory(job.id, "Прервано (процесс остановлен), можно продолжить")
        }
    }

    fun start(jobId: Long): Boolean {
        if (workers?.isActive == true) {
            if (activeJobId == jobId) resume(jobId)
            return activeJobId == jobId
        }
        val job = store.getJob(jobId) ?: return false
        if (job.state == JobState.STOPPED || job.state == JobState.FINISHED) return false
        activeJobId = jobId
        store.resetRunning(jobId)
        store.setJobState(jobId, JobState.RUNNING)
        paused.value = false
        synchronized(times) { times.clear() }
        publish(RunStatus.RUNNING)
        val n = settings().sanitized().concurrency
        workers = scope.launch {
            coroutineScope { repeat(n) { launch { workerLoop(jobId) } } }
            finish(jobId)
        }
        return true
    }

    fun resume(jobId: Long) {
        if (workers?.isActive == true && activeJobId == jobId) {
            synchronized(times) { times.clear() }
            store.setJobState(jobId, JobState.RUNNING)
            paused.value = false
            publish(RunStatus.RUNNING)
            updateHistory(jobId, "В процессе")
        } else {
            start(jobId)
        }
    }

    /** Мягкая пауза: уже запущенные проверки доводятся до конца, новые не берутся. */
    fun pause(jobId: Long) {
        if (activeJobId != jobId) return
        paused.value = true
        store.setJobState(jobId, JobState.PAUSED)
        publish(RunStatus.PAUSED)
        updateHistory(jobId, "Приостановлено")
    }

    /** Жёсткая остановка: текущие запросы отменяются, адреса возвращаются в очередь, задача закрывается. */
    fun stop(jobId: Long) {
        if (activeJobId != jobId) return
        scope.launch {
            if (activeJobId != jobId) return@launch
            workers?.cancelAndJoin()
            workers = null
            running.clear()
            store.resetRunning(jobId)
            store.setJobState(jobId, JobState.STOPPED, clock())
            publish(RunStatus.STOPPED)
            updateHistory(jobId, "Остановлено пользователем")
        }
    }

    private suspend fun workerLoop(jobId: Long) {
        while (true) {
            paused.first { !it }
            val target = store.claimNext(jobId) ?: return
            running.add(target)
            publish(currentStatus())
            var rec = CheckRecord(target = target, startedAt = clock())
            try {
                rec = checker.check(target) { upd ->
                    rec = upd
                    persistProgress(jobId, target, upd)
                }
                store.saveItem(jobId, target, ItemState.FINISHED, rec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rec = rec.copy(staticError = rec.staticError ?: "Внутренняя ошибка: ${e.message}", finishedAt = clock())
                store.saveItem(jobId, target, ItemState.FINISHED, rec)
            } finally {
                running.remove(target)
                lastPersist.remove(target)
            }
            synchronized(times) {
                times.addLast(clock())
                while (times.size > 10) times.removeFirst()
            }
            publish(currentStatus())
        }
    }

    private fun persistProgress(jobId: Long, target: String, rec: CheckRecord) {
        if (rec.isTerminal) return
        val now = clock()
        val last = lastPersist[target] ?: 0L
        if (now - last >= 500L) {
            store.saveItem(jobId, target, ItemState.RUNNING, rec)
            lastPersist[target] = now
        }
    }

    private fun finish(jobId: Long) {
        val c = store.counts(jobId)
        if (c.queued == 0 && c.running == 0) {
            store.setJobState(jobId, JobState.FINISHED, clock())
            publish(RunStatus.FINISHED)
            updateHistory(jobId, "Завершено")
        }
    }

    private fun currentStatus() = if (paused.value) RunStatus.PAUSED else RunStatus.RUNNING

    private fun publish(status: RunStatus) {
        val id = activeJobId ?: return
        val counts = store.counts(id)
        _state.value = EngineState(
            jobId = id,
            status = status,
            counts = counts,
            current = running.toList().sorted(),
            etaMs = if (status == RunStatus.RUNNING) eta(counts) else null,
            title = store.getJob(id)?.input.orEmpty(),
        )
    }

    private fun eta(c: JobCounts): Long? {
        val t = synchronized(times) { times.toList() }
        if (t.size < 4) return null
        val span = t.last() - t.first()
        if (span <= 0L) return null
        val rate = (t.size - 1).toDouble() / span
        return ((c.queued + c.running) / rate).toLong()
    }

    private fun updateHistory(jobId: Long, completion: String) {
        val c = store.counts(jobId)
        val job = store.getJob(jobId) ?: return
        val summary = "${c.finished} из ${c.total} проверено, заблокировано ${c.blocked}, ошибок ${c.errors}"
        history.updateScan(jobId, completion, summary, (job.finishedAt ?: clock()) - job.createdAt)
    }
}
