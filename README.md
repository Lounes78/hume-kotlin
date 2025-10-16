# Hume AI Test App

A simple Android application to test Hume AI's Empathic Voice Interface (EVI) using the hume-kotlin-sdk.

## Features

- 🎤 **Voice Interaction**: Real-time speech-to-speech communication with Hume AI
- 😊 **Emotion Detection**: Shows emotion analysis for both user and assistant
- 🔧 **Tool Support**: Basic tool call handling (time example included)
- ⚡ **Interrupt Handling**: Proper interrupt management for natural conversations
- 📱 **Simple UI**: Clean interface with connection status and message logging

## Setup Instructions

### 1. Get Hume AI Credentials

1. Sign up at [Hume AI Platform](https://platform.hume.ai/)
2. Get your API key from the dashboard
3. (Optional) Create a custom configuration for your use case

### 2. Configure the App

1. Open `app/src/main/assets/config.properties`
2. Replace the placeholder values with your actual credentials:

```properties
HUME_API_KEY=your_actual_api_key_here
HUME_CONFIG_ID=your_config_id_here
```

Example:
```properties
HUME_API_KEY=sk-1234567890abcdef1234567890abcdef
HUME_CONFIG_ID=config-1234567890abcdef
```

### 3. Build and Run

1. Open the project in Android Studio
2. Sync the project with Gradle files
3. Connect an Android device or start an emulator (API level 24+)
4. Run the app

### 4. Grant Permissions

When you first run the app, it will request microphone permission. This is required for voice interaction.

## How to Use

1. **Connect**: Tap "Connect to Hume AI" button
2. **Talk**: Once connected, start speaking naturally
3. **View Results**: Watch the messages panel for:
   - Your speech transcripts with emotion analysis
   - EVI's responses with emotion data
   - Connection status updates
   - Audio processing information

## Project Structure

```
app/src/main/java/com/hume/
├── test/
│   └── MainActivity.kt              # Main application logic
└── empathicvoice/
    ├── HumeEviClient.kt            # High-level client wrapper
    ├── HumeEviWebSocket.kt         # WebSocket communication
    ├── HumeAudioManager.kt         # Audio capture and playback
    └── HumeEviDataClasses.kt       # Data models and message types
```

## SDK Features

This app demonstrates the complete hume-kotlin-sdk functionality:

### Core Features
- ✅ Complete WebSocket protocol implementation
- ✅ Real-time audio streaming (16kHz, mono, PCM)
- ✅ Full emotion inference (48 emotions)
- ✅ Proper interrupt handling
- ✅ Tool calling support
- ✅ Session configuration
- ✅ Error handling

### Message Types Supported
- **Incoming**: UserMessage, AssistantMessage, AudioOutput, ToolCall, etc.
- **Outgoing**: AudioInput, SessionSettings, UserInput, ToolResponse, etc.

### Emotion Analysis
The app shows real-time emotion detection including:
- Joy, Excitement, Calmness
- Anxiety, Anger, Sadness
- Surprise (positive/negative)
- And 41 other emotions from Hume's prosody model

## Troubleshooting

### Connection Issues
- Verify your API key is correct
- Check internet connectivity
- Look for error messages in the app logs

### Audio Issues
- Ensure microphone permission is granted
- Test on a physical device (emulator audio may be limited)
- Check device volume settings

### Build Issues
- Sync project with Gradle files
- Ensure minimum SDK version is 24+
- Check that all dependencies are downloaded

## Development Notes

- Based on the reference project structure from `android_sesame_ai`
- Compatible with Android API 24+ (Android 7.0)
- Uses Kotlin coroutines for async operations
- Implements Material Design UI components

## Dependencies

- **OkHttp**: WebSocket communication
- **Gson**: JSON serialization
- **Kotlin Coroutines**: Async programming
- **AndroidX**: Modern Android components

## License

This is a test application for demonstrating Hume AI integration. Please refer to Hume AI's terms of service for API usage.