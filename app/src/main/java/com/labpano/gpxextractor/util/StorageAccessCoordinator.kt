package com.labpano.gpxextractor.util

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Coordinates file access performed inside this application.
 *
 * A fixed striped lock table is intentionally used instead of retaining one lock forever for every
 * file path. This keeps memory bounded during long-running deployments with many recordings.
 */
object StorageAccessCoordinator {
    private const val STRIPE_COUNT = 128
    private val locks = Array(STRIPE_COUNT) { ReentrantReadWriteLock(true) }

    fun <T> withRead(file: File, action: () -> T): T {
        val lock = locks[stripeFor(file)].readLock()
        lock.lock()
        return try {
            action()
        } finally {
            lock.unlock()
        }
    }

    fun <T> withWrite(files: Collection<File>, action: () -> T): T {
        val acquired = files
            .map(::stripeFor)
            .distinct()
            .sorted()
            .map { index -> locks[index].writeLock() }
        acquired.forEach { it.lock() }
        return try {
            action()
        } finally {
            acquired.asReversed().forEach { it.unlock() }
        }
    }

    private fun stripeFor(file: File): Int {
        val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return (key.hashCode() and Int.MAX_VALUE) % STRIPE_COUNT
    }
}
