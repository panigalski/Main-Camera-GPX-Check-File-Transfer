package com.labpano.gpxextractor.util

import android.util.Log

object AppLog {
    private const val TAG = "LabpanoGpx"

    fun debug(message: String) = Log.d(TAG, message)
    fun info(message: String) = Log.i(TAG, message)
    fun warn(message: String, throwable: Throwable? = null) = Log.w(TAG, message, throwable)
    fun error(message: String, throwable: Throwable? = null) = Log.e(TAG, message, throwable)
}
