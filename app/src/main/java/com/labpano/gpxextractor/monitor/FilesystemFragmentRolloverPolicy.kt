package com.labpano.gpxextractor.monitor

/**
 * Pure policy helpers for filesystem-proven Fragment Storage rollover.
 *
 * These helpers deliberately know nothing about Pilot broadcasts or camera.getOptions. Camera
 * 5.18.11's stock Divider proves the predecessor's low-level recorder stopped before the successor
 * is started, so the caller combines successor activity with a predecessor quiet/stat check.
 */
internal object FilesystemFragmentRolloverPolicy {
    data class Predecessor(
        val stem: String,
        val firstSeenWall: Long,
        val observedModifiedAt: Long,
        val finalizedModifiedAt: Long
    )

    fun selectBootstrapPredecessor(
        nextStem: String,
        nextFirstSeenWall: Long,
        nextModifiedAt: Long,
        candidates: List<Predecessor>,
        orderingToleranceMs: Long
    ): String? = candidates
        .asSequence()
        .filter { it.stem != nextStem }
        .filter {
            it.firstSeenWall <= nextFirstSeenWall + orderingToleranceMs &&
                it.finalizedModifiedAt <= nextModifiedAt + orderingToleranceMs
        }
        .maxByOrNull { maxOf(it.finalizedModifiedAt, it.observedModifiedAt) }
        ?.stem

    fun successorProvedActive(
        nextActivityCount: Int,
        activityCountAtDetection: Int,
        nextLastChangedElapsed: Long,
        detectedElapsed: Long,
        successorExists: Boolean,
        nowElapsed: Long,
        successorExistenceProofMs: Long
    ): Boolean =
        nextActivityCount > activityCountAtDetection ||
            (nextActivityCount >= 2 && nextLastChangedElapsed >= detectedElapsed) ||
            (nextActivityCount >= 1 && successorExists &&
                nowElapsed - detectedElapsed >= successorExistenceProofMs)

    fun previousSettled(
        previousLastChangedElapsed: Long,
        previousLastModifiedWall: Long,
        nowElapsed: Long,
        nowWall: Long,
        settleMs: Long
    ): Boolean {
        if (previousLastChangedElapsed > 0L && nowElapsed - previousLastChangedElapsed < settleMs) return false
        if (previousLastModifiedWall > 0L && nowWall - previousLastModifiedWall < settleMs) return false
        return true
    }
}
