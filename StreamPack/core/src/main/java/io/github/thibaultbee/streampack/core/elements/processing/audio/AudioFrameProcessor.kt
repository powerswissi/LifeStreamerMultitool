/*
 * Copyright (C) 2025 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.processing.audio

import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.processing.IFrameProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Audio frame processor.
 *
 * Supports mute effect and audio level monitoring.
 */
class AudioFrameProcessor : IFrameProcessor<RawFrame>, IAudioFrameProcessor {
    override var isMuted: Boolean = false
    override var channelCount: Int = 1
    override var audioLevelCallback: AudioLevelCallback? = null

    private val zeroChunk = ByteArray(4096)

    override fun processFrame(frame: RawFrame): RawFrame {
        if (isMuted) {
            zeroFill(frame.rawBuffer)
            audioLevelCallback?.invoke(AudioLevelData(channelCount, 0f, 0f, 0f, 0f))
            return frame
        }

        audioLevelCallback?.let { callback ->
            val levels = calculateAudioLevels(frame.rawBuffer, channelCount)
            callback(levels)
        }
        return frame
    }

    private fun zeroFill(buffer: ByteBuffer) {
        if (buffer.isReadOnly) return

        val pos = buffer.position()
        val limit = buffer.limit()
        val len = limit - pos
        if (len <= 0) return

        if (buffer.hasArray()) {
            val start = buffer.arrayOffset() + pos
            val end = start + len
            java.util.Arrays.fill(buffer.array(), start, end, 0.toByte())
            return
        }

        // Direct buffer: chunked absolute writes (does not change position/limit)
        var off = 0
        while (off < len) {
            val n = min(len - off, zeroChunk.size)
            for (i in 0 until n) {
                buffer.put(pos + off + i, 0.toByte())
            }
            off += n
        }
    }

    /**
     * Calculate RMS and peak audio levels from 16-bit PCM audio buffer.
     * Supports mono (1 channel) and stereo (2 channels, interleaved L-R-L-R).
     */
    private fun calculateAudioLevels(buffer: ByteBuffer, channels: Int): AudioLevelData {
        val position = buffer.position()
        val limit = buffer.limit()
        val remaining = limit - position

        if (remaining < 2) {
            return AudioLevelData(channels, 0f, 0f, 0f, 0f)
        }

        val readBuffer = buffer.duplicate()
        readBuffer.position(position)
        readBuffer.order(ByteOrder.LITTLE_ENDIAN)

        var maxSampleLeft = 0
        var maxSampleRight = 0
        var sumSquaresLeft = 0.0
        var sumSquaresRight = 0.0
        var sampleCountLeft = 0
        var sampleCountRight = 0

        val isStereo = channels >= 2
        var isLeftChannel = true

        while (readBuffer.remaining() >= 2) {
            val sample = readBuffer.short.toInt()
            val absSample = abs(sample)
            val sampleSquared = (sample.toLong() * sample.toLong()).toDouble()

            if (!isStereo || isLeftChannel) {
                if (absSample > maxSampleLeft) maxSampleLeft = absSample
                sumSquaresLeft += sampleSquared
                sampleCountLeft++
            } else {
                if (absSample > maxSampleRight) maxSampleRight = absSample
                sumSquaresRight += sampleSquared
                sampleCountRight++
            }

            if (isStereo) isLeftChannel = !isLeftChannel
        }

        val peakLeft =
            if (sampleCountLeft > 0) (maxSampleLeft / 32767f).coerceIn(0f, 1f) else 0f
        val rmsLeft =
            if (sampleCountLeft > 0) (sqrt(sumSquaresLeft / sampleCountLeft) / 32767.0).toFloat()
                .coerceIn(0f, 1f) else 0f

        val peakRight =
            if (sampleCountRight > 0) (maxSampleRight / 32767f).coerceIn(0f, 1f) else 0f
        val rmsRight =
            if (sampleCountRight > 0) (sqrt(sumSquaresRight / sampleCountRight) / 32767.0).toFloat()
                .coerceIn(0f, 1f) else 0f

        return AudioLevelData(channels, rmsLeft, peakLeft, rmsRight, peakRight)
    }
}
