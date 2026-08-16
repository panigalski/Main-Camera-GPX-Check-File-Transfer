package com.labpano.gpxextractor.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesystemFragmentRolloverPolicyTest {
    @Test
    fun firstObservedSuccessorCanBootstrapPreviousFragmentWhenMonitoringStartedMidRecording() {
        val previous = FilesystemFragmentRolloverPolicy.selectBootstrapPredecessor(
            nextStem = "segment_b",
            nextFirstSeenWall = 20_000L,
            nextModifiedAt = 20_000L,
            candidates = listOf(
                FilesystemFragmentRolloverPolicy.Predecessor("history", 1_000L, 1_000L, 1_000L),
                FilesystemFragmentRolloverPolicy.Predecessor("segment_a", 10_000L, 18_000L, 18_000L)
            ),
            orderingToleranceMs = 1_000L
        )
        assertEquals("segment_a", previous)
    }

    @Test
    fun createOnlySuccessorMustPersistBeforeItCanProveRollover() {
        assertFalse(
            FilesystemFragmentRolloverPolicy.successorProvedActive(
                nextActivityCount = 1,
                activityCountAtDetection = 1,
                nextLastChangedElapsed = 20_000L,
                detectedElapsed = 20_000L,
                successorExists = true,
                nowElapsed = 20_500L,
                successorExistenceProofMs = 1_500L
            )
        )
        assertTrue(
            FilesystemFragmentRolloverPolicy.successorProvedActive(
                nextActivityCount = 1,
                activityCountAtDetection = 1,
                nextLastChangedElapsed = 20_000L,
                detectedElapsed = 20_000L,
                successorExists = true,
                nowElapsed = 21_500L,
                successorExistenceProofMs = 1_500L
            )
        )
    }

    @Test
    fun previousFragmentMustBeQuietBeforeRelease() {
        assertFalse(
            FilesystemFragmentRolloverPolicy.previousSettled(
                previousLastChangedElapsed = 19_500L,
                previousLastModifiedWall = 19_500L,
                nowElapsed = 21_000L,
                nowWall = 21_000L,
                settleMs = 2_500L
            )
        )
        assertTrue(
            FilesystemFragmentRolloverPolicy.previousSettled(
                previousLastChangedElapsed = 18_000L,
                previousLastModifiedWall = 18_000L,
                nowElapsed = 21_000L,
                nowWall = 21_000L,
                settleMs = 2_500L
            )
        )
    }
}
