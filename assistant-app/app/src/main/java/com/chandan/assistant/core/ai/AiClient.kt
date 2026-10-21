package com.chandan.assistant.core.ai

import kotlinx.serialization.Serializable

@Serializable
data class RouteResult(
    val action: String = "unknown",
    val app: String = "",
    val recipient: String = "",
    val subject: String = "",
    val text: String = "",
    val steps: List<String> = emptyList(),
    val needs_confirmation: Boolean = false
)

@Serializable
data class AgentStep(
    val action: String = "wait",
    val target: String = "",
    val text: String = "",
    val direction: String = "",
    val message: String = ""
)

@Serializable
data class LocateResult(val found: Boolean = false, val box: List<Int> = emptyList())

@Serializable
data class SkillDef(
    val name: String = "",
    val description: String = "",
    val params: List<String> = emptyList(),
    val steps: List<AgentStep> = emptyList()
)

class AiException(message: String) : Exception(message)

/** Every AI call goes through here. Swap GeminiClient for a ClaudeClient by implementing this. */
interface AiClient {
    suspend fun routeCommand(command: String, people: List<String>, nicknames: List<String>, skills: List<String>): RouteResult
    suspend fun nextAgentStep(goal: String, history: List<String>, screen: String): AgentStep
    suspend fun repairStep(stepDescription: String, screen: String): AgentStep
    suspend fun summarize(text: String): String
    suspend fun locateOnScreen(description: String, jpegBase64: String): LocateResult
    suspend fun buildSkillFromRecording(nameHint: String, recording: String): SkillDef
}
