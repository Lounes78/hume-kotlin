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
    suspend fun connect(allowUserInterrupt: Boolean = false): Boolean {
        this.allowInterrupt = allowUserInterrupt
        
        // Connect WebSocket
        val connected = webSocket.connect()
        if (!connected) {
            Log.e(TAG, "Failed to connect WebSocket")
            return false
        }
        
        isConnected = true
        
        // Set up audio configuration - like Python SDK
        val audioConfig = audioManager.getAudioConfiguration()
        val sessionSettings = SessionSettings(audio = audioConfig)
        webSocket.sendSessionSettings(sessionSettings)
        
        // Start audio capture and playback
        audioManager.startRecording()
        audioManager.startPlayback()
        
        // Set up audio streaming with proper interrupt handling
        setupAudioStreaming()
        
        // Set up message handling for interrupts
        setupMessageHandling()
        
        Log.d(TAG, "EVI client connected and ready")
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
            audioManager.audioInput.collect { audioData ->
                // Critical: Only send audio when sendAudio flag is true
                // This matches Python SDK's MicrophoneSender.send() logic exactly
                if (isConnected && sendAudio.get()) {
                    webSocket.sendAudioInput(audioData)
                }
            }
        }
        
        scope.launch {
            webSocket.audioOutput.collect { audioData ->
                if (isConnected) {
                    audioManager.queueAudioForPlayback(audioData)
                }
            }
        }
    }

    /**
     * Set up message handling with proper interrupt logic from Python SDK
     */
    private fun setupMessageHandling() {
        scope.launch {
            webSocket.messageEvents.collect { event ->
                when (event) {
                    is AudioOutput -> {
                        // Python SDK's on_audio_begin logic
                        // When assistant starts speaking, stop sending audio unless interrupts allowed
                        sendAudio.set(allowInterrupt)
                        Log.d(TAG, "Assistant speaking - sendAudio set to $allowInterrupt")
                    }
                    
                    is AssistantEnd -> {
                        // Python SDK's on_audio_end logic  
                        // When assistant stops speaking, resume sending audio
                        sendAudio.set(true)
                        Log.d(TAG, "Assistant finished - sendAudio set to true")
                    }
                    
                    is UserInterruption -> {
                        // User interrupted - ensure we can send audio again
                        sendAudio.set(true)
                        Log.d(TAG, "User interruption detected at ${event.time} - sendAudio set to true")
                    }
                    
                    is ChatMetadata -> {
                        Log.d(TAG, "Chat metadata: chatId=${event.chatId}, chatGroupId=${event.chatGroupId}")
                    }
                    
                    is UserMessage -> {
                        val content = event.message.content ?: ""
                        Log.d(TAG, "User message: $content (interim=${event.interim}, from_text=${event.fromText})")
                        
                        // Log emotion data if available
                        event.models.prosody?.let { prosody ->
                            val topEmotion = getTopEmotion(prosody.scores)
                            Log.d(TAG, "User emotions - Top: ${topEmotion.first} (${topEmotion.second})")
                        }
                    }
                    
                    is AssistantMessage -> {
                        val content = event.message.content ?: ""
                        Log.d(TAG, "Assistant message: $content (id=${event.id}, from_text=${event.fromText})")
                        
                        // Log emotion data if available
                        event.models.prosody?.let { prosody ->
                            val topEmotion = getTopEmotion(prosody.scores)
                            Log.d(TAG, "Assistant emotions - Top: ${topEmotion.first} (${topEmotion.second})")
                        }
                    }
                    
                    is AssistantProsody -> {
                        event.models.prosody?.let { prosody ->
                            val topEmotion = getTopEmotion(prosody.scores)
                            Log.d(TAG, "Assistant prosody - Top emotion: ${topEmotion.first} (${topEmotion.second})")
                        }
                    }
                    
                    is ToolCallMessage -> {
                        Log.d(TAG, "Tool call: ${event.name} with params: ${event.parameters} (type=${event.toolType})")
                        // Note: Tool calls need to be handled by the application
                    }
                    
                    is ToolResponseMessage -> {
                        Log.d(TAG, "Tool response: ${event.content}")
                    }
                    
                    is ToolErrorMessage -> {
                        Log.e(TAG, "Tool error: ${event.error} - ${event.content}")
                    }
                    
                    is WebSocketError -> {
                        Log.e(TAG, "WebSocket error: ${event.error} (code=${event.code})")
                    }
                    
                    else -> {
                        Log.d(TAG, "Received event: ${event.type}")
                    }
                }
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