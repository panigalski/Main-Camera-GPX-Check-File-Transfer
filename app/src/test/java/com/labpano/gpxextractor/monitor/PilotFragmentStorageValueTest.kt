package com.labpano.gpxextractor.monitor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PilotFragmentStorageValueTest {
    @Test fun cameraSizeValuesAreParsedExactly() {
        listOf(4, 6, 8, 10).forEach { gb ->
            val parsed = PilotFragmentStorageRegistry.parseLimitValue("${gb}gb")
            assertEquals("size", parsed.type)
            assertEquals(gb, parsed.sizeGb)
            assertEquals("$gb GB", parsed.display)
        }
    }

    @Test fun cameraTimeValuesAreParsedExactly() {
        assertEquals(10, PilotFragmentStorageRegistry.parseLimitValue("10min").durationMinutes)
        assertEquals(30, PilotFragmentStorageRegistry.parseLimitValue("30min").durationMinutes)
        assertEquals(60, PilotFragmentStorageRegistry.parseLimitValue("1h").durationMinutes)
        assertEquals(120, PilotFragmentStorageRegistry.parseLimitValue("2h").durationMinutes)
    }

    @Test fun genericStitchedDirectoryDoesNotGuessBetweenStitchedAndStreetView() {
        val on = PilotFragmentStorageRegistry.ModeSetting(true, true, "6gb", "6 GB")
        val off = PilotFragmentStorageRegistry.ModeSetting(true, false, "", "Off (Unlimited)")
        val snapshot = PilotFragmentStorageRegistry.Snapshot(
            available = true,
            stitched = on,
            unstitched = on,
            streetView = off,
            timeLapse = off,
            updatedAt = 1L,
            source = "test",
            revision = 1L
        )
        val genericStitched = File("/sdcard/DCIM/Videos/Stitched")
        assertEquals(false, PilotFragmentStorageRegistry.enabledForDirectory(genericStitched, snapshot))
        assertEquals(true, PilotFragmentStorageRegistry.enabledForDirectory(genericStitched, snapshot, "stitched"))
        assertEquals(false, PilotFragmentStorageRegistry.enabledForDirectory(genericStitched, snapshot, "streetView"))
    }

    @Test fun refreshThrottleUsesMonotonicElapsedTimeAndResetsAcrossEpochs() {
        assertEquals(false, PilotFragmentStorageRegistry.shouldThrottleRefresh(0L, 1_000L))
        assertEquals(true, PilotFragmentStorageRegistry.shouldThrottleRefresh(1_000L, 1_500L))
        assertEquals(false, PilotFragmentStorageRegistry.shouldThrottleRefresh(1_000L, 20_000L))
        assertEquals(false, PilotFragmentStorageRegistry.shouldThrottleRefresh(10_000L, 1_000L))
        assertEquals(false, PilotFragmentStorageRegistry.shouldThrottleRefresh(1_000L, 1_500L, force = true))
    }

}
