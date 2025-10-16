package com.hume.empathicvoice

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for Hume's Empathic Voice Interface (EVI)
 * Package naming matches Python SDK: hume.empathic_voice -> com.hume.empathicvoice
 */
class HumeEviWebSocket(
    private val apiKey: String,
    private val configId: String? = null,
    private val configVersion: String? = null,
    private val resumedChatGroupId: String? = null
) {
    companion object {
        private const val TAG = "HumeEviWebSocket"
        private const val WEBSOCKET_URL = "wss://api.hume.ai/v0/evi/chat"
        private const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024 // 16MB
    }

    private val gson = Gson()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for streaming
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false

    // Event flows for received messages
    private val _messageEvents = MutableSharedFlow<SubscribeEvent>()
    val messageEvents: SharedFlow<SubscribeEvent> = _messageEvents.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>()
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    private val _audioOutput = MutableSharedFlow<ByteArray>()
    val audioOutput: SharedFlow<ByteArray> = _audioOutput.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Connect to the EVI WebSocket
     */
    suspend fun connect(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = buildWebSocketUrl()
                val request = Request.Builder()
                    .url(url)
                    .build()

                webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect", e)
                _connectionState.tryEmit(ConnectionState.Error(e.message ?: "Connection failed"))
                false
            }
        }
    }

    /**
     * Disconnect from the WebSocket
     */
    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
        scope.cancel()
    }

    /**
     * Send audio input to EVI
     * @param audioData Raw PCM audio data
     */
    suspend fun sendAudioInput(audioData: ByteArray) {
        if (!isConnected) {
            Log.w(TAG, "Not connected, cannot send audio")
            return
        }

        try {
            val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
            val audioInput = AudioInput(data = base64Audio)
            sendMessage(audioInput)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio input", e)
        }
    }

    /**
     * Send session settings to configure audio parameters
     */
    suspend fun sendSessionSettings(sessionSettings: SessionSettings) {
        sendMessage(sessionSettings)
    }

    /**
     * Send user text input
     */
    suspend fun sendUserInput(text: String) {
        val userInput = UserInput(text = text)
        sendMessage(userInput)
    }

    /**
     * Send assistant input
     */
    suspend fun sendAssistantInput(text: String) {
        val assistantInput = AssistantInput(text = text)
        sendMessage(assistantInput)
    }

    /**
     * Send tool response
     */
    suspend fun sendToolResponse(toolCallId: String, content: String) {
        val toolResponse = ToolResponseMessage(
            toolCallId = toolCallId,
            content = content
        )
        sendMessage(toolResponse)
    }

    /**
     * Send tool error
     */
    suspend fun sendToolError(toolCallId: String, error: String, content: String? = null) {
        val toolError = ToolErrorMessage(
            toolCallId = toolCallId,
            error = error,
            content = content
        )
        sendMessage(toolError)
    }

    /**
     * Pause assistant responses
     */
    suspend fun pauseAssistant() {
        sendMessage(PauseAssistantMessage())
    }

    /**
     * Resume assistant responses
     */
    suspend fun resumeAssistant() {
        sendMessage(ResumeAssistantMessage())
    }

    private suspend fun sendMessage(message: PublishEvent) {
        if (!isConnected) {
            Log.w(TAG, "Not connected, cannot send message")
            return
        }

        try {
            val json = gson.toJson(message)
            Log.d(TAG, "Sending message: $json")
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
        }
    }

    private fun buildWebSocketUrl(): String {
        val urlBuilder = StringBuilder(WEBSOCKET_URL)
        urlBuilder.append("?apiKey=").append(apiKey)

        configId?.let {
            urlBuilder.append("&config_id=").append(it)
        }

        configVersion?.let {
            urlBuilder.append("&config_version=").append(it)
        }

        resumedChatGroupId?.let {
            urlBuilder.append("&resumed_chat_group_id=").append(it)
        }

        return urlBuilder.toString()
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            isConnected = true
            _connectionState.tryEmit(ConnectionState.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                try {
                    Log.d(TAG, "Received message: $text")
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling message", e)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            isConnected = false
            _connectionState.tryEmit(ConnectionState.Disconnecting)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            isConnected = false
            _connectionState.tryEmit(ConnectionState.Disconnected)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error", t)
            isConnected = false
            _connectionState.tryEmit(ConnectionState.Error(t.message ?: "WebSocket error"))
        }
    }

    private suspend fun handleMessage(messageText: String) {
        try {
            // Parse the message type first
            val messageType = gson.fromJson(messageText, MessageTypeOnly::class.java)
            
            val event: SubscribeEvent = when (messageType.type) {
                "chat_metadata" -> gson.fromJson(messageText, ChatMetadata::class.java)
                "user_message" -> gson.fromJson(messageText, UserMessage::class.java)
                "assistant_message" -> gson.fromJson(messageText, AssistantMessage::class.java)
                "assistant_end" -> gson.fromJson(messageText, AssistantEnd::class.java)
                "assistant_prosody" -> gson.fromJson(messageText, AssistantProsody::class.java)
                "audio_output" -> {
                    val audioOutput = gson.fromJson(messageText, AudioOutput::class.java)
                    // Decode and emit audio data
                    try {
                        val audioBytes = Base64.decode(audioOutput.data, Base64.DEFAULT)
                        _audioOutput.tryEmit(audioBytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode audio data", e)
                    }
                    audioOutput
                }
                "user_interruption" -> gson.fromJson(messageText, UserInterruption::class.java)
                "tool_call" -> gson.fromJson(messageText, ToolCallMessage::class.java)
                "tool_response" -> gson.fromJson(messageText, ToolResponseMessage::class.java)
                "tool_error" -> gson.fromJson(messageText, ToolErrorMessage::class.java)
                "error" -> gson.fromJson(messageText, WebSocketError::class.java)
                else -> {
                    Log.w(TAG, "Unknown message type: ${messageType.type}")
                    return
                }
            }

            _messageEvents.tryEmit(event)

        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse message: $messageText", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }

    private data class MessageTypeOnly(val type: String)

    sealed class ConnectionState {
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        object Disconnecting : ConnectionState()
        object Disconnected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
}