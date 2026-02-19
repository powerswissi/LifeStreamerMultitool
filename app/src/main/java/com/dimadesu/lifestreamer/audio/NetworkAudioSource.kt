package com.dimadesu.lifestreamer.audio

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioFrameSourceInternal
import io.github.thibaultbee.streampack.core.elements.interfaces.SuspendConfigurable
import io.github.thibaultbee.streampack.core.elements.interfaces.SuspendStreamable
import io.github.thibaultbee.streampack.core.elements.interfaces.Releasable
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Network-backed audio source for ExtPCM protocol.
 * Receives external PCM data and provides it to the audio pipeline.
 */
class NetworkAudioSource : IAudioSourceInternal, IAudioFrameSourceInternal, SuspendStreamable, SuspendConfigurable<AudioSourceConfig>, Releasable {
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private var currentConfig: AudioSourceConfig? = null
    private val frameQueue = ConcurrentLinkedQueue<ByteArray>()
    private val mutex = Mutex()

    /**
     * Push external PCM data into the source.
     */
    fun pushPcmData(data: ByteArray) {
        if (_isStreamingFlow.value) {
            frameQueue.offer(data)
        }
    }

    override suspend fun configure(config: AudioSourceConfig) {
        currentConfig = config
    }

    override suspend fun startStream() {
        _isStreamingFlow.emit(true)
    }

    override suspend fun stopStream() {
        _isStreamingFlow.emit(false)
        frameQueue.clear()
    }

    override fun release() {
        frameQueue.clear()
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val data = frameQueue.poll()
        if (data != null) {
            val buffer = frame.rawBuffer
            val fillSize = minOf(data.size, buffer.remaining())
            buffer.put(data, 0, fillSize)
            frame.timestampInUs = System.nanoTime() / 1000L
            return frame
        } else {
            // Stall detected - RawFramePullPush will catch null and inject SilentFrame
            frame.close()
            return null as RawFrame // Explicit null to signal stall
        }
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val cfg = currentConfig ?: return null as RawFrame
        // Assume standard frame size if queue empty, or match incoming data size
        val size = frameQueue.peek()?.size ?: 1024 
        return fillAudioFrame(frameFactory.create(size, 0))
    }
}

class NetworkAudioSourceFactory : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal = NetworkAudioSource()
    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean = source is NetworkAudioSource
}
