package com.hume.empathicvoice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level Hume EVI Client - based on Python SDK with proper interrupt handling
 * Combines WebSocket communication and audio handling
 */
class HumeEviClient(
    private val context: Context,
    private val apiKey: String,
    private val configId: String? = null
) {
    companion object {
        private const val TAG = "HumeEviClient"
    }

    private val webSocket = HumeEviWebSocket(apiKey, configId)
    private val audioManager = HumeAudioManager(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var isConnected = false
    private var allowInterrupt = false
    
    // Critical: Proper interrupt handling like Python SDK's MicrophoneSender
    private val sendAudio = AtomicBoolean(true) // Python SDK's send_audio flag
    
    // Expose events from WebSocket
    val messageEvents: SharedFlow<SubscribeEvent> = webSocket.messageEvents
    val connectionState: SharedFlow<HumeEviWebSocket.ConnectionState> = webSocket.connectionState

    /**
     * Connect to EVI and start audio - like Python SDK's MicrophoneInterface.start()
     */
    suspend fun connect(allowUserInterrupt: Boolean = true): Boolean {
        this.allowInterrupt = allowUserInterrupt
        Log.d(TAG, "Connecting with allowUserInterrupt=$allowUserInterrupt")
        
        setupAudioStreaming()
        setupMessageHandling()
        audioManager.startPlayback()
        
        val connected = webSocket.connect()
        if (!connected) {
            Log.e(TAG, "Failed to connect WebSocket")
            return false
        }
        
        Log.d(TAG, "EVI client setup complete")
        return true
    }

    /**
     * Disconnect from EVI
     */
    fun disconnect() {
        isConnected = false
        audioManager.release()
        webSocket.disconnect()
        scope.cancel()
        Log.d(TAG, "EVI client disconnected")
    }

    /**
     * Send text input to EVI
     */
    suspend fun sendTextInput(text: String) {
        webSocket.sendUserInput(text)
    }

    /**
     * Send tool response
     */
    suspend fun sendToolResponse(toolCallId: String, content: String) {
        webSocket.sendToolResponse(toolCallId, content)
    }

    /**
     * Send tool error
     */
    suspend fun sendToolError(toolCallId: String, error: String, content: String? = null) {
        webSocket.sendToolError(toolCallId, error, content)
    }

    /**
     * Pause assistant responses
     */
    suspend fun pauseAssistant() {
        webSocket.pauseAssistant()
    }

    /**
     * Resume assistant responses  
     */
    suspend fun resumeAssistant() {
        webSocket.resumeAssistant()
    }

    /**
     * Set up continuous audio streaming with proper interrupt handling
     */
    private fun setupAudioStreaming() {
        scope.launch {
            try {
                audioManager.audioInput.collect { audioData ->
                    if (isConnected && sendAudio.get()) {
                        webSocket.sendAudioInput(audioData)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio input streaming", e)
            }
        }
        
        scope.launch {
            try {
                webSocket.audioOutput.collect { audioData ->
                    try {
                        audioManager.queueAudioForPlayback(audioData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to queue audio data", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio output streaming", e)
            }
        }
    }

    /**
     * Set up message handling with proper interrupt logic from Python SDK
     */
    private fun setupMessageHandling() {
        // Immediate fallback: Start microphone after WebSocket stabilizes
        scope.launch {
            delay(1000)
            
            if (!isConnected) {
                isConnected = true
                val audioConfig = audioManager.getAudioConfiguration()
                val sessionSettings = SessionSettings(audio = audioConfig)
                webSocket.sendSessionSettings(sessionSettings)
                audioManager.startRecording()
                Log.d(TAG, "EVI client connected via immediate start")
            }
        }
        
        // Handle WebSocket connection state changes
        scope.launch {
            try {
                webSocket.connectionState.collect { connectionState ->
                    when (connectionState) {
                        is HumeEviWebSocket.ConnectionState.Connected -> {
                            if (!isConnected) {
                                isConnected = true
                                val audioConfig = audioManager.getAudioConfiguration()
                                val sessionSettings = SessionSettings(audio = audioConfig)
                                webSocket.sendSessionSettings(sessionSettings)
                                audioManager.startRecording()
                                Log.d(TAG, "EVI client fully connected")
                            }
                        }
                        is HumeEviWebSocket.ConnectionState.Disconnected -> {
                            isConnected = false
                        }
                        is HumeEviWebSocket.ConnectionState.Error -> {
                            Log.e(TAG, "WebSocket error: ${connectionState.message}")
                            isConnected = false
                        }
                        is HumeEviWebSocket.ConnectionState.Connecting -> {
                            // WebSocket is connecting, wait for Connected state
                        }
                        is HumeEviWebSocket.ConnectionState.Disconnecting -> {
                            // WebSocket is disconnecting
                        }
                        else -> {
                            // Handle any other connection states
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in connection state collector", e)
            }
        }
        
        // Handle WebSocket messages
        scope.launch {
            try {
                webSocket.messageEvents.collect { event ->
                    // Fallback: If we receive any message, WebSocket is connected
                    if (!isConnected) {
                        isConnected = true
                        val audioConfig = audioManager.getAudioConfiguration()
                        val sessionSettings = SessionSettings(audio = audioConfig)
                        webSocket.sendSessionSettings(sessionSettings)
                        audioManager.startRecording()
                        Log.d(TAG, "EVI client connected via fallback")
                    }
                    
                    when (event) {
                        is AudioOutput -> {
                            sendAudio.set(allowInterrupt)
                        }
                        
                        is AssistantEnd -> {
                            sendAudio.set(true)
                        }
                        
                        is UserInterruption -> {
                            sendAudio.set(true)
                        }
                        
                        is UserMessage -> {
                            val content = event.message.content ?: ""
                            Log.d(TAG, "User: $content")
                        }
                        
                        is AssistantMessage -> {
                            val content = event.message.content ?: ""
                            Log.d(TAG, "Assistant: $content")
                        }
                        
                        is ToolCallMessage -> {
                            Log.d(TAG, "Tool call: ${event.name}")
                        }
                        
                        is WebSocketError -> {
                            Log.e(TAG, "WebSocket error: ${event.error}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in message events collector", e)
            }
        }
    }

    /**
     * Get the top emotion from emotion scores
     */
    private fun getTopEmotion(scores: EmotionScores): Pair<String, Double> {
        val emotionMap = mapOf(
            "Admiration" to scores.admiration,
            "Adoration" to scores.adoration,
            "Aesthetic Appreciation" to scores.aestheticAppreciation,
            "Amusement" to scores.amusement,
            "Anger" to scores.anger,
            "Anxiety" to scores.anxiety,
            "Awe" to scores.awe,
            "Awkwardness" to scores.awkwardness,
            "Boredom" to scores.boredom,
            "Calmness" to scores.calmness,
            "Concentration" to scores.concentration,
            "Confusion" to scores.confusion,
            "Contemplation" to scores.contemplation,
            "Contempt" to scores.contempt,
            "Contentment" to scores.contentment,
            "Craving" to scores.craving,
            "Desire" to scores.desire,
            "Determination" to scores.determination,
            "Disappointment" to scores.disappointment,
            "Disgust" to scores.disgust,
            "Distress" to scores.distress,
            "Doubt" to scores.doubt,
            "Ecstasy" to scores.ecstasy,
            "Embarrassment" to scores.embarrassment,
            "Empathic Pain" to scores.empathicPain,
            "Entrancement" to scores.entrancement,
            "Envy" to scores.envy,
            "Excitement" to scores.excitement,
            "Fear" to scores.fear,
            "Guilt" to scores.guilt,
            "Horror" to scores.horror,
            "Interest" to scores.interest,
            "Joy" to scores.joy,
            "Love" to scores.love,
            "Nostalgia" to scores.nostalgia,
            "Pain" to scores.pain,
            "Pride" to scores.pride,
            "Realization" to scores.realization,
            "Relief" to scores.relief,
            "Romance" to scores.romance,
            "Sadness" to scores.sadness,
            "Satisfaction" to scores.satisfaction,
            "Shame" to scores.shame,
            "Surprise (negative)" to scores.surpriseNegative,
            "Surprise (positive)" to scores.surprisePositive,
            "Sympathy" to scores.sympathy,
            "Tiredness" to scores.tiredness,
            "Triumph" to scores.triumph
        )
        
        return emotionMap.maxByOrNull { it.value }?.let { it.key to it.value } ?: ("Unknown" to 0.0)
    }
}