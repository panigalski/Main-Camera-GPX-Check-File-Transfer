package com.labpano.gpxextractor

import android.os.SystemClock
import java.util.UUID

/** Monotonic process epoch/order source used by Main-App API responses. */
object AppProcessClock {
    /** Opaque process identity remains unambiguous even if the Pilot device itself reboots. */
    val processInstanceId: String = UUID.randomUUID().toString()
    val processStartedElapsedRealtime: Long = nowElapsedRealtime()

    fun nowElapsedRealtime(): Long = runCatching { SystemClock.elapsedRealtime() }
        .getOrElse { System.nanoTime() / 1_000_000L }
}
