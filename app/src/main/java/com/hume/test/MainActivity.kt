package com.hume.test

import android.Manifest
import android.content.Context
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
import com.hume.empathicvoice.*
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RECORD_AUDIO_REQUEST_CODE = 101
    }

    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusText: TextView
    private lateinit var messagesText: TextView

    private var humeClient: HumeEviClient? = null
    private var isConnected = false

    // API configuration
    private var apiKey: String = ""
    private var configId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadConfig()
        checkPermissions()
    }

    private fun initViews() {
        connectButton = findViewById(R.id.btn_connect)
        disconnectButton = findViewById(R.id.btn_disconnect)
        statusText = findViewById(R.id.tv_status)
        messagesText = findViewById(R.id.tv_messages)

        connectButton.setOnClickListener { connectToHume() }
        disconnectButton.setOnClickListener { disconnectFromHume() }

        // Initially disabled until we have permissions and config
        connectButton.isEnabled = false
        disconnectButton.isEnabled = false
    }

    private fun loadConfig() {
        try {
            // Try to read API key from assets/config.properties
            val inputStream = assets.open("config.properties")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val properties = java.util.Properties()
            properties.load(reader)
            
            apiKey = properties.getProperty("HUME_API_KEY", "")
            configId = properties.getProperty("HUME_CONFIG_ID")
            
            reader.close()
            
            if (apiKey.isNotEmpty()) {
                appendMessage("✅ Config loaded from assets/config.properties")
                Log.d(TAG, "Config loaded successfully")
            } else {
                appendMessage("⚠️ Please add HUME_API_KEY to assets/config.properties")
                statusText.text = "Missing API key - check assets/config.properties"
            }
        } catch (e: Exception) {
            // Config file doesn't exist, show instructions
            appendMessage("⚠️ Create assets/config.properties with:")
            appendMessage("HUME_API_KEY=your_api_key_here")
            appendMessage("HUME_CONFIG_ID=your_config_id_here")
            statusText.text = "Missing config file - see messages"
            Log.w(TAG, "Config file not found: ${e.message}")
        }
    }

    private fun checkPermissions() {
        val missingPermissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS)
            != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        }
        
        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "Missing permissions: ${missingPermissions.joinToString(", ")}")
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                RECORD_AUDIO_REQUEST_CODE
            )
        } else {
            onPermissionsReady()
        }
    }

    private fun onPermissionsReady() {
        // Check audio hardware availability
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        Log.d(TAG, "🎤 Audio Hardware Check:")
        Log.d(TAG, "  Has microphone: ${packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)}")
        Log.d(TAG, "  Audio mode: ${audioManager.mode}")
        Log.d(TAG, "  Volume: ${audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}")
        Log.d(TAG, "  Max volume: ${audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}")
        
        if (apiKey.isNotEmpty()) {
            connectButton.isEnabled = true
            statusText.text = getString(R.string.status_ready)
            appendMessage("🎤 Audio permissions granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            var allGranted = true
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    Log.w(TAG, "Permission denied: ${permissions[i]}")
                    appendMessage("❌ Permission denied: ${permissions[i]}")
                }
            }
            
            if (allGranted) {
                Toast.makeText(this, getString(R.string.permission_granted), Toast.LENGTH_SHORT).show()
                appendMessage("✅ All audio permissions granted")
                onPermissionsReady()
            } else {
                Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_SHORT).show()
                appendMessage("⚠️ Audio permissions required for voice chat")
            }
        }
    }

    private fun connectToHume() {
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please set API key in assets/config.properties", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            try {
                statusText.text = getString(R.string.status_connecting)
                appendMessage("🔄 Connecting to Hume AI...")

                // Initialize Hume client
                humeClient = HumeEviClient(
                    context = this@MainActivity,
                    apiKey = apiKey,
                    configId = configId
                )

                // Set up event listeners
                setupEventListeners()

                // Connect with interrupt handling enabled
                val success = humeClient!!.connect(allowUserInterrupt = true)

                if (success) {
                    isConnected = true
                    connectButton.isEnabled = false
                    disconnectButton.isEnabled = true
                    statusText.text = getString(R.string.status_connected)
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

    private fun setupEventListeners() {
        val client = humeClient ?: return

        // Listen to connection state changes
        lifecycleScope.launch {
            client.connectionState.collect { state ->
                runOnUiThread {
                    handleConnectionStateChange(state)
                }
            }
        }

        // Listen to EVI messages
        lifecycleScope.launch {
            client.messageEvents.collect { event ->
                runOnUiThread {
                    handleHumeMessage(event)
                }
            }
        }
    }

    private fun handleConnectionStateChange(state: HumeEviWebSocket.ConnectionState) {
        when (state) {
            is HumeEviWebSocket.ConnectionState.Connecting -> {
                statusText.text = getString(R.string.status_connecting)
            }
            is HumeEviWebSocket.ConnectionState.Connected -> {
                statusText.text = getString(R.string.status_connected)
            }
            is HumeEviWebSocket.ConnectionState.Disconnected -> {
                statusText.text = getString(R.string.status_disconnected)
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

    private fun handleHumeMessage(event: SubscribeEvent) {
        when (event) {
            is ChatMetadata -> {
                appendMessage("💬 Chat started - ID: ${event.chatId}")
            }
            
            is UserMessage -> {
                val content = event.message.content ?: ""
                val status = if (event.interim) " (interim)" else ""
                val source = if (event.fromText) " [text]" else " [voice]"
                appendMessage("👤 You: $content$status$source")
                
                // Show emotion data
                event.models.prosody?.let { prosody ->
                    val emotions = prosody.scores
                    val topEmotions = getTopEmotions(emotions, 2)
                    appendMessage("😊 Emotions: ${topEmotions.joinToString(", ") { "${it.first} (${String.format("%.2f", it.second)})" }}")
                }
            }
            
            is AssistantMessage -> {
                val content = event.message.content ?: ""
                val source = if (event.fromText) " [text]" else " [voice]"
                appendMessage("🤖 EVI: $content$source")
                
                // Show assistant emotions
                event.models.prosody?.let { prosody ->
                    val emotions = prosody.scores
                    val topEmotions = getTopEmotions(emotions, 2)
                    appendMessage("🎭 EVI emotions: ${topEmotions.joinToString(", ") { "${it.first} (${String.format("%.2f", it.second)})" }}")
                }
            }
            
            is AssistantEnd -> {
                appendMessage("✅ Assistant finished speaking")
            }
            
            is AudioOutput -> {
                appendMessage("🔊 Audio chunk ${event.index}")
            }
            
            is UserInterruption -> {
                appendMessage("⚡ User interrupted at ${event.time}ms")
            }
            
            is WebSocketError -> {
                appendMessage("❌ Error: ${event.error}")
            }
            
            is ToolCallMessage -> {
                appendMessage("🔧 Tool call: ${event.name}")
                // Handle simple tool calls
                handleToolCall(event)
            }
            
            else -> {
                Log.d(TAG, "Received event: ${event.type}")
            }
        }
    }

    private fun handleToolCall(toolCall: ToolCallMessage) {
        lifecycleScope.launch {
            try {
                when (toolCall.name) {
                    "get_current_time" -> {
                        val currentTime = System.currentTimeMillis()
                        val response = "Current time: ${java.util.Date(currentTime)}"
                        humeClient?.sendToolResponse(toolCall.toolCallId, response)
                        appendMessage("⏰ Time tool response sent")
                    }
                    
                    else -> {
                        humeClient?.sendToolError(
                            toolCall.toolCallId,
                            "Unknown tool: ${toolCall.name}",
                            "Tool not implemented"
                        )
                        appendMessage("❓ Unknown tool: ${toolCall.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution error", e)
                humeClient?.sendToolError(
                    toolCall.toolCallId,
                    "Tool execution failed: ${e.message}",
                    "Internal error"
                )
            }
        }
    }

    private fun getTopEmotions(scores: EmotionScores, count: Int): List<Pair<String, Double>> {
        val emotionMap = mapOf(
            "Admiration" to scores.admiration,
            "Amusement" to scores.amusement,
            "Anger" to scores.anger,
            "Anxiety" to scores.anxiety,
            "Calmness" to scores.calmness,
            "Confusion" to scores.confusion,
            "Contentment" to scores.contentment,
            "Excitement" to scores.excitement,
            "Fear" to scores.fear,
            "Joy" to scores.joy,
            "Love" to scores.love,
            "Sadness" to scores.sadness,
            "Satisfaction" to scores.satisfaction,
            "Surprise (positive)" to scores.surprisePositive,
            "Surprise (negative)" to scores.surpriseNegative
        )
        
        return emotionMap.toList()
            .sortedByDescending { it.second }
            .take(count)
    }

    private fun disconnectFromHume() {
        try {
            humeClient?.disconnect()
            humeClient = null
            isConnected = false
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            statusText.text = getString(R.string.status_disconnected)
            appendMessage("🔴 Disconnected from Hume EVI")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }

    private fun appendMessage(message: String) {
        val currentText = messagesText.text.toString()
        val newText = if (currentText.isEmpty()) {
            message
        } else {
            "$currentText\n$message"
        }
        messagesText.text = newText
        
        // Auto-scroll to bottom by setting selection to end
        messagesText.post {
            val layout = messagesText.layout
            if (layout != null) {
                val scrollY = layout.getLineTop(messagesText.lineCount) - messagesText.height
                if (scrollY > 0) {
                    messagesText.scrollTo(0, scrollY)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isConnected) {
            humeClient?.disconnect()
        }
    }
}