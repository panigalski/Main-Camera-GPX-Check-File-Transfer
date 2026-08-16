package com.labpano.gpxextractor.mp4

data class IsoBox(
    val type: String,
    val offset: Long,
    val size: Long,
    val headerSize: Int
) {
    val contentOffset: Long get() = offset + headerSize
    val endOffset: Long get() = offset + size
    val contentSize: Long get() = size - headerSize
}
