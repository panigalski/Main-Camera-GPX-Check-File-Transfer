package com.labpano.gpxextractor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.labpano.gpxextractor.AppConfig
import kotlin.math.min

class ProcessedRecordingStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    AppConfig.DATABASE_NAME,
    null,
    AppConfig.DATABASE_VERSION
) {
    data class RetryDecision(
        val attemptCount: Int,
        val nextRetryAt: Long,
        val quarantine: Boolean
    )

    data class PendingGpxEntry(
        val id: String,
        val status: String,
        val completedAt: String,
        val videoName: String,
        val videoPath: String,
        val gpxName: String,
        val gpxPath: String,
        val gpxSizeBytes: Long
    )

    data class TransferJournalEntry(
        val transactionId: String,
        val sourcePath: String,
        val sourceSize: Long,
        val sourceModifiedAt: Long,
        val status: ProcessingStatus,
        val message: String,
        val outputDate: String,
        val outputDirectory: String?,
        val outputTreeUri: String?,
        val destination: String,
        val videoName: String,
        val videoPath: String?,
        val gpxName: String?,
        val gpxPath: String?,
        val gpxSizeBytes: Long,
        val cleanupPending: Boolean,
        val state: String,
        val createdAt: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        createProcessedTable(db)
        createPendingQueueTable(db)
        createTransferJournalTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE processed_recordings ADD COLUMN processor_version INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 3) {
            addColumnIfMissing(db, "processed_recordings", "attempt_count INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "processed_recordings", "first_seen_at INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "processed_recordings", "next_retry_at INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "processed_recordings", "is_final INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE processed_recordings SET is_final = 1 WHERE status IN ('GOOD','FAILED')"
            )
            db.execSQL(
                "UPDATE processed_recordings SET first_seen_at = processed_at WHERE first_seen_at = 0"
            )
        }
        if (oldVersion < 4) createPendingQueueTable(db)
        if (oldVersion < 5) createTransferJournalTable(db)
        if (oldVersion < 6) {
            addColumnIfMissing(db, "transfer_journal", "video_path TEXT")
            addColumnIfMissing(db, "transfer_journal", "gpx_path TEXT")
        }
        // Ensure indexes also exist when upgrading an existing database.
        createProcessedTable(db)
        createPendingQueueTable(db)
        createTransferJournalTable(db)
    }

    private fun createProcessedTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS processed_recordings (
                path TEXT PRIMARY KEY NOT NULL,
                file_size INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                processor_version INTEGER NOT NULL,
                status TEXT NOT NULL,
                message TEXT,
                processed_at INTEGER NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                first_seen_at INTEGER NOT NULL DEFAULT 0,
                next_retry_at INTEGER NOT NULL DEFAULT 0,
                is_final INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_processed_final ON processed_recordings(is_final, processed_at)"
        )
    }

    private fun createPendingQueueTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_gpx_queue (
                id TEXT PRIMARY KEY NOT NULL,
                status TEXT NOT NULL,
                completed_at TEXT NOT NULL,
                video_name TEXT NOT NULL,
                video_path TEXT NOT NULL,
                gpx_name TEXT NOT NULL,
                gpx_path TEXT NOT NULL,
                gpx_size_bytes INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_pending_created ON pending_gpx_queue(created_at)"
        )
    }

    private fun createTransferJournalTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transfer_journal (
                transaction_id TEXT PRIMARY KEY NOT NULL,
                source_path TEXT NOT NULL,
                source_size INTEGER NOT NULL,
                source_modified_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                message TEXT NOT NULL,
                output_date TEXT NOT NULL,
                output_directory TEXT,
                output_tree_uri TEXT,
                destination TEXT NOT NULL,
                video_name TEXT NOT NULL,
                video_path TEXT,
                gpx_name TEXT,
                gpx_path TEXT,
                gpx_size_bytes INTEGER NOT NULL DEFAULT 0,
                cleanup_pending INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_journal_state ON transfer_journal(state, updated_at)"
        )
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, definition: String) {
        runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $definition") }
    }

    fun hasFinalResult(path: String, fileSize: Long, modifiedAt: Long): Boolean {
        readableDatabase.query(
            "processed_recordings",
            arrayOf("is_final"),
            "path = ? AND file_size = ? AND modified_at = ? AND processor_version = ?",
            metadataArgs(path, fileSize, modifiedAt),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) == 1
        }
    }

    fun nextRetryAt(path: String, fileSize: Long, modifiedAt: Long): Long {
        readableDatabase.query(
            "processed_recordings",
            arrayOf("next_retry_at"),
            "path = ? AND file_size = ? AND modified_at = ? AND processor_version = ? AND is_final = 0",
            metadataArgs(path, fileSize, modifiedAt),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    fun beginProcessing(path: String, fileSize: Long, modifiedAt: Long, message: String) {
        val state = readAttemptState(path, fileSize, modifiedAt)
        saveRecord(
            path = path,
            fileSize = fileSize,
            modifiedAt = modifiedAt,
            status = ProcessingStatus.PROCESSING,
            message = message,
            attemptCount = state?.attemptCount ?: 0,
            firstSeenAt = state?.firstSeenAt ?: System.currentTimeMillis(),
            nextRetryAt = 0L,
            isFinal = false
        )
    }

    fun recordRetry(
        path: String,
        fileSize: Long,
        modifiedAt: Long,
        message: String,
        now: Long = System.currentTimeMillis()
    ): RetryDecision {
        val previous = readAttemptState(path, fileSize, modifiedAt)
        val attempts = (previous?.attemptCount ?: 0) + 1
        val firstSeen = previous?.firstSeenAt?.takeIf { it > 0L } ?: now
        val age = (now - firstSeen).coerceAtLeast(0L)
        val quarantine = attempts >= AppConfig.MAX_PROCESSING_ATTEMPTS || age >= AppConfig.MAX_RETRY_AGE_MS
        val exponent = (attempts - 1).coerceIn(0, 20)
        val delay = min(
            AppConfig.RETRY_MAX_DELAY_MS,
            AppConfig.RETRY_BASE_DELAY_MS * (1L shl exponent)
        )
        val retryAt = if (quarantine) 0L else now + delay
        saveRecord(
            path = path,
            fileSize = fileSize,
            modifiedAt = modifiedAt,
            status = ProcessingStatus.ERROR,
            message = message,
            attemptCount = attempts,
            firstSeenAt = firstSeen,
            nextRetryAt = retryAt,
            isFinal = false
        )
        return RetryDecision(attempts, retryAt, quarantine)
    }

    fun saveFinal(
        path: String,
        fileSize: Long,
        modifiedAt: Long,
        status: ProcessingStatus,
        message: String
    ) {
        val state = readAttemptState(path, fileSize, modifiedAt)
        saveRecord(
            path = path,
            fileSize = fileSize,
            modifiedAt = modifiedAt,
            status = status,
            message = message,
            attemptCount = state?.attemptCount ?: 0,
            firstSeenAt = state?.firstSeenAt ?: System.currentTimeMillis(),
            nextRetryAt = 0L,
            isFinal = true
        )
    }

    fun deleteTransientResult(path: String, fileSize: Long, modifiedAt: Long) {
        writableDatabase.delete(
            "processed_recordings",
            "path = ? AND file_size = ? AND modified_at = ? AND processor_version = ? AND is_final = 0",
            metadataArgs(path, fileSize, modifiedAt)
        )
    }

    fun recordMovedTransaction(entry: TransferJournalEntry) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("transaction_id", entry.transactionId)
            put("source_path", entry.sourcePath)
            put("source_size", entry.sourceSize)
            put("source_modified_at", entry.sourceModifiedAt)
            put("status", entry.status.name)
            put("message", entry.message)
            put("output_date", entry.outputDate)
            put("output_directory", entry.outputDirectory)
            put("output_tree_uri", entry.outputTreeUri)
            put("destination", entry.destination)
            put("video_name", entry.videoName)
            put("video_path", entry.videoPath)
            put("gpx_name", entry.gpxName)
            put("gpx_path", entry.gpxPath)
            put("gpx_size_bytes", entry.gpxSizeBytes)
            put("cleanup_pending", if (entry.cleanupPending) 1 else 0)
            put("state", entry.state)
            put("created_at", entry.createdAt)
            put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict(
            "transfer_journal",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun pendingTransactions(): List<TransferJournalEntry> {
        val result = mutableListOf<TransferJournalEntry>()
        readableDatabase.query(
            "transfer_journal",
            JOURNAL_COLUMNS,
            "state != ?",
            arrayOf(STATE_COMMITTED),
            null,
            null,
            "created_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TransferJournalEntry(
                    transactionId = cursor.getString(0),
                    sourcePath = cursor.getString(1),
                    sourceSize = cursor.getLong(2),
                    sourceModifiedAt = cursor.getLong(3),
                    status = ProcessingStatus.valueOf(cursor.getString(4)),
                    message = cursor.getString(5),
                    outputDate = cursor.getString(6),
                    outputDirectory = cursor.getStringOrNull(7),
                    outputTreeUri = cursor.getStringOrNull(8),
                    destination = cursor.getString(9),
                    videoName = cursor.getString(10),
                    videoPath = cursor.getStringOrNull(11),
                    gpxName = cursor.getStringOrNull(12),
                    gpxPath = cursor.getStringOrNull(13),
                    gpxSizeBytes = cursor.getLong(14),
                    cleanupPending = cursor.getInt(15) == 1,
                    state = cursor.getString(16),
                    createdAt = cursor.getLong(17)
                )
            }
        }
        return result
    }

    fun markTransactionCommitted(transactionId: String) {
        val values = ContentValues().apply {
            put("state", STATE_COMMITTED)
            put("cleanup_pending", 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            "transfer_journal",
            values,
            "transaction_id = ?",
            arrayOf(transactionId)
        )
    }

    fun enqueuePendingGpx(entry: PendingGpxEntry) {
        val values = ContentValues().apply {
            put("id", entry.id)
            put("status", entry.status)
            put("completed_at", entry.completedAt)
            put("video_name", entry.videoName)
            put("video_path", entry.videoPath)
            put("gpx_name", entry.gpxName)
            put("gpx_path", entry.gpxPath)
            put("gpx_size_bytes", entry.gpxSizeBytes)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "pending_gpx_queue",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun listPendingGpx(limit: Int, offset: Int): List<PendingGpxEntry> {
        val safeLimit = limit.coerceIn(1, AppConfig.MAX_PENDING_API_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        val result = mutableListOf<PendingGpxEntry>()
        readableDatabase.query(
            "pending_gpx_queue",
            arrayOf(
                "id", "status", "completed_at", "video_name", "video_path",
                "gpx_name", "gpx_path", "gpx_size_bytes"
            ),
            null,
            null,
            null,
            null,
            "created_at ASC",
            "$safeOffset,$safeLimit"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PendingGpxEntry(
                    id = cursor.getString(0),
                    status = cursor.getString(1),
                    completedAt = cursor.getString(2),
                    videoName = cursor.getString(3),
                    videoPath = cursor.getString(4),
                    gpxName = cursor.getString(5),
                    gpxPath = cursor.getString(6),
                    gpxSizeBytes = cursor.getLong(7)
                )
            }
        }
        return result
    }

    fun findPendingGpx(id: String): PendingGpxEntry? {
        if (id.isBlank()) return null
        readableDatabase.query(
            "pending_gpx_queue",
            arrayOf(
                "id", "status", "completed_at", "video_name", "video_path",
                "gpx_name", "gpx_path", "gpx_size_bytes"
            ),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return PendingGpxEntry(
                id = cursor.getString(0),
                status = cursor.getString(1),
                completedAt = cursor.getString(2),
                videoName = cursor.getString(3),
                videoPath = cursor.getString(4),
                gpxName = cursor.getString(5),
                gpxPath = cursor.getString(6),
                gpxSizeBytes = cursor.getLong(7)
            )
        }
    }

    fun pendingGpxCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_gpx_queue", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    fun prune(now: Long = System.currentTimeMillis()) {
        val database = writableDatabase
        database.delete(
            "pending_gpx_queue",
            "created_at < ?",
            arrayOf((now - AppConfig.PENDING_QUEUE_RETENTION_MS).toString())
        )
        database.execSQL(
            "DELETE FROM pending_gpx_queue WHERE id NOT IN " +
                "(SELECT id FROM pending_gpx_queue ORDER BY created_at DESC LIMIT ${AppConfig.MAX_PENDING_QUEUE_ROWS})"
        )
        database.delete(
            "processed_recordings",
            "is_final = 1 AND processed_at < ?",
            arrayOf((now - AppConfig.PENDING_QUEUE_RETENTION_MS).toString())
        )
        database.delete(
            "transfer_journal",
            "state = ? AND updated_at < ?",
            arrayOf(STATE_COMMITTED, (now - 30L * 24L * 60L * 60L * 1000L).toString())
        )
    }

    private data class AttemptState(val attemptCount: Int, val firstSeenAt: Long)

    private fun readAttemptState(path: String, fileSize: Long, modifiedAt: Long): AttemptState? {
        readableDatabase.query(
            "processed_recordings",
            arrayOf("attempt_count", "first_seen_at"),
            "path = ? AND file_size = ? AND modified_at = ? AND processor_version = ?",
            metadataArgs(path, fileSize, modifiedAt),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) AttemptState(cursor.getInt(0), cursor.getLong(1)) else null
        }
    }

    private fun saveRecord(
        path: String,
        fileSize: Long,
        modifiedAt: Long,
        status: ProcessingStatus,
        message: String?,
        attemptCount: Int,
        firstSeenAt: Long,
        nextRetryAt: Long,
        isFinal: Boolean
    ) {
        val values = ContentValues().apply {
            put("path", path)
            put("file_size", fileSize)
            put("modified_at", modifiedAt)
            put("processor_version", AppConfig.PROCESSOR_VERSION)
            put("status", status.name)
            put("message", message)
            put("processed_at", System.currentTimeMillis())
            put("attempt_count", attemptCount)
            put("first_seen_at", firstSeenAt)
            put("next_retry_at", nextRetryAt)
            put("is_final", if (isFinal) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            "processed_recordings",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun metadataArgs(path: String, fileSize: Long, modifiedAt: Long): Array<String> = arrayOf(
        path,
        fileSize.toString(),
        modifiedAt.toString(),
        AppConfig.PROCESSOR_VERSION.toString()
    )

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    companion object {
        const val STATE_MOVED = "MOVED"
        const val STATE_COMMITTED = "COMMITTED"

        private val JOURNAL_COLUMNS = arrayOf(
            "transaction_id", "source_path", "source_size", "source_modified_at", "status",
            "message", "output_date", "output_directory", "output_tree_uri", "destination",
            "video_name", "video_path", "gpx_name", "gpx_path", "gpx_size_bytes",
            "cleanup_pending", "state", "created_at"
        )
    }
}
