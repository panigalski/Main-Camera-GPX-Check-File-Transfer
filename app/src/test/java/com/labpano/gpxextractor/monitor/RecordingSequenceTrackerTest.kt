package com.labpano.gpxextractor.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSequenceTrackerTest {
    @Test
    fun baselineFilesAreIgnoredAndFirstNewMp4BecomesActive() {
        val tracker = RecordingSequenceTracker(listOf("old1.mp4", "old2.mp4"))

        val result = tracker.observeSnapshot(listOf("old1.mp4", "old2.mp4", "A.mp4"))

        assertEquals(listOf("A.mp4"), result.newlyDiscovered)
        assertTrue(result.newlyReleased.isEmpty())
        assertEquals("A.mp4", result.activePath)
        assertTrue(tracker.isActive("A.mp4"))
    }

    @Test
    fun eachNewMp4ReleasesOnlyItsImmediatePredecessor() {
        val tracker = RecordingSequenceTracker(listOf("old.mp4"))
        tracker.observeSnapshot(listOf("old.mp4", "A.mp4"))

        val b = tracker.observeSnapshot(listOf("old.mp4", "A.mp4", "B.mp4"))
        assertEquals(listOf("A.mp4"), b.newlyReleased)
        assertEquals("B.mp4", b.activePath)

        val c = tracker.observeSnapshot(listOf("old.mp4", "A.mp4", "B.mp4", "C.mp4"))
        assertEquals(listOf("B.mp4"), c.newlyReleased)
        assertEquals("C.mp4", c.activePath)
    }

    @Test
    fun finalStopReleasesLastActiveAndResetsBaselineForNextRecording() {
        val tracker = RecordingSequenceTracker(emptyList())
        tracker.observeSnapshot(listOf("A.mp4"))
        tracker.observeSnapshot(listOf("A.mp4", "B.mp4"))

        val stopped = tracker.finishRecording(listOf("A.mp4", "B.mp4"))
        assertEquals(listOf("B.mp4"), stopped.newlyReleased)
        assertEquals(null, stopped.activePath)
        assertFalse(tracker.isActive("B.mp4"))

        val next = tracker.observeSnapshot(listOf("A.mp4", "B.mp4", "NEXT.mp4"))
        assertEquals(listOf("NEXT.mp4"), next.newlyDiscovered)
        assertTrue(next.newlyReleased.isEmpty())
        assertEquals("NEXT.mp4", next.activePath)
    }

    @Test
    fun multipleFilesFirstSeenTogetherReleaseInOrderAndKeepNewestActive() {
        val tracker = RecordingSequenceTracker(emptyList())

        val result = tracker.observeSnapshot(listOf("A.mp4", "B.mp4", "C.mp4"))

        assertEquals(listOf("A.mp4", "B.mp4", "C.mp4"), result.newlyDiscovered)
        assertEquals(listOf("A.mp4", "B.mp4"), result.newlyReleased)
        assertEquals("C.mp4", result.activePath)
    }
}
