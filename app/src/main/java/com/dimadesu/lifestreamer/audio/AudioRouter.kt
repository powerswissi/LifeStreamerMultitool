package com.dimadesu.lifestreamer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.interfaces.IWithAudioSource
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer

/**
 * Central router for audio source switching and sync management.
 * sw{BT:SCO; USB:VC; NET:ExtPCM}
 */
class AudioRouter(private val context: Context) {
    enum class Source { FRONT, BACK, BT, USB, WIFI, RTMP }
    
    // Internal protocol mapping
    // BT -> SCO
    // USB -> VC (Voice Communication)
    // NET (WIFI/RTMP) -> ExtPCM

    suspend fun switchTo(streamer: ISingleStreamer, src: Source, device: AudioDeviceInfo? = null) {
        val audioStreamer = streamer as? IWithAudioSource ?: return
        
        val factory = when (src) {
            Source.FRONT -> MicrophoneSourceFactory(MediaRecorder.AudioSource.MIC)
            Source.BACK -> MicrophoneSourceFactory(MediaRecorder.AudioSource.CAMCORDER)
            Source.BT -> BluetoothAudioSourceFactory(device) // Automatically uses SCO logic in factory
            Source.USB -> MicrophoneSourceFactory(MediaRecorder.AudioSource.VOICE_COMMUNICATION) // USB:VC
            Source.WIFI, Source.RTMP -> NetworkAudioSourceFactory() // NET:ExtPCM
        }

        audioStreamer.setAudioSource(factory)
    }
}
