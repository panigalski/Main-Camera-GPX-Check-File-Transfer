package com.labpano.gpxextractor.report

import android.content.Context

/** Small app-private diagnostic record for report I/O failures and recoveries. */
object ReportHealthRegistry {
    data class Snapshot(
        val lastSuccessAt: Long,
        val lastFailureAt: Long,
        val lastOperation: String,
        val lastError: String
    )

    fun success(context: Context, operation: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SUCCESS_AT, System.currentTimeMillis())
            .putString(KEY_OPERATION, operation.take(120))
            .putString(KEY_ERROR, "")
            .apply()
    }

    fun recoveredSuccess(context: Context, operation: String) {
        val current = snapshot(context)
        if (current.lastFailureAt > current.lastSuccessAt) success(context, operation)
    }

    fun failure(context: Context, operation: String, error: Throwable) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_FAILURE_AT, System.currentTimeMillis())
            .putString(KEY_OPERATION, operation.take(120))
            .putString(KEY_ERROR, (error.message ?: error.javaClass.simpleName).take(1000))
            .apply()
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            lastSuccessAt = prefs.getLong(KEY_SUCCESS_AT, 0L),
            lastFailureAt = prefs.getLong(KEY_FAILURE_AT, 0L),
            lastOperation = prefs.getString(KEY_OPERATION, "").orEmpty(),
            lastError = prefs.getString(KEY_ERROR, "").orEmpty()
        )
    }

    private const val PREFS = "report_health"
    private const val KEY_SUCCESS_AT = "last_success_at"
    private const val KEY_FAILURE_AT = "last_failure_at"
    private const val KEY_OPERATION = "last_operation"
    private const val KEY_ERROR = "last_error"
}
