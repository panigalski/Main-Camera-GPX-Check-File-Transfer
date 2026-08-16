package com.labpano.gpxextractor.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDisplayPolicyTest {
    @Test
    fun cameraStartLatchStaysRecordingDespiteLongWriteSilence() {
        assertTrue(CaptureDisplayPolicy.isBroadcastCaptureActive(startHintAt = 1_000, hardStopAt = 0))
    }

    @Test
    fun hardStopEndsCameraLatchedCaptureImmediately() {
        assertFalse(CaptureDisplayPolicy.isBroadcastCaptureActive(startHintAt = 1_000, hardStopAt = 9_900))
    }

    @Test
    fun newerStartSupersedesOlderStop() {
        assertTrue(CaptureDisplayPolicy.isBroadcastCaptureActive(startHintAt = 9_500, hardStopAt = 9_000))
    }

    @Test
    fun missingStartHintCannotClaimBroadcastCapture() {
        assertFalse(CaptureDisplayPolicy.isBroadcastCaptureActive(startHintAt = 0, hardStopAt = 0))
    }

    @Test
    fun freshFilesystemFallbackActivityCountsAsCapture() {
        assertTrue(CaptureDisplayPolicy.isFallbackActivityFresh(now = 20_000, lastWriteActivityAt = 19_000))
    }

    @Test
    fun staleFilesystemFallbackActivityExpires() {
        assertFalse(CaptureDisplayPolicy.isFallbackActivityFresh(now = 20_000, lastWriteActivityAt = 4_999))
    }

    @Test
    fun exactFallbackBoundaryStillCountsAsCapture() {
        assertTrue(
            CaptureDisplayPolicy.isFallbackActivityFresh(
                now = 20_000,
                lastWriteActivityAt = 20_000 - CaptureDisplayPolicy.FALLBACK_ACTIVITY_TIMEOUT_MS
            )
        )
    }
}
