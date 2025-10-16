package com.hume.empathicvoice

import com.google.gson.annotations.SerializedName

// ============================================================================
// Core Message Types (exactly matching Python SDK)
// ============================================================================

interface SubscribeEvent {
    val type: String
    val customSessionId: String?
}

interface PublishEvent {
    val type: String
}

// ============================================================================
// Supporting Data Classes (complete Python SDK structure)
// ============================================================================

data class MillisecondInterval(
    val begin: Long,
    val end: Long
)

data class ChatMessage(
    val content: String?,
    val role: Role,
    @SerializedName("tool_call") val toolCall: ToolCallMessage? = null,
    @SerializedName("tool_result") val toolResult: ChatMessageToolResult? = null
)

data class ChatMessageToolResult(
    val content: String,
    @SerializedName("tool_call_id") val toolCallId: String
)

enum class Role {
    @SerializedName("assistant") ASSISTANT,
    @SerializedName("system") SYSTEM,
    @SerializedName("user") USER,
    @SerializedName("all") ALL,
    @SerializedName("tool") TOOL
}

data class Inference(
    val prosody: ProsodyInference?
)

data class ProsodyInference(
    val scores: EmotionScores
)

/**
 * Complete emotion scores matching Python SDK exactly
 * All 48 emotions with proper field names
 */
data class EmotionScores(
    @SerializedName("Admiration") val admiration: Double,
    @SerializedName("Adoration") val adoration: Double,
    @SerializedName("Aesthetic Appreciation") val aestheticAppreciation: Double,
    @SerializedName("Amusement") val amusement: Double,
    @SerializedName("Anger") val anger: Double,
    @SerializedName("Anxiety") val anxiety: Double,
    @SerializedName("Awe") val awe: Double,
    @SerializedName("Awkwardness") val awkwardness: Double,
    @SerializedName("Boredom") val boredom: Double,
    @SerializedName("Calmness") val calmness: Double,
    @SerializedName("Concentration") val concentration: Double,
    @SerializedName("Confusion") val confusion: Double,
    @SerializedName("Contemplation") val contemplation: Double,
    @SerializedName("Contempt") val contempt: Double,
    @SerializedName("Contentment") val contentment: Double,
    @SerializedName("Craving") val craving: Double,
    @SerializedName("Desire") val desire: Double,
    @SerializedName("Determination") val determination: Double,
    @SerializedName("Disappointment") val disappointment: Double,
    @SerializedName("Disgust") val disgust: Double,
    @SerializedName("Distress") val distress: Double,
    @SerializedName("Doubt") val doubt: Double,
    @SerializedName("Ecstasy") val ecstasy: Double,
    @SerializedName("Embarrassment") val embarrassment: Double,
    @SerializedName("Empathic Pain") val empathicPain: Double,
    @SerializedName("Entrancement") val entrancement: Double,
    @SerializedName("Envy") val envy: Double,
    @SerializedName("Excitement") val excitement: Double,
    @SerializedName("Fear") val fear: Double,
    @SerializedName("Guilt") val guilt: Double,
    @SerializedName("Horror") val horror: Double,
    @SerializedName("Interest") val interest: Double,
    @SerializedName("Joy") val joy: Double,
    @SerializedName("Love") val love: Double,
    @SerializedName("Nostalgia") val nostalgia: Double,
    @SerializedName("Pain") val pain: Double,
    @SerializedName("Pride") val pride: Double,
    @SerializedName("Realization") val realization: Double,
    @SerializedName("Relief") val relief: Double,
    @SerializedName("Romance") val romance: Double,
    @SerializedName("Sadness") val sadness: Double,
    @SerializedName("Satisfaction") val satisfaction: Double,
    @SerializedName("Shame") val shame: Double,
    @SerializedName("Surprise (negative)") val surpriseNegative: Double,
    @SerializedName("Surprise (positive)") val surprisePositive: Double,
    @SerializedName("Sympathy") val sympathy: Double,
    @SerializedName("Tiredness") val tiredness: Double,
    @SerializedName("Triumph") val triumph: Double
)

data class AudioConfiguration(
    @SerializedName("sample_rate") val sampleRate: Int,
    val channels: Int,
    val encoding: String
)

data class Context(
    val text: String?,
    val type: ContextType
)

enum class ContextType {
    @SerializedName("persistent") PERSISTENT,
    @SerializedName("temporary") TEMPORARY
}

enum class ToolType {
    @SerializedName("builtin") BUILTIN,
    @SerializedName("function") FUNCTION
}

data class Tool(
    val id: String,
    val version: Int? = null,
    val name: String,
    val description: String? = null,
    val parameters: String? = null,
    @SerializedName("fallback_content") val fallbackContent: String? = null
)

data class BuiltinToolConfig(
    val name: String,
    @SerializedName("fallback_content") val fallbackContent: String? = null
)

// ============================================================================
// Incoming Messages (SubscribeEvent) - Complete with all fields and type
// ============================================================================

data class ChatMetadata(
    override val type: String = "chat_metadata",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    @SerializedName("chat_group_id") val chatGroupId: String,
    @SerializedName("chat_id") val chatId: String
) : SubscribeEvent

data class UserMessage(
    override val type: String = "user_message",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    @SerializedName("from_text") val fromText: Boolean,
    val interim: Boolean,
    val message: ChatMessage,
    val models: Inference,
    val time: MillisecondInterval
) : SubscribeEvent

data class AssistantMessage(
    override val type: String = "assistant_message",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    @SerializedName("from_text") val fromText: Boolean,
    val id: String? = null,
    val message: ChatMessage,
    val models: Inference
) : SubscribeEvent

data class AssistantEnd(
    override val type: String = "assistant_end",
    @SerializedName("custom_session_id") override val customSessionId: String? = null
) : SubscribeEvent

data class AssistantProsody(
    override val type: String = "assistant_prosody",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    val id: String? = null,
    val models: Inference
) : SubscribeEvent

data class AudioOutput(
    override val type: String = "audio_output",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    val data: String,
    val id: String,
    val index: Int
) : SubscribeEvent

data class UserInterruption(
    override val type: String = "user_interruption",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    val time: Long // Unix timestamp - missing from previous implementation
) : SubscribeEvent

data class ToolCallMessage(
    override val type: String = "tool_call",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    @SerializedName("tool_call_id") val toolCallId: String,
    val name: String,
    val parameters: String,
    @SerializedName("response_required") val responseRequired: Boolean,
    @SerializedName("tool_type") val toolType: ToolType? = null // Proper enum, not string
) : SubscribeEvent

data class ToolResponseMessage(
    override val type: String = "tool_response",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    @SerializedName("tool_call_id") val toolCallId: String,
    val content: String
) : SubscribeEvent, PublishEvent

data class ToolErrorMessage(
    override val type: String = "tool_error",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    @SerializedName("tool_call_id") val toolCallId: String,
    val error: String,
    val content: String? = null,
    val level: String = "warn"
) : SubscribeEvent, PublishEvent

data class WebSocketError(
    override val type: String = "error",
    @SerializedName("custom_session_id") override val customSessionId: String? = null,
    val error: String,
    val code: String? = null
) : SubscribeEvent

// ============================================================================
// Outgoing Messages (PublishEvent) - Complete with all fields and type
// ============================================================================

data class AudioInput(
    override val type: String = "audio_input",
    @SerializedName("custom_session_id") val customSessionId: String? = null,
    val data: String
) : PublishEvent

data class SessionSettings(
    override val type: String = "session_settings",
    val audio: AudioConfiguration? = null,
    @SerializedName("builtin_tools") val builtinTools: List<BuiltinToolConfig>? = null,
    val context: Context? = null,
    @SerializedName("custom_session_id") val customSessionId: String? = null,
    @SerializedName("language_model_api_key") val languageModelApiKey: String? = null,
    val metadata: Map<String, Any?>? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    val tools: List<Tool>? = null,
    val variables: Map<String, Any>? = null,
    @SerializedName("voice_id") val voiceId: String? = null
) : PublishEvent

data class UserInput(
    override val type: String = "user_input",
    @SerializedName("custom_session_id") val customSessionId: String? = null,
    val text: String
) : PublishEvent

data class AssistantInput(
    override val type: String = "assistant_input",
    @SerializedName("custom_session_id") val customSessionId: String? = null,
    val text: String
) : PublishEvent

data class PauseAssistantMessage(
    override val type: String = "pause_assistant_message"
) : PublishEvent

data class ResumeAssistantMessage(
    override val type: String = "resume_assistant_message"
) : PublishEvent