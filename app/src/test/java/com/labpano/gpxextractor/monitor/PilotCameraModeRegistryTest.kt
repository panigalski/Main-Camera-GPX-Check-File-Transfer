package com.labpano.gpxextractor.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PilotCameraModeRegistryTest {
    @Test
    fun cameraFragmentPropertyPrefixIdentifiesRecordingFamily() {
        assertEquals("stitched", PilotCameraModeRegistry.modeForPropertyPrefix("video"))
        assertEquals("unstitched", PilotCameraModeRegistry.modeForPropertyPrefix("video_fishEye"))
        assertEquals("streetView", PilotCameraModeRegistry.modeForPropertyPrefix("video_streetView"))
        assertEquals("timeLapse", PilotCameraModeRegistry.modeForPropertyPrefix("video_timeLapse"))
        assertEquals("", PilotCameraModeRegistry.modeForPropertyPrefix("unknown"))
    }

    @Test
    fun stitchedFolderIsIntentionallyAmbiguousButUnstitchedIsConcrete() {
        assertNull(PilotCameraModeRegistry.modeForActiveVideoPath("/sdcard/DCIM/Videos/Stitched/VID.mp4"))
        assertEquals(
            "unstitched",
            PilotCameraModeRegistry.modeForActiveVideoPath("/sdcard/DCIM/Videos/Unstitched/VID.mp4")
        )
    }
    @Test
    fun fragmentStorageModeHintExpiresInsteadOfBecomingPermanentCurrentMode() {
        val observed = 1_000L
        assertEquals(true, PilotCameraModeRegistry.isModeHintFresh(observed, observed + PilotCameraModeRegistry.MODE_HINT_TTL_MS))
        assertEquals(false, PilotCameraModeRegistry.isModeHintFresh(observed, observed + PilotCameraModeRegistry.MODE_HINT_TTL_MS + 1L))
        assertEquals(false, PilotCameraModeRegistry.isModeHintFresh(observed, observed - 1L))
    }

}
