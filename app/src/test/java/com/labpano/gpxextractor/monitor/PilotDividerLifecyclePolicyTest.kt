package com.labpano.gpxextractor.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class PilotDividerLifecyclePolicyTest {
    @Test
    fun repeatedFileChangeDuringFragmentedCaptureIsDividerRestart() {
        assertEquals(
            PilotDividerLifecyclePolicy.FileChangeKind.FRAGMENT_RESTART,
            PilotDividerLifecyclePolicy.classify(hasLatchedVideo = true, fragmentStorageEnabled = true)
        )
    }

    @Test
    fun firstFileChangeStartsNewRecordingGeneration() {
        assertEquals(
            PilotDividerLifecyclePolicy.FileChangeKind.NEW_RECORDING,
            PilotDividerLifecyclePolicy.classify(hasLatchedVideo = false, fragmentStorageEnabled = true)
        )
    }

    @Test
    fun repeatedSignalWithoutFragmentStorageDoesNotMasqueradeAsRollover() {
        assertEquals(
            PilotDividerLifecyclePolicy.FileChangeKind.NEW_RECORDING,
            PilotDividerLifecyclePolicy.classify(hasLatchedVideo = true, fragmentStorageEnabled = false)
        )
    }
}
