package com.labpano.gpxextractor.ui

/**
 * Single source of truth for the network-aware Wi-Fi connection button state.
 * The file server cannot be started or left running without a usable local network.
 */
object WifiConnectionUiPolicy {
    data class State(
        val buttonEnabled: Boolean,
        val buttonText: String,
        val showUrl: Boolean
    )

    fun resolve(networkConnected: Boolean, serverActive: Boolean): State {
        if (!networkConnected) {
            return State(
                buttonEnabled = false,
                buttonText = "START WI-FI CONNECTION",
                showUrl = false
            )
        }
        return if (serverActive) {
            State(
                buttonEnabled = true,
                buttonText = "STOP WI-FI CONNECTION",
                showUrl = true
            )
        } else {
            State(
                buttonEnabled = true,
                buttonText = "START WI-FI CONNECTION",
                showUrl = false
            )
        }
    }
}
