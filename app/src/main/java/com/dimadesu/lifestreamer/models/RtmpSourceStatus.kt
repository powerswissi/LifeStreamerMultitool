package com.dimadesu.lifestreamer.models

/**
 * Status for individual RTMP sources.
 */
enum class RtmpSourceStatus {
    IDLE,
    BUFFERING,
    READY,
    ERROR
}
