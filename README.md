# Hume AI Voice Chat - Android Kotlin SDK

A complete Android app for natural voice conversations with Hume AI's Empathic Voice Interface (EVI).
## Project Architecture

```
app/src/main/java/com/hume/
├── test/
│   └── MainActivity.kt              # Main application UI and logic
└── empathicvoice/
    ├── HumeEviClient.kt            # High-level client with interrupt handling
    ├── HumeEviWebSocket.kt         # WebSocket protocol implementation  
    ├── HumeAudioManager.kt         # Advanced audio system with echo cancellation
    └── HumeEviDataClasses.kt       # Complete data models and message types
```


## Interrupt Handling
- **Natural Flow**: Users can interrupt assistant speech naturally
- **Python SDK Compatible**: Follows Hume's reference implementation patterns
- **Responsive**: Immediate audio flow control based on conversation state


## Complete Protocol Support
- WebSocket-based real-time communication
- Audio streaming (16kHz input, 48kHz output)
- Full 48-emotion prosody analysis
- Tool calling and responses
- Session configuration management

## Message Types
- **Incoming**: `UserMessage`, `AssistantMessage`, `AudioOutput`, `ToolCall`, `EmotionScores`
- **Outgoing**: `AudioInput`, `SessionSettings`, `UserInput`, `ToolResponse`, `ToolError`

## 📦 Dependencies

- **OkHttp 4.x**: WebSocket communication and HTTP client
- **Gson**: JSON serialization and deserialization  
- **Kotlin Coroutines**: Asynchronous programming and flow control
- **AndroidX Core**: Modern Android component libraries
- **Material Components**: UI design system


