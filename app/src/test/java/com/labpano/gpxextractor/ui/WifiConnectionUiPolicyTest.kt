package com.labpano.gpxextractor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiConnectionUiPolicyTest {
    @Test
    fun noNetworkDisablesStartButton() {
        val state = WifiConnectionUiPolicy.resolve(networkConnected = false, serverActive = false)
        assertFalse(state.buttonEnabled)
        assertEquals("START WI-FI CONNECTION", state.buttonText)
        assertFalse(state.showUrl)
    }

    @Test
    fun noNetworkNeverPresentsServerAsRunning() {
        val state = WifiConnectionUiPolicy.resolve(networkConnected = false, serverActive = true)
        assertFalse(state.buttonEnabled)
        assertEquals("START WI-FI CONNECTION", state.buttonText)
        assertFalse(state.showUrl)
    }

    @Test
    fun connectedNetworkAllowsStartAndStopStates() {
        val stopped = WifiConnectionUiPolicy.resolve(networkConnected = true, serverActive = false)
        assertTrue(stopped.buttonEnabled)
        assertEquals("START WI-FI CONNECTION", stopped.buttonText)
        assertFalse(stopped.showUrl)

        val running = WifiConnectionUiPolicy.resolve(networkConnected = true, serverActive = true)
        assertTrue(running.buttonEnabled)
        assertEquals("STOP WI-FI CONNECTION", running.buttonText)
        assertTrue(running.showUrl)
    }
}
