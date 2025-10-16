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
        // Audio format - input vs output may differ
        private const val INPUT_SAMPLE_RATE = 16000  // 16kHz input for speech recognition
        private const val OUTPUT_SAMPLE_RATE = 48000 // 48kHz output from Hume AI
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var isRecording = false
    private var isPlaying = false
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Audio streams - with replay buffer for timing issues
    private val _audioInput = MutableSharedFlow<ByteArray>(
        replay = 5,  // Buffer recent audio chunks for late collectors
        extraBufferCapacity = 20  // Extra buffer for continuous audio
    )
    val audioInput: SharedFlow<ByteArray> = _audioInput.asSharedFlow()
    
    // Audio playback queue - match Python SDK's 32-item queue size
    private val audioPlaybackQueue = LinkedBlockingQueue<ByteArray>(32)
    private var isFirstAudioChunk = true
    
    private val bufferSizeIn = AudioRecord.getMinBufferSize(INPUT_SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
    private val bufferSizeOut = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR

    /**
     * Start audio capture - continuously streams audio like Python SDK
     */
    fun startRecording() {
        if (isRecording) {
            Log.d(TAG, "Recording already active")
            return
        }
        
        Log.d(TAG, "Starting audio recording...")
        
        try {
            // Calculate buffer size like Sesame AI - with error checking
            val calculatedBufferSize = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR
            
            if (calculatedBufferSize == AudioRecord.ERROR || calculatedBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size: $calculatedBufferSize")
                return
            }
            
            val bufferSize = calculatedBufferSize
            
            // Use VOICE_COMMUNICATION source with hardware echo cancellation
            val audioSource = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            
            audioRecord = AudioRecord(
                audioSource,
                INPUT_SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized - microphone access failed")
                audioRecord?.release()
                audioRecord = null
                return
            }
            
            audioRecord?.startRecording()
            
            val recordingState = audioRecord?.recordingState
            if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord failed to start recording - check microphone permissions")
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                return
            }
            
            isRecording = true
            startRecordingThread(bufferSize)
            Log.d(TAG, "Audio recording started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording = false
        }
    }
    
    /**
     * Start recording thread - based on Sesame AI approach for reliable audio capture
     */
    private fun startRecordingThread(bufferSize: Int) {
        Thread {
            val buffer = ByteArray(bufferSize / 4)
            var chunksProcessed = 0
            
            while (isRecording && !Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    
                    if (bytesRead > 0) {
                        val audioData = buffer.copyOf(bytesRead)
                        _audioInput.tryEmit(audioData)
                        chunksProcessed++
                        
                        if (chunksProcessed == 1) {
                            Log.d(TAG, "Microphone capture started successfully")
                        }
                        
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                    
                } catch (e: Exception) {
                    if (isRecording) {
                        Log.e(TAG, "Error reading audio data", e)
                    }
                    break
                }
            }
            
            Log.d(TAG, "Audio capture thread ended")
        }.apply {
            name = "HumeAudioCapture"
        }.start()
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
        
        // Reset for new playback session
        isFirstAudioChunk = true
        
        try {
            // Configure audio manager for loudspeaker output with echo cancellation
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION  // Better for full-duplex with echo cancellation
            audioManager?.isSpeakerphoneOn = true  // Enable loudspeaker explicitly
            Log.d(TAG, "Audio configured for loudspeaker output")
            
            // Request audio focus with better handling for loudspeaker
            val result = audioManager?.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                audioTrack?.play()
                            }
                        }
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            
            // Configure AudioTrack for loudspeaker output with echo cancellation
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,  // Use voice call stream for echo cancellation
                OUTPUT_SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT,
                bufferSizeOut,
                AudioTrack.MODE_STREAM
            )
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack not initialized")
                return
            }
            
            // Set volume and start immediately - original SDK approach
            audioTrack?.setVolume(1.0f)
            audioTrack?.play()
            isPlaying = true
            
            Log.d(TAG, "AudioTrack created successfully")
            
            // Start playback loop - with better error handling and state checking
            scope.launch {
                while (isPlaying) {
                    try {
                        val audioData = audioPlaybackQueue.take()
                        
                        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                            continue
                        }
                        
                        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            audioTrack?.play()
                        }
                        
                        audioTrack?.write(audioData, 0, audioData.size)
                        
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error playing audio", e)
                        if (audioTrack?.state == AudioTrack.STATE_UNINITIALIZED) {
                            stopPlayback()
                            delay(100)
                            startPlayback()
                            break
                        }
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
        
        // Reset for next session
        isFirstAudioChunk = true
        
        // Release audio focus
        audioManager?.abandonAudioFocus { }
        audioManager = null
        
        Log.d(TAG, "Audio playback stopped")
    }

    /**
     * Queue audio for playback - called when receiving audio from EVI
     */
    fun queueAudioForPlayback(audioData: ByteArray) {
        if (isPlaying && audioTrack != null) {
            val processedAudio = processIncomingAudio(audioData)
            
            if (processedAudio.isNotEmpty()) {
                val queued = audioPlaybackQueue.offer(processedAudio)
                if (!queued) {
                    Log.w(TAG, "Audio queue full - dropping audio data")
                }
            }
        }
    }

    /**
     * Process incoming audio data - Python SDK approach with proper header handling
     */
    private fun processIncomingAudio(audioData: ByteArray): ByteArray {
        // Python SDK: "Each chunk of audio data sent from evi is a .wav file.
        // We want to concatenate these as one long .wav stream rather than playing
        // each individual .wav file. Every .wav file starts with a 44 byte header."
        
        return if (audioData.size > 44 &&
                   audioData.sliceArray(0..3).contentEquals("RIFF".toByteArray()) &&
                   audioData.sliceArray(8..11).contentEquals("WAVE".toByteArray())) {
            
            // Python SDK approach: Parse header from first chunk only
            if (isFirstAudioChunk) {
                try {
                    val sampleRate = ByteArray(4).apply {
                        audioData.copyInto(this, 0, 24, 28)
                    }.let { bytes ->
                        (bytes[0].toInt() and 0xFF) or
                        ((bytes[1].toInt() and 0xFF) shl 8) or
                        ((bytes[2].toInt() and 0xFF) shl 16) or
                        ((bytes[3].toInt() and 0xFF) shl 24)
                    }
                    
                    val channels = ByteArray(2).apply {
                        audioData.copyInto(this, 0, 22, 24)
                    }.let { bytes ->
                        (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
                    }
                    
                    isFirstAudioChunk = false
                    return audioData
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WAV header", e)
                }
            } else {
                val pcmData = audioData.sliceArray(44 until audioData.size)
                return pcmData
            }
            
            audioData
        } else {
            audioData
        }
    }
    
    /**
     * Simple audio resampling for sample rate conversion
     */
    private fun resampleAudio(inputData: ByteArray, inputSampleRate: Int, outputSampleRate: Int): ByteArray {
        if (inputSampleRate == outputSampleRate) return inputData
        
        try {
            // Convert bytes to 16-bit samples
            val inputSamples = ShortArray(inputData.size / 2)
            for (i in inputSamples.indices) {
                val byteIndex = i * 2
                inputSamples[i] = ((inputData[byteIndex + 1].toInt() and 0xFF) shl 8 or
                                  (inputData[byteIndex].toInt() and 0xFF)).toShort()
            }
            
            // Simple linear interpolation resampling
            val ratio = inputSampleRate.toDouble() / outputSampleRate.toDouble()
            val outputLength = (inputSamples.size / ratio).toInt()
            val outputSamples = ShortArray(outputLength)
            
            for (i in outputSamples.indices) {
                val sourceIndex = (i * ratio).toInt()
                if (sourceIndex < inputSamples.size) {
                    outputSamples[i] = inputSamples[sourceIndex]
                }
            }
            
            // Convert back to bytes
            val outputData = ByteArray(outputSamples.size * 2)
            for (i in outputSamples.indices) {
                val byteIndex = i * 2
                outputData[byteIndex] = (outputSamples[i].toInt() and 0xFF).toByte()
                outputData[byteIndex + 1] = ((outputSamples[i].toInt() shr 8) and 0xFF).toByte()
            }
            
            return outputData
            
        } catch (e: Exception) {
            Log.e(TAG, "Error resampling audio", e)
            return inputData // Return original on error
        }
    }

    /**
     * Get audio configuration for session settings
     */
    fun getAudioConfiguration(): AudioConfiguration {
        return AudioConfiguration(
            sampleRate = INPUT_SAMPLE_RATE,  // Send 16kHz for input to Hume
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