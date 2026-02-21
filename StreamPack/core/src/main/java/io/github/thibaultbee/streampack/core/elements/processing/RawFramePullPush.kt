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
package io.github.thibaultbee.streampack.core.elements.processing

import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.utils.pool.IRawFrameFactory
import io.github.thibaultbee.streampack.core.elements.utils.pool.RawFrameFactory
import io.github.thibaultbee.streampack.core.elements.processing.audio.IAudioFrameProcessor
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

fun RawFramePullPush(
    frameProcessor: IFrameProcessor<RawFrame>,
    onFrame: suspend (RawFrame) -> Unit,
    processDispatcher: CoroutineDispatcher,
    isDirect: Boolean = true
) = RawFramePullPush(frameProcessor, onFrame, RawFrameFactory(isDirect), processDispatcher)

/**
 * A component that pull a frame from an input and push it to [onFrame] output.
 *
 * @param frameProcessor the frame processor
 * @param onFrame the output frame callback
 * @param frameFactory the frame factory to create frames
 * @param processDispatcher the dispatcher to process frames on
 */
class RawFramePullPush(
    private val frameProcessor: IFrameProcessor<RawFrame>,
    val onFrame: suspend (RawFrame) -> Unit,
    private val frameFactory: IRawFrameFactory,
    private val processDispatcher: CoroutineDispatcher,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + processDispatcher)
    private val mutex = Mutex()

    private var getFrame: (suspend (frameFactory: IRawFrameFactory) -> RawFrame)? = null

    private val isReleaseRequested = AtomicBoolean(false)

    private var job: Job? = null
    private var lastPts = -1L
    private var lastBufferSize = 0

    var continuous = false
    var frameDurationUs = 0L

    suspend fun setInput(getFrame: suspend (frameFactory: IRawFrameFactory) -> RawFrame) {
        mutex.withLock {
            this.getFrame = getFrame
        }
    }

    suspend fun removeInput() {
        mutex.withLock {
            this.getFrame = null
        }
    }

    fun startStream() {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "Already released")
            return
        }
        job = coroutineScope.launch {
            while (isActive) {
                val rawFrame = mutex.withLock {
                    val listener = getFrame ?: return@withLock null
                    try {
                        listener(frameFactory)
                    } catch (t: Throwable) {
                        Logger.e(TAG, "Failed to get frame: ${t.message}")
                        null
                    }
                }

                val frameToProcess = if (rawFrame == null) {
                    val isMuted = (frameProcessor as? IAudioFrameProcessor)?.isMuted == true
                    if (continuous && isMuted) {
                        // CRASH PREVENTION: Default values for silent frame generation if source stalls early
                        val duration = if (frameDurationUs > 0) frameDurationUs else 20000L // Default 20ms
                        val bufferSize = if (lastBufferSize > 0) lastBufferSize else 1024
                        val nextPts = if (lastPts == -1L) 0L else (lastPts + duration)
                        
                        // Generate silent frame
                        val generatedFrame = frameFactory.create(bufferSize, nextPts)
                        lastPts = nextPts // Update lastPts so next generated frame is also correct
                        generatedFrame
                    } else {
                        null
                    }
                } else {
                    if (lastPts != -1L && rawFrame.timestampInUs > lastPts) {
                        // Update frame duration estimate
                        frameDurationUs = rawFrame.timestampInUs - lastPts
                    }
                    lastPts = rawFrame.timestampInUs
                    lastBufferSize = rawFrame.rawBuffer.remaining()
                    rawFrame
                }

                if (frameToProcess == null) {
                    continue
                }

                // Process buffer with effects
                val processedFrame = try {
                    frameProcessor.processFrame(frameToProcess)
                } catch (t: Throwable) {
                    Logger.e(TAG, "Failed to pre-process frame: ${t.message}")
                    frameToProcess.close()
                    continue
                }

                // Store for outputs
                onFrame(processedFrame)
            }
            Logger.e(TAG, "Processing loop ended")
        }
    }

    fun stopStream() {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "Already released")
            return
        }
        job?.cancel()
        job = null

        frameFactory.clear()
    }

    fun release() {
        stopStream()

        if (isReleaseRequested.getAndSet(true)) {
            Logger.w(TAG, "Already released")
            return
        }

        coroutineScope.cancel()
        frameFactory.close()
    }

    companion object {
        private const val TAG = "FramePullPush"
    }
}