package com.hume.empathicvoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Example Activity showing how to use Hume EVI in Android
 * Updated with proper package naming and complete emotion data access
 */
class HumeEviActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "HumeEviActivity"
        private const val RECORD_AUDIO_REQUEST_CODE = 101
        private const val YOUR_API_KEY = "your-hume-api-key-here"
        private const val YOUR_CONFIG_ID = "your-config-id-here" // Optional
    }

    private lateinit var humeClient: HumeEviClient
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusText: TextView
    private lateinit var messagesText: TextView

    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hume_evi)

        initViews()
        initHumeClient()
        checkPermissions()
    }

    private fun initViews() {
        connectButton = findViewById(R.id.btn_connect)
        disconnectButton = findViewById(R.id.btn_disconnect)
        statusText = findViewById(R.id.tv_status)
        messagesText = findViewById(R.id.tv_messages)

        connectButton.setOnClickListener { connectToEvi() }
        disconnectButton.setOnClickListener { disconnectFromEvi() }

        // Initially disabled until we have permissions
        connectButton.isEnabled = false
        disconnectButton.isEnabled = false
        statusText.text = "Ready to connect"
    }

    private fun initHumeClient() {
        // Initialize the Hume EVI client with proper interrupt handling
        humeClient = HumeEviClient(
            context = this,
            apiKey = YOUR_API_KEY,
            configId = YOUR_CONFIG_ID // Optional - use null for default
        )

        // Listen to connection state changes
        lifecycleScope.launch {
            humeClient.connectionState.collect { state ->
                runOnUiThread {
                    handleConnectionStateChange(state)
                }
            }
        }

        // Listen to EVI messages with complete type handling
        lifecycleScope.launch {
            humeClient.messageEvents.collect { event ->
                runOnUiThread {
                    handleEviMessage(event)
                }
            }
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_REQUEST_CODE
            )
        } else {
            connectButton.isEnabled = true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                connectButton.isEnabled = true
                Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connectToEvi() {
        lifecycleScope.launch {
            try {
                statusText.text = "Connecting..."
                
                // Connect to EVI with interrupt handling enabled
                val success = humeClient.connect(allowUserInterrupt = true)
                
                if (success) {
                    isConnected = true
                    connectButton.isEnabled = false
                    disconnectButton.isEnabled = true
                    statusText.text = "Connected - Say something!"
                    appendMessage("🟢 Connected to Hume EVI")
                } else {
                    statusText.text = "Connection failed"
                    appendMessage("❌ Failed to connect")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                statusText.text = "Connection error: ${e.message}"
                appendMessage("❌ Error: ${e.message}")
            }
        }
    }

    private fun disconnectFromEvi() {
        try {
            humeClient.disconnect()
            isConnected = false
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            statusText.text = "Disconnected"
            appendMessage("🔴 Disconnected from Hume EVI")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }

    private fun handleConnectionStateChange(state: HumeEviWebSocket.ConnectionState) {
        when (state) {
            is HumeEviWebSocket.ConnectionState.Connecting -> {
                statusText.text = "Connecting..."
            }
            is HumeEviWebSocket.ConnectionState.Connected -> {
                statusText.text = "Connected"
            }
            is HumeEviWebSocket.ConnectionState.Disconnected -> {
                statusText.text = "Disconnected"
                isConnected = false
                connectButton.isEnabled = true
                disconnectButton.isEnabled = false
            }
            is HumeEviWebSocket.ConnectionState.Error -> {
                statusText.text = "Error: ${state.message}"
                appendMessage("❌ ${state.message}")
            }
            else -> { /* Handle other states */ }
        }
    }

    private fun handleEviMessage(event: SubscribeEvent) {
        // Handle different message types with complete data access
        when (event) {
            is ChatMetadata -> {
                appendMessage("💬 Chat ID: ${event.chatId}")
                appendMessage("🔗 Chat Group: ${event.chatGroupId}")
                Log.d(TAG, "Chat metadata: ${event.chatGroupId}")
            }
            
            is UserMessage -> {
                val content = event.message.content ?: ""
                val status = if (event.interim) " (interim)" else ""
                val fromText = if (event.fromText) " [text]" else " [voice]"
                appendMessage("👤 You: $content$status$fromText")
                
                // Access complete emotion data from the 48-emotion model
                event.models.prosody?.let { prosody ->
                    val emotions = prosody.scores
                    val topEmotions = getTopEmotions(emotions, 3)
                    appendMessage("😊 Top emotions: ${topEmotions.joinToString(", ") { "${it.first} (${String.format("%.2f", it.second)})" }}")
                    
                    // Log specific emotions of interest
                    Log.d(TAG, "User emotions - Joy: ${emotions.joy}, Excitement: ${emotions.excitement}, Anxiety: ${emotions.anxiety}")
                }
                
                // Access timing information
                appendMessage("⏱️ Time: ${event.time.begin}ms - ${event.time.end}ms")
                
                Log.d(TAG, "User message: $content (from_text=${event.fromText}, interim=${event.interim})")
            }
            
            is AssistantMessage -> {
                val content = event.message.content ?: ""
                val fromText = if (event.fromText) " [text]" else " [voice]"
                appendMessage("🤖 EVI: $content$fromText")
                
                // Access emotion data from assistant's response
                event.models.prosody?.let { prosody ->
                    val emotions = prosody.scores
                    val topEmotions = getTopEmotions(emotions, 2)
                    appendMessage("🎭 EVI emotions: ${topEmotions.joinToString(", ") { "${it.first} (${String.format("%.2f", it.second)})" }}")
                }
                
                event.id?.let { id ->
                    appendMessage("🆔 Message ID: $id")
                }
                
                Log.d(TAG, "Assistant message: $content (id=${event.id}, from_text=${event.fromText})")
            }
            
            is AssistantEnd -> {
                appendMessage("✅ Assistant finished speaking")
                Log.d(TAG, "Assistant end")
            }
            
            is AssistantProsody -> {
                event.models.prosody?.let { prosody ->
                    val emotions = prosody.scores
                    val topEmotion = getTopEmotions(emotions, 1).firstOrNull()
                    topEmotion?.let {
                        appendMessage("🎤 Prosody: ${it.first} (${String.format("%.2f", it.second)})")
                    }
                }
                Log.d(TAG, "Assistant prosody data: ${event.id}")
            }
            
            is AudioOutput -> {
                appendMessage("🔊 Audio chunk ${event.index} (id=${event.id})")
                Log.d(TAG, "Audio output chunk: ${event.index}")
            }
            
            is ToolCallMessage -> {
                appendMessage("🔧 Tool call: ${event.name} (${event.toolType})")
                Log.d(TAG, "Tool call: ${event.name} with params: ${event.parameters}")
                
                // Handle tool calls with complete data
                handleToolCall(event)
            }
            
            is ToolResponseMessage -> {
                appendMessage("✅ Tool response sent")
                Log.d(TAG, "Tool response: ${event.content}")
            }
            
            is ToolErrorMessage -> {
                appendMessage("❌ Tool error: ${event.error}")
                Log.e(TAG, "Tool error: ${event.error} - ${event.content}")
            }
            
            is UserInterruption -> {
                appendMessage("⚡ Interruption at ${event.time}ms")
                Log.d(TAG, "User interruption at timestamp: ${event.time}")
            }
            
            is WebSocketError -> {
                appendMessage("❌ Error: ${event.error}")
                Log.e(TAG, "WebSocket error: ${event.error} (code=${event.code})")
            }
            
            else -> {
                Log.d(TAG, "Received event: ${event.type}")
            }
        }
    }

    private fun handleToolCall(toolCall: ToolCallMessage) {
        // Example tool handling with complete message data
        lifecycleScope.launch {
            try {
                when (toolCall.name) {
                    "get_current_weather" -> {
                        // Parse parameters properly
                        val params = toolCall.parameters
                        Log.d(TAG, "Weather tool called with params: $params")
                        
                        // Mock weather response
                        val response = "The weather is sunny and 72°F"
                        humeClient.sendToolResponse(toolCall.toolCallId, response)
                        appendMessage("🌤️ Weather tool response sent")
                    }
                    
                    "get_time" -> {
                        val currentTime = System.currentTimeMillis()
                        val response = "Current time: ${java.util.Date(currentTime)}"
                        humeClient.sendToolResponse(toolCall.toolCallId, response)
                        appendMessage("⏰ Time tool response sent")
                    }
                    
                    else -> {
                        // Unknown tool
                        humeClient.sendToolError(
                            toolCall.toolCallId,
                            "Unknown tool: ${toolCall.name}",
                            "Tool not implemented"
                        )
                        appendMessage("❓ Unknown tool: ${toolCall.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution error", e)
                humeClient.sendToolError(
                    toolCall.toolCallId,
                    "Tool execution failed: ${e.message}",
                    "Internal error"
                )
            }
        }
    }

    /**
     * Get top N emotions from complete emotion scores
     */
    private fun getTopEmotions(scores: EmotionScores, count: Int): List<Pair<String, Double>> {
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
        
        return emotionMap.toList()
            .sortedByDescending { it.second }
            .take(count)
    }

    private fun appendMessage(message: String) {
        val currentText = messagesText.text.toString()
        val newText = if (currentText.isEmpty()) {
            message
        } else {
            "$currentText\n$message"
        }
        messagesText.text = newText
        
        // Auto-scroll to bottom (you might want to add a ScrollView)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isConnected) {
            humeClient.disconnect()
        }
    }
}