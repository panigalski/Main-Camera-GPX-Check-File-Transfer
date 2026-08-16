package com.labpano.gpxextractor.monitor

/** Pure classification of Camera 5.18.11's repeated gallery.fileChange callback. */
internal object PilotDividerLifecyclePolicy {
    enum class FileChangeKind { NEW_RECORDING, FRAGMENT_RESTART }

    fun classify(hasLatchedVideo: Boolean, fragmentStorageEnabled: Boolean): FileChangeKind =
        if (hasLatchedVideo && fragmentStorageEnabled) FileChangeKind.FRAGMENT_RESTART
        else FileChangeKind.NEW_RECORDING
}
