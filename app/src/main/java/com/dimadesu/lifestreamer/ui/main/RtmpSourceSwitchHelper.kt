package com.dimadesu.lifestreamer.ui.main

import com.swissi.lifestreamer.multitool.R

import android.app.Application
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.datasource.rtmp.RtmpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import android.graphics.Bitmap
import io.github.thibaultbee.streampack.core.elements.sources.video.bitmap.BitmapSourceFactory
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.media.projection.MediaProjection
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionAudioSourceFactory
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionHelper
import com.dimadesu.lifestreamer.player.SrtDataSourceFactory
import com.dimadesu.lifestreamer.player.TsOnlyExtractorFactory
import kotlinx.coroutines.isActive

import com.dimadesu.lifestreamer.models.RtmpSourceStatus

internal object RtmpSourceSwitchHelper {
    private const val TAG = "RtmpSourceSwitchHelper"

    /**
     * Check if the URL is an SRT URL.
     */
    private fun isSrtUrl(url: String): Boolean {
        return url.lowercase().startsWith("srt://")
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun createExoPlayer(application: Application, url: String): ExoPlayer =
        withContext(Dispatchers.Main) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    50000,      // 50 seconds
                    50000,      // 50 seconds
                    1000, // Start playback after 1s of buffering
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS  // 5 seconds
                )
                .build()

            val exoPlayer = ExoPlayer.Builder(application)
                .setLoadControl(loadControl)
                .build()

            val mediaItem = MediaItem.fromUri(url)
            
            // Use SRT data source for srt:// URLs, RTMP for rtmp://, otherwise default
            val mediaSource = if (isSrtUrl(url)) {
                Log.i(TAG, "Creating SRT media source for: $url")
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                    SrtDataSourceFactory(),
                    TsOnlyExtractorFactory()
                ).createMediaSource(mediaItem)
            } else if (url.lowercase().startsWith("rtmp://")) {
                Log.i(TAG, "Creating RTMP media source for: $url")
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                    RtmpDataSource.Factory(),
                    DefaultExtractorsFactory()
                ).createMediaSource(mediaItem)
            } else {
                Log.i(TAG, "Creating default media source for: $url")
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                    DefaultDataSource.Factory(application),
                    DefaultExtractorsFactory()
                ).createMediaSource(mediaItem)
            }

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.volume = 0f
            
            // Add a lightweight error listener so callers can observe failures in logs
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val protocol = if (isSrtUrl(url)) "SRT" else "RTMP"
                    Log.w(TAG, "ExoPlayer $protocol error: ${error.message}")
                }
            })
            exoPlayer
        }

    private fun normalizeUrl(url: String): String {
        return if (url.lowercase().contains("rtmp://localhost")) {
            url.replace("localhost", "127.0.0.1", ignoreCase = true)
        } else {
            url
        }
    }

    suspend fun awaitReady(player: ExoPlayer, url: String, timeoutMs: Long = 30000, postStatus: ((String) -> Unit)? = null): Boolean {
        val pathPart = try {
            val uri = android.net.Uri.parse(url)
            uri.path ?: url.substringAfterLast("/")
        } catch (_: Exception) {
            url.takeLast(10)
        }
        val displayUrl = url.substringAfter("://").take(15) + "..." + pathPart
        Log.d(TAG, "awaitReady: Waiting for player to become READY for $url (timeout=${timeoutMs}ms)")
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            val stateName = when (playbackState) {
                                androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                                androidx.media3.common.Player.STATE_BUFFERING -> "Buffering"
                                androidx.media3.common.Player.STATE_READY -> "READY"
                                androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                                else -> "UNKNOWN($playbackState)"
                            }
                            Log.d(TAG, "ExoPlayer awaitReady state: $stateName for $url")
                            
                            // Always post status for transparency during connection
                            if (playbackState != androidx.media3.common.Player.STATE_READY) {
                                postStatus?.invoke("RTMP ($stateName): $displayUrl")
                            }
                            
                            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                                try { player.removeListener(this) } catch (_: Exception) {}
                                if (cont.isActive) cont.resume(true) {}
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e(TAG, "ExoPlayer error in awaitReady for $url: ${error.message}", error)
                            val errorInfo = error.message?.take(15) ?: error.errorCodeName
                            postStatus?.invoke("RTMP Error ($errorInfo): $displayUrl")
                            try { player.removeListener(this) } catch (_: Exception) {}
                            if (cont.isActive) cont.resume(false) {}
                        }
                    }
                    
                    val currentState = try { player.playbackState } catch (_: Exception) { -1 }
                    Log.d(TAG, "awaitReady: Initial state is $currentState")
                    
                    // Post initial status if not ready
                    if (currentState != androidx.media3.common.Player.STATE_READY && currentState != -1) {
                         val stateName = when (currentState) {
                             androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                             androidx.media3.common.Player.STATE_BUFFERING -> "Buffering"
                             androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                             else -> "Init"
                         }
                         postStatus?.invoke("RTMP ($stateName): $displayUrl")
                    }

                    if (currentState == androidx.media3.common.Player.STATE_READY) {
                        if (cont.isActive) cont.resume(true) {}
                        return@suspendCancellableCoroutine
                    }
                    player.addListener(listener)
                    cont.invokeOnCancellation {
                        try { player.removeListener(listener) } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "awaitReady failed or timed out for $url: ${e.message}")
            false
        }
    }

    /**
     * Probes an RTMP source status in the background without affecting the main display.
     * @return The detected status (READY, ERROR, or IDLE on timeout)
     */
    @androidx.media3.common.util.UnstableApi
    suspend fun probeRtmpStatus(application: Application, url: String): RtmpSourceStatus {
        return withContext(Dispatchers.Main) {
            var player: ExoPlayer? = null
            try {
                player = createExoPlayer(application, url)
                player.prepare()
                player.playWhenReady = true
                // We use a shorter timeout for background probing to minimize resource usage.
                // Increase to 12s to allow for slower handshakes.
                val isReady = awaitReady(player, url, timeoutMs = 12000, postStatus = null)
                if (isReady) RtmpSourceStatus.READY else RtmpSourceStatus.ERROR
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Probe failed for $url: ${e.message}")
                RtmpSourceStatus.ERROR
            } finally {
                try { player?.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Switch to bitmap fallback for RTMP source.
     * Uses MediaProjection audio to capture RTMP player audio.
     */
    suspend fun switchToBitmapFallback(
        streamer: SingleStreamer,
        bitmap: Bitmap,
        mediaProjection: MediaProjection? = null,
        mediaProjectionHelper: MediaProjectionHelper? = null
    ) {
        try {
            // Add delay before switching sources to allow previous sources to fully release
            // This prevents resource conflicts when hot-swapping sources
            delay(300)
            
            // Set video to bitmap first
            streamer.setVideoSource(BitmapSourceFactory(bitmap))
            
            // Audio follows video: For RTMP/Bitmap, prefer MediaProjection, fallback to mic
            val projection = mediaProjection ?: mediaProjectionHelper?.getMediaProjection()
            if (projection != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    streamer.setAudioSource(MediaProjectionAudioSourceFactory(projection))
                    Log.i(TAG, "Switched to bitmap fallback with MediaProjection audio")
                } catch (e: Exception) {
                    Log.w(TAG, "MediaProjection audio failed, using microphone: ${e.message}")
                    streamer.setAudioSource(com.dimadesu.lifestreamer.audio.ConditionalAudioSourceFactory())
                    Log.i(TAG, "Switched to bitmap fallback with microphone audio")
                }
            } else {
                // No MediaProjection available - use microphone
                streamer.setAudioSource(com.dimadesu.lifestreamer.audio.ConditionalAudioSourceFactory())
                Log.i(TAG, "Switched to bitmap fallback with microphone audio (no MediaProjection)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set bitmap fallback source: ${e.message}", e)
        }
    }

    /**
     * Full flow to switch a streamer from camera to an RTMP source without
     * stopping or restarting the streamer service itself:
     * - switch to bitmap fallback immediately (so UI doesn't freeze)
     * - prepare ExoPlayer for RTMP preview
     * - wait until ready (with timeout)
     * - attach RTMP video and appropriate audio source
     * - retry every 5 seconds if connection fails
     * - call onRtmpConnected with ExoPlayer instance when successfully connected
     * Returns the Job for the retry loop so it can be cancelled if needed.
     */
    suspend fun switchToRtmpSource(
        application: Application,
        currentStreamer: SingleStreamer,
        testBitmap: Bitmap,
        mediaProjectionHelper: MediaProjectionHelper,
        streamingMediaProjection: MediaProjection?,
        postError: (String) -> Unit,
        postRtmpStatus: (String?) -> Unit,
        rtmpVideoSourceUrlFlow: kotlinx.coroutines.flow.Flow<String>,
        onStatusChanged: (RtmpSourceStatus) -> Unit = {},
        onRtmpConnected: ((ExoPlayer) -> Unit)? = null
    ) {
        var attemptCount = 0
        val maxAttempts = Int.MAX_VALUE // Keep retrying indefinitely
        
        while (attemptCount < maxAttempts) {
            // Check for cancellation at the start of each loop
            kotlinx.coroutines.yield()
            
            attemptCount++
            val isFirstAttempt = attemptCount == 1
            
            var exoPlayerInstance: ExoPlayer? = null
            try {
                onStatusChanged(RtmpSourceStatus.BUFFERING)
                
                val rawUrl = try {
                    withContext(Dispatchers.IO) {
                        rtmpVideoSourceUrlFlow.first()
                    }
                } catch (e: Exception) {
                    application.getString(com.swissi.lifestreamer.multitool.R.string.rtmp_source_default_url)
                }
                
                val videoSourceUrl = normalizeUrl(rawUrl)
                val displayUrl = videoSourceUrl.substringAfter("://").take(20) + (if (videoSourceUrl.length > 20) "..." else "")

                // Show status message
                if (isFirstAttempt) {
                    postRtmpStatus("Connecting: $displayUrl")
                    Log.i(TAG, "Attempting to connect to RTMP source: $videoSourceUrl")
                } else {
                    postRtmpStatus("Retrying: $displayUrl")
                    Log.i(TAG, "Retrying RTMP connection (attempt $attemptCount): $videoSourceUrl")
                }

                exoPlayerInstance = createExoPlayer(application, videoSourceUrl)

                // Prepare and wait for the RTMP player to be ready
                val readyTimeout = 30000L
                val ready = try {
                    exoPlayerInstance.prepare()
                    exoPlayerInstance.playWhenReady = true
                    awaitReady(exoPlayerInstance, videoSourceUrl, readyTimeout, postRtmpStatus)
                } catch (e: Exception) {
                    Log.w(TAG, "awaitReady error for $videoSourceUrl: ${e.message}")
                    false
                }

                if (!ready) throw Exception("Stream Timeout (No data)")

                // Attach RTMP video and audio to the streamer
                delay(300)
                
                currentStreamer.setVideoSource(RTMPVideoSource.Factory(exoPlayerInstance))
                
                val isStreaming = currentStreamer.isStreamingFlow.value == true
                val projection = streamingMediaProjection ?: mediaProjectionHelper.getMediaProjection()
                
                if (isStreaming && projection != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        currentStreamer.setAudioSource(MediaProjectionAudioSourceFactory(projection))
                        Log.i(TAG, "Set MediaProjection audio for RTMP")
                    } catch (ae: Exception) {
                        currentStreamer.setAudioSource(com.dimadesu.lifestreamer.audio.ConditionalAudioSourceFactory())
                    }
                } else {
                    currentStreamer.setAudioSource(com.dimadesu.lifestreamer.audio.ConditionalAudioSourceFactory())
                }
                
                postRtmpStatus(null)
                onStatusChanged(RtmpSourceStatus.READY)
                onRtmpConnected?.invoke(exoPlayerInstance)
                
                return // Success - exit function
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // If the job is cancelled, don't post status and just rethrow
                Log.d(TAG, "switchToRtmpSource cancelled for $attemptCount")
                try { exoPlayerInstance?.release() } catch (_: Exception) {}
                throw ce
            } catch (t: Throwable) {
                val errorMsg = when (t) {
                    is kotlinx.coroutines.TimeoutCancellationException -> "Connection Timeout (15s)"
                    else -> "Playback Error: ${t.message}"
                }
                Log.e(TAG, "RTMP playback failed: ${t.message}")
                postRtmpStatus(errorMsg)
                onStatusChanged(RtmpSourceStatus.ERROR)
                
                try { exoPlayerInstance?.release() } catch (_: Exception) {}
                
                if (isFirstAttempt) {
                    switchToBitmapFallback(currentStreamer, testBitmap, streamingMediaProjection, mediaProjectionHelper)
                }
                
                delay(5000)
                continue
            }
        }
    }
}
