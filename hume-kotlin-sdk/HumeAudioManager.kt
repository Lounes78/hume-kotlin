package com.hume.empathicvoice

import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.LinkedBlockingQueue

/**
 * Simple audio manager for Hume EVI - based on Python SDK pattern
 * Continuously captures and streams audio without VAD
 */
class HumeAudioManager(private val context: Context) {
    companion object {
        private const val TAG = "HumeAudioManager"
        // Audio format matching Python SDK defaults
        private const val SAMPLE_RATE = 16000 // 16kHz input for speech recognition
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Audio streams
    private val _audioInput = MutableSharedFlow<ByteArray>()
    val audioInput: SharedFlow<ByteArray> = _audioInput.asSharedFlow()
    
    // Audio playback queue - simple approach like Python SDK
    private val audioPlaybackQueue = LinkedBlockingQueue<ByteArray>()
    
    private val bufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
    private val bufferSizeOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR

    /**
     * Start audio capture - continuously streams audio like Python SDK
     */
    fun startRecording() {
        if (isRecording) return
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSizeIn
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return
            }
            
            audioRecord?.startRecording()
            isRecording = true
            
            // Start continuous capture loop - like Python SDK
            scope.launch {
                val buffer = ByteArray(bufferSizeIn / 4) // Smaller chunks for real-time streaming
                
                while (isRecording) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (bytesRead > 0) {
                            // Send raw audio data - let EVI handle everything else
                            val audioData = buffer.copyOf(bytesRead)
                            _audioInput.tryEmit(audioData)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading audio", e)
                        break
                    }
                }
            }
            
            Log.d(TAG, "Audio recording started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording = false
        }
    }

    /**
     * Stop audio capture
     */
    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Audio recording stopped")
    }

    /**
     * Start audio playback
     */
    fun startPlayback() {
        if (isPlaying) return
        
        try {
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT,
                bufferSizeOut,
                AudioTrack.MODE_STREAM
            )
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack not initialized")
                return
            }
            
            audioTrack?.play()
            isPlaying = true
            
            // Start playback loop - simple like Python SDK
            scope.launch {
                while (isPlaying) {
                    try {
                        val audioData = audioPlaybackQueue.take() // Blocking call
                        audioTrack?.write(audioData, 0, audioData.size)
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error playing audio", e)
                    }
                }
            }
            
            Log.d(TAG, "Audio playback started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            isPlaying = false
        }
    }

    /**
     * Stop audio playback
     */
    fun stopPlayback() {
        isPlaying = false
        audioPlaybackQueue.clear()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "Audio playback stopped")
    }

    /**
     * Queue audio for playback - called when receiving audio from EVI
     */
    fun queueAudioForPlayback(audioData: ByteArray) {
        if (isPlaying) {
            // Process WAV headers like Python SDK
            val processedAudio = processIncomingAudio(audioData)
            audioPlaybackQueue.offer(processedAudio)
        }
    }

    /**
     * Process incoming audio data - handle WAV headers like Python SDK
     */
    private fun processIncomingAudio(audioData: ByteArray): ByteArray {
        // Python SDK comment: "Each chunk of audio data sent from evi is a .wav file. 
        // We want to concatenate these as one long .wav stream rather than playing 
        // each individual .wav file. Every .wav file starts with a 44 byte header."
        
        return if (audioData.size > 44 && 
                   audioData.sliceArray(0..3).contentEquals("RIFF".toByteArray()) &&
                   audioData.sliceArray(8..11).contentEquals("WAVE".toByteArray())) {
            // Skip the 44-byte WAV header to get raw PCM data
            audioData.sliceArray(44 until audioData.size)
        } else {
            // Already raw PCM or unknown format
            audioData
        }
    }

    /**
     * Get audio configuration for session settings
     */
    fun getAudioConfiguration(): AudioConfiguration {
        return AudioConfiguration(
            sampleRate = SAMPLE_RATE,
            channels = 1, // Mono
            encoding = "linear16" // 16-bit PCM
        )
    }

    /**
     * Clean up resources
     */
    fun release() {
        stopRecording()
        stopPlayback()
        scope.cancel()
    }
}