package ru.blockscan.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import ru.blockscan.app.domain.*

object RecordCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(r: CheckRecord): String {
        val bytes = json.encodeToString(r).toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
        val encoded = "GZ1:" + Base64.getEncoder().encodeToString(compressed)
        return if (encoded.length < bytes.size) encoded else bytes.toString(Charsets.UTF_8)
    }
    fun decode(s: String?): CheckRecord? = s?.let { raw -> runCatching {
        val value = if (raw.startsWith("GZ1:")) GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(raw.drop(4))))
            .bufferedReader(Charsets.UTF_8).use { it.readText() } else raw
        json.decodeFromString<CheckRecord>(value)
    }.getOrNull() }
}

/**
 * Хранилище на чистом SQLite (SQLiteOpenHelper). Room сознательно не используется:
 * его процессор аннотаций при сборке грузит нативную sqlite-jdbc, что ненадёжно в JVM под Termux.
 */
class SqliteStore(context: Context) : SQLiteOpenHelper(context, "blockscan.db", null, 2), ScanStore, HistoryStore {

    init { setWriteAheadLoggingEnabled(true) }

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kind TEXT NOT NULL, " +
                "target TEXT NOT NULL, overall TEXT, summary TEXT NOT NULL, duration_ms INTEGER, completion TEXT NOT NULL, " +
                "job_id INTEGER, record_json TEXT)",
        )
        db.execSQL(
            "CREATE TABLE scan_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, input TEXT NOT NULL, " +
                "range_desc TEXT NOT NULL, total INTEGER NOT NULL, state TEXT NOT NULL, finished INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE scan_items(job_id INTEGER NOT NULL, target TEXT NOT NULL, seq INTEGER NOT NULL, " +
                "state TEXT NOT NULL, overall TEXT, updated INTEGER NOT NULL DEFAULT 0, record_json TEXT, " +
                "PRIMARY KEY(job_id, target))",
        )
        db.execSQL("CREATE INDEX idx_items_state ON scan_items(job_id, state, seq)")
        db.execSQL("CREATE INDEX idx_items_counts ON scan_items(job_id, state, overall)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_items_counts ON scan_items(job_id, state, overall)")
    }

    private fun notifyChange() { _changes.tryEmit(Unit) }

    // ---------------- ScanStore ----------------

    override fun createJob(input: String, rangeDesc: String, targets: Sequence<String>, total: Int): Long {
        val db = writableDatabase
        var id = 0L
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("created", System.currentTimeMillis()); put("input", input); put("range_desc", rangeDesc)
                put("total", total); put("state", JobState.RUNNING.name)
            }
            id = db.insertOrThrow("scan_jobs", null, cv)
            val st = db.compileStatement("INSERT OR IGNORE INTO scan_items(job_id, target, seq, state) VALUES(?, ?, ?, 'QUEUED')")
            var seq = 0L
            for (t in targets) {
                st.clearBindings()
                st.bindLong(1, id); st.bindString(2, t); st.bindLong(3, seq++)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChange()
        return id
    }

    private fun jobFrom(c: android.database.Cursor) = JobRow(
        id = c.getLong(0), createdAt = c.getLong(1), input = c.getString(2), rangeDesc = c.getString(3),
        total = c.getInt(4), state = runCatching { JobState.valueOf(c.getString(5)) }.getOrDefault(JobState.STOPPED),
        finishedAt = if (c.isNull(6)) null else c.getLong(6),
    )

    private val jobCols = "id, created, input, range_desc, total, state, finished"

    override fun getJob(id: Long): JobRow? =
        readableDatabase.rawQuery("SELECT $jobCols FROM scan_jobs WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) jobFrom(it) else null
        }

    override fun latestUnfinishedJob(): JobRow? =
        readableDatabase.rawQuery(
            "SELECT $jobCols FROM scan_jobs WHERE state IN ('RUNNING','PAUSED') ORDER BY id DESC LIMIT 1", null,
        ).use { if (it.moveToFirst()) jobFrom(it) else null }

    override fun setJobState(id: Long, state: JobState, finishedAt: Long?) {
        val cv = ContentValues().apply {
            put("state", state.name)
            if (finishedAt != null) put("finished", finishedAt)
        }
        writableDatabase.update("scan_jobs", cv, "id=?", arrayOf(id.toString()))
        notifyChange()
    }

    override fun resetRunning(jobId: Long) {
        writableDatabase.execSQL("UPDATE scan_items SET state='QUEUED' WHERE job_id=? AND state='RUNNING'", arrayOf<Any>(jobId))
        notifyChange()
    }

    override fun claimNext(jobId: Long): String? {
        val db = writableDatabase
        var target: String? = null
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT target FROM scan_items WHERE job_id=? AND state='QUEUED' ORDER BY seq LIMIT 1",
                arrayOf(jobId.toString()),
            ).use { if (it.moveToFirst()) target = it.getString(0) }
            target?.let {
                db.execSQL("UPDATE scan_items SET state='RUNNING', updated=? WHERE job_id=? AND target=?",
                    arrayOf<Any>(System.currentTimeMillis(), jobId, it))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (target != null) notifyChange()
        return target
    }

    override fun saveItem(jobId: Long, target: String, state: ItemState, record: CheckRecord) {
        val cv = ContentValues().apply {
            put("state", state.name)
            if (state == ItemState.FINISHED) put("overall", record.overall().name) else putNull("overall")
            put("updated", System.currentTimeMillis())
            put("record_json", RecordCodec.encode(record))
        }
        writableDatabase.update("scan_items", cv, "job_id=? AND target=?", arrayOf(jobId.toString(), target))
        notifyChange()
    }

    override fun counts(jobId: Long): JobCounts {
        var total = 0; var queued = 0; var running = 0; var finished = 0
        var blocked = 0; var notBlocked = 0; var partial = 0; var pending = 0; var errors = 0; var unknown = 0
        readableDatabase.rawQuery(
            "SELECT state, overall, COUNT(*) FROM scan_items WHERE job_id=? GROUP BY state, overall",
            arrayOf(jobId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(2)
                total += n
                when (c.getString(0)) {
                    "QUEUED" -> queued += n
                    "RUNNING" -> running += n
                    else -> {
                        finished += n
                        when (c.getString(1)) {
                            "BLOCKED" -> blocked += n
                            "NOT_LISTED", "COMPLETE" -> notBlocked += n
                            "DYNAMIC_PARTIAL" -> partial += n
                            "DYNAMIC_PENDING" -> pending += n
                            "ERROR" -> errors += n
                            else -> unknown += n
                        }
                    }
                }
            }
        }
        return JobCounts(total, queued, running, finished, blocked, notBlocked, partial, pending, errors, unknown)
    }

    private fun itemFrom(c: android.database.Cursor) = ItemRow(
        target = c.getString(0),
        state = runCatching { ItemState.valueOf(c.getString(1)) }.getOrDefault(ItemState.QUEUED),
        overall = c.getString(2)?.let { s -> runCatching { Overall.valueOf(s) }.getOrNull() },
        record = RecordCodec.decode(c.getString(3)),
        updatedAt = c.getLong(4),
    )

    override fun queryItems(jobId: Long, q: ItemQuery): List<ItemRow> {
        val args = ArrayList<String>()
        val sb = StringBuilder("job_id = ?")
        args += jobId.toString()
        when (q.filter) {
            ItemFilter.ALL -> {}
            ItemFilter.BLOCKED -> sb.append(" AND overall = 'BLOCKED'")
            ItemFilter.NOT_BLOCKED -> sb.append(" AND overall IN ('NOT_LISTED','COMPLETE')")
            ItemFilter.PENDING -> sb.append(" AND (state IN ('QUEUED','RUNNING') OR overall = 'DYNAMIC_PENDING')")
            ItemFilter.PARTIAL -> sb.append(" AND overall = 'DYNAMIC_PARTIAL'")
            ItemFilter.ERRORS -> sb.append(" AND overall = 'ERROR'")
            ItemFilter.UNKNOWN -> sb.append(" AND overall = 'UNKNOWN'")
        }
        if (q.search.isNotBlank()) {
            sb.append(" AND target LIKE ?")
            args += "%${q.search.trim()}%"
        }
        val order = when (q.sort) {
            ItemSort.ORDER -> "seq"
            ItemSort.STATUS -> "COALESCE(overall, 'ZZ'), seq"
            ItemSort.UPDATED -> "updated DESC, seq"
        }
        val sql = "SELECT target, state, overall, record_json, updated FROM scan_items WHERE $sb " +
            "ORDER BY $order LIMIT ${q.limit} OFFSET ${q.offset}"
        val out = ArrayList<ItemRow>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { while (it.moveToNext()) out += itemFrom(it) }
        return out
    }

    override fun getItem(jobId: Long, target: String): ItemRow? =
        readableDatabase.rawQuery(
            "SELECT target, state, overall, record_json, updated FROM scan_items WHERE job_id=? AND target=?",
            arrayOf(jobId.toString(), target),
        ).use { if (it.moveToFirst()) itemFrom(it) else null }

    // ---------------- HistoryStore ----------------

    override fun addSingle(rec: CheckRecord) {
        val overall = rec.overall()
        val st = rec.static()
        val summary = buildString {
            append(overall.ru)
            st?.asn?.let { append(" • ").append(it) }
            if (rec.dyn == DynamicStatus.DONE) rec.summary()?.let { append(" • сканеров: ${it.totalProbes}") }
        }
        val cv = ContentValues().apply {
            put("ts", rec.startedAt); put("kind", HistoryKind.SINGLE.name); put("target", rec.target)
            put("overall", overall.name); put("summary", summary)
            put("duration_ms", (rec.finishedAt ?: System.currentTimeMillis()) - rec.startedAt)
            put("completion", rec.completionLabel()); put("record_json", RecordCodec.encode(rec))
        }
        writableDatabase.insertOrThrow("history", null, cv)
        notifyChange()
    }

    override fun addScan(jobId: Long, input: String, rangeDesc: String, ts: Long) {
        val cv = ContentValues().apply {
            put("ts", ts); put("kind", HistoryKind.SCAN.name); put("target", input)
            put("summary", rangeDesc); put("completion", "В процессе"); put("job_id", jobId)
        }
        writableDatabase.insertOrThrow("history", null, cv)
        notifyChange()
    }

    override fun updateScan(jobId: Long, completion: String, summary: String, durationMs: Long) {
        val cv = ContentValues().apply {
            put("completion", completion); put("summary", summary); put("duration_ms", durationMs)
        }
        writableDatabase.update("history", cv, "job_id=?", arrayOf(jobId.toString()))
        notifyChange()
    }

    private fun histFrom(c: android.database.Cursor) = HistoryEntry(
        id = c.getLong(0), ts = c.getLong(1),
        kind = runCatching { HistoryKind.valueOf(c.getString(2)) }.getOrDefault(HistoryKind.SINGLE),
        target = c.getString(3),
        overall = c.getString(4)?.let { s -> runCatching { Overall.valueOf(s) }.getOrNull() },
        summary = c.getString(5), durationMs = if (c.isNull(6)) null else c.getLong(6),
        completion = c.getString(7), jobId = if (c.isNull(8)) null else c.getLong(8), recordJson = c.getString(9),
    )

    private val histCols = "id, ts, kind, target, overall, summary, duration_ms, completion, job_id, record_json"

    override fun list(limit: Int): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        readableDatabase.rawQuery("SELECT $histCols FROM history ORDER BY ts DESC, id DESC LIMIT $limit", null)
            .use { while (it.moveToNext()) out += histFrom(it) }
        return out
    }

    override fun get(id: Long): HistoryEntry? =
        readableDatabase.rawQuery("SELECT $histCols FROM history WHERE id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) histFrom(it) else null }

    override fun last(): HistoryEntry? =
        readableDatabase.rawQuery("SELECT $histCols FROM history ORDER BY ts DESC, id DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) histFrom(it) else null }

    private fun scalar(sql: String): Long =
        readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun stats(): HistoryStats = HistoryStats(
        checked = scalar("SELECT COUNT(*) FROM history WHERE kind='SINGLE'") +
            scalar("SELECT COUNT(*) FROM scan_items WHERE state='FINISHED'"),
        blocked = scalar("SELECT COUNT(*) FROM history WHERE kind='SINGLE' AND overall='BLOCKED'") +
            scalar("SELECT COUNT(*) FROM scan_items WHERE state='FINISHED' AND overall='BLOCKED'"),
    )

    /** Очистка не трогает активное (идущее или приостановленное) сканирование. */
    override fun clear() {
        val db = writableDatabase
        val active = "(SELECT id FROM scan_jobs WHERE state IN ('RUNNING','PAUSED'))"
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM history WHERE job_id IS NULL OR job_id NOT IN $active")
            db.execSQL("DELETE FROM scan_items WHERE job_id NOT IN $active")
            db.execSQL("DELETE FROM scan_jobs WHERE id NOT IN $active")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChange()
    }
}
