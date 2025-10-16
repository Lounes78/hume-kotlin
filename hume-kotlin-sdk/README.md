# Hume AI Empathic Voice Interface (EVI) - Kotlin SDK

A complete Kotlin implementation of Hume AI's Empathic Voice Interface for Android, precisely following the official Python SDK patterns and message structures. This SDK provides speech-to-speech functionality with full emotion inference and proper interrupt handling.

## 🚀 Key Features

- **Complete Protocol Compliance** - All message types include required `type` fields and match Python SDK exactly
- **Full Emotion Inference** - Access to prosody model results with 48 emotion measurements  
- **Proper Interrupt Handling** - Implements Python SDK's `send_audio` flag for natural turn-taking
- **Complete Message Types** - All EVI message types including AssistantEnd, AssistantProsody, etc.
- **Comprehensive SessionSettings** - Full support for tools, variables, metadata, and context injection
- **Android Automotive Optimized** - Built specifically for car environments

## 🏗️ Architecture

### Core Components

1. **`HumeEviDataClasses.kt`** - Complete message type definitions matching Python SDK
2. **`HumeEviWebSocket.kt`** - WebSocket client with full protocol support
3. **`HumeAudioManager.kt`** - Simple continuous audio streaming (no client-side VAD)
4. **`HumeEviClient.kt`** - High-level client with proper interrupt handling
5. **`HumeEviActivity.kt`** - Complete example with emotion data access

### Message Flow

```
Audio Input → Base64 Encode → WebSocket → EVI Processing → Audio Output
     ↑                                                          ↓
Microphone ←—————————— Interrupt Handling ——————————→ Speaker
```

## 🎯 Quick Start

### 1. Basic Setup

```kotlin
val humeClient = HumeEviClient(
    context = tNhis,
    apiKey = "your-hume-api-key",
    configId = "your-config-id" // Optional
)

// Connect with proper interrupt handling
lifecycleScope.launch {
    val success = humeClient.connect(allowUserInterrupt = true)
    // Ready to talk!
}
```

### 2. Access Complete Message Data

```kotlin
humeClient.messageEvents.collect { event ->
    when (event) {
        is UserMessage -> {
            val transcript = event.message.content
            val isInterim = event.interim
            val emotions = event.models.prosody?.scores
            val timeRange = event.time // MillisecondInterval
        }
        is AssistantMessage -> {
            val response = event.message.content
            val assistantEmotions = event.models.prosody?.scores
            val messageId = event.id
        }
        is AudioOutput -> {
            // Audio chunk with index and ID for tracking
            val chunkIndex = event.index
            val audioId = event.id
        }
    }
}
```

### 3. Tool Handling with Complete Data

```kotlin
private suspend fun handleToolCall(toolCall: ToolCallMessage) {
    // Access all tool call data
    val toolName = toolCall.name
    val parameters = toolCall.parameters // JSON string
    val callId = toolCall.toolCallId
    val responseRequired = toolCall.responseRequired
    
    when (toolName) {
        "get_weather" -> {
            val result = getWeatherData(parameters)
            humeClient.sendToolResponse(callId, result)
        }
        else -> {
            humeClient.sendToolError(
                callId, 
                "Unknown tool: $toolName",
                "Tool not implemented"
            )
        }
    }
}
```

## 🎛️ Interrupt Handling (Critical Fix)

The SDK now properly implements the Python SDK's interrupt handling pattern:

```kotlin
// Python SDK equivalent: MicrophoneSender.send_audio flag
private val sendAudio = AtomicBoolean(true)

// When assistant starts speaking
is AudioOutput -> {
    sendAudio.set(allowInterrupt) // Stop audio unless interrupts allowed
}

// When assistant finishes
is AssistantEnd -> {
    sendAudio.set(true) // Resume audio input
}

// Only send audio when flag is true
if (sendAudio.get()) {
    webSocket.sendAudioInput(audioData)
}
```

## 📊 Emotion Data Access

Access complete emotion inference data from the prosody model:

```kotlin
is UserMessage -> {
    event.models.prosody?.scores?.let { emotionScores ->
        // Map of 48 emotions with confidence scores
        val topEmotion = emotionScores.maxByOrNull { it.value }
        val joy = emotionScores["Joy"] ?: 0.0
        val excitement = emotionScores["Excitement"] ?: 0.0
        // ... all 48 emotions available
    }
}
```

## ⚙️ Complete SessionSettings

Configure all aspects of the EVI session:

```kotlin
val sessionSettings = SessionSettings(
    audio = AudioConfiguration(
        sampleRate = 16000,
        channels = 1,
        encoding = "linear16"
    ),
    systemPrompt = "You are a helpful car assistant",
    variables = mapOf(
        "user_name" to "John",
        "car_model" to "Tesla Model 3"
    ),
    tools = listOf(
        Tool(
            id = "weather-tool",
            name = "get_weather",
            description = "Get current weather"
        )
    ),
    builtinTools = listOf(
        BuiltinToolConfig(name = "web_search")
    ),
    context = Context(
        text = "The user is driving and needs quick responses",
        type = ContextType.PERSISTENT
    ),
    voiceId = "custom-voice-id",
    metadata = mapOf("session_type" to "automotive")
)
```

## 🔄 Complete Message Types

### Incoming Messages (SubscribeEvent)
- `ChatMetadata` - Session info with chat_id and chat_group_id
- `UserMessage` - Complete with transcript, emotions, timing, interim flag
- `AssistantMessage` - Full response with emotions and message ID
- `AssistantEnd` - Signals end of assistant response (critical for interrupts)
- `AssistantProsody` - Emotion data for assistant's speech
- `AudioOutput` - Audio chunks with index and ID tracking
- `UserInterruption` - User interrupted the assistant
- `ToolCallMessage` - Complete tool call with all parameters
- `ToolResponseMessage` - Tool execution results
- `ToolErrorMessage` - Tool errors with details
- `WebSocketError` - Connection/API errors

### Outgoing Messages (PublishEvent)
- `AudioInput` - Voice data with custom_session_id support
- `SessionSettings` - Complete configuration including tools/variables
- `UserInput` - Text input messages
- `AssistantInput` - Assistant message injection
- `ToolResponseMessage` - Tool results
- `ToolErrorMessage` - Tool execution errors
- `PauseAssistantMessage` - Pause responses
- `ResumeAssistantMessage` - Resume responses

## 🚗 Android Automotive Integration

### Audio Routing
```kotlin
// Optimized for car audio systems
AudioManager.STREAM_MUSIC // Car-compatible audio stream
SAMPLE_RATE = 16000 // Optimal for car microphones
```

### Permission Handling
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

### Lifecycle Management
```kotlin
override fun onDestroy() {
    super.onDestroy()
    if (isConnected) {
        humeClient.disconnect() // Proper cleanup
    }
}
```

## 📱 Dependencies

```kotlin
dependencies {
    implementation 'com.squareup.okhttp3:okhttp:4.11.0'
    implementation 'com.google.code.gson:gson:2.10.1'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
}
```

## 🐛 Error Handling

Complete error handling for all scenarios:

```kotlin
// Connection errors
humeClient.connectionState.collect { state ->
    when (state) {
        is HumeEviWebSocket.ConnectionState.Error -> {
            Log.e(TAG, "Connection error: ${state.message}")
        }
    }
}

// Protocol errors  
is WebSocketError -> {
    Log.e(TAG, "API error: ${event.error} (code: ${event.code})")
}

// Tool errors
is ToolErrorMessage -> {
    Log.e(TAG, "Tool error: ${event.error} - ${event.content}")
}
```

## 🔍 Debugging

Enable detailed logging to track message flow:

```kotlin
// In HumeEviClient
private fun setupMessageHandling() {
    scope.launch {
        webSocket.messageEvents.collect { event ->
            Log.d(TAG, "Received: ${event.type}")
            when (event) {
                is AudioOutput -> {
                    Log.d(TAG, "Audio chunk ${event.index} received")
                }
                is UserMessage -> {
                    Log.d(TAG, "User: ${event.message.content} (interim=${event.interim})")
                }
                // ... detailed logging for all message types
            }
        }
    }
}
```

## ✅ Protocol Compliance Checklist

- [x] All message types include required `type` fields
- [x] Complete emotion inference with prosody model access  
- [x] Proper interrupt handling with send_audio flag pattern
- [x] All message types (AssistantEnd, AssistantProsody, etc.)
- [x] Complete SessionSettings with tools/variables/metadata
- [x] Base64 audio encoding/decoding
- [x] WAV header processing for audio chunks
- [x] Tool calling with complete parameter access
- [x] Context injection and dynamic variables
- [x] Custom session ID support
- [x] Error handling for all scenarios

## 🎯 Integration with Existing Project

To integrate with your existing Android project at `C:\Users\az03732\Desktop\sesame_ai\android_sesame_ai`:

1. Copy the `hume-kotlin/` files to your project
2. Replace your existing WebSocket client with `HumeEviClient`
3. Update message handling to use the complete message types
4. Configure your Hume API key and configuration ID

```kotlin
// Replace existing WebSocket usage
val humeClient = HumeEviClient(this, "your-api-key", "your-config-id")
val success = humeClient.connect(allowUserInterrupt = true)
```

## 📚 Further Reading

- [Hume AI EVI Documentation](https://docs.hume.ai/docs/speech-to-speech-evi)
- [Python SDK Reference](https://github.com/HumeAI/hume-python-sdk)
- [EVI API Reference](https://docs.hume.ai/reference/speech-to-speech-evi/chat)

---

**This implementation is now fully compliant with the Hume EVI protocol and includes all critical fixes for production use.**