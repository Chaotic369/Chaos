package com.chandan.assistant.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKey: String, private val model: String) : AiClient {
    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
        private val ACTIONS = listOf(
            "call", "open_app", "open_url", "send_message", "send_email", "screen_task",
            "run_skill", "system", "set_alarm", "create_trigger", "unknown"
        )
        private const val SYSTEM = "You convert one spoken Hindi/English phone command into JSON. " +
            "action is one of: call, open_app, open_url, send_message, send_email, screen_task, run_skill, system, set_alarm, create_trigger, unknown. " +
            "app = target app name (whatsapp, telegram, instagram, gmail, sms, chrome, or any other app name). " +
            "recipient = person name as spoken; use a name from the known list if one is close. " +
            "subject and text = the message content in the user's own words, nothing added. " +
            "set_alarm: text = time in 24h HH:mm. open_url: text = URL or nickname. " +
            "screen_task: steps = short ordered UI instructions. system: text = what to change, e.g. wifi on. " +
            "run_skill: app = skill name from the known skills, steps = [\"param=value\", ...]. " +
            "Never invent phone numbers or addresses."
    }

    private val schema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "STRING")
                putJsonArray("enum") { ACTIONS.forEach { add(it) } }
            }
            for (k in listOf("app", "recipient", "subject", "text")) {
                putJsonObject(k) { put("type", "STRING") }
            }
            putJsonObject("steps") {
                put("type", "ARRAY")
                putJsonObject("items") { put("type", "STRING") }
            }
            putJsonObject("needs_confirmation") { put("type", "BOOLEAN") }
        }
        putJsonArray("required") { add("action") }
    }

    override suspend fun routeCommand(command: String, people: List<String>, nicknames: List<String>, skills: List<String>): RouteResult {
        val user = "Known people: ${people.joinToString(", ").ifEmpty { "none" }}\n" +
            "Known nicknames: ${nicknames.joinToString(", ").ifEmpty { "none" }}\n" +
            "Known skills: ${skills.joinToString(", ").ifEmpty { "none" }}\n" +
            "Command: $command"
        val text = generate(SYSTEM, user, schema)
        return try {
            json.decodeFromString(RouteResult.serializer(), text)
        } catch (e: Exception) {
            throw AiException("AI returned something I couldn't read")
        }
    }

    private val agentActions = listOf(
        "click", "tap_visual", "long_click", "type", "enter", "scroll", "back", "home", "open_app", "wait", "done", "ask_user", "fail"
    )

    private val agentSystem = "You control an Android phone screen to reach a goal. " +
        "You get the goal, past actions, and the current screen as a list of tappable nodes: [N] BTN/EDIT text desc id bounds. " +
        "Reply with ONE next action. action: click (target=#N or visible label), long_click, type (target=#N of an EDIT node, or empty for the focused field; text=what to type), " +
        "enter (press Enter/Search on the keyboard), scroll (direction=up or down), back, home, open_app (target=app name), wait, " +
        "tap_visual (target=description of what to tap, only if the needed element is missing from the list), " +
        "done (message=short result, use as soon as the goal is complete), ask_user (message=question), fail (message=why). " +
        "Prefer #N targets. Never repeat an action that failed or changed nothing; try something different."

    private val agentSchema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "STRING")
                putJsonArray("enum") { agentActions.forEach { add(it) } }
            }
            for (k in listOf("target", "text", "direction", "message")) {
                putJsonObject(k) { put("type", "STRING") }
            }
        }
        putJsonArray("required") { add("action") }
    }

    override suspend fun nextAgentStep(goal: String, history: List<String>, screen: String): AgentStep {
        val user = "Goal: $goal\nPast actions:\n${history.takeLast(8).joinToString("\n").ifEmpty { "none" }}\nScreen:\n$screen"
        return decodeStep(generate(agentSystem, user, agentSchema))
    }

    override suspend fun repairStep(stepDescription: String, screen: String): AgentStep {
        val user = "A scripted step failed: $stepDescription\nFind the right element on this screen and give ONE action that accomplishes that step.\nScreen:\n$screen"
        return decodeStep(generate(agentSystem, user, agentSchema))
    }

    private val locateSchema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("found") { put("type", "BOOLEAN") }
            putJsonObject("box") {
                put("type", "ARRAY")
                putJsonObject("items") { put("type", "INTEGER") }
            }
        }
        putJsonArray("required") { add("found") }
    }

    private val skillSchema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "STRING") }
            putJsonObject("description") { put("type", "STRING") }
            putJsonObject("params") {
                put("type", "ARRAY")
                putJsonObject("items") { put("type", "STRING") }
            }
            putJsonObject("steps") {
                put("type", "ARRAY")
                put("items", agentSchema)
            }
        }
        putJsonArray("required") {
            add("name")
            add("steps")
        }
    }

    override suspend fun locateOnScreen(description: String, jpegBase64: String): LocateResult {
        val sys = "Find the UI element described in the screenshot. box = [ymin, xmin, ymax, xmax] normalized 0-1000. found=false if it is not visible."
        val t = generate(sys, "Find: $description", locateSchema, jpegBase64)
        return try {
            json.decodeFromString(LocateResult.serializer(), t)
        } catch (e: Exception) {
            throw AiException("AI returned something I couldn't read")
        }
    }

    override suspend fun buildSkillFromRecording(nameHint: String, recording: String): SkillDef {
        val sys = "Turn a recording of a user's phone actions into a reusable skill. " +
            "Recording lines: app <label> (<package>), click text=.. id=.., type text=.. field=.., scroll. " +
            "Output name (short snake_case), description, params (names of values that should change each run, e.g. question), and steps. " +
            "Step actions: open_app (target = app label), click (target = the visible text or id from the recording), " +
            "type (target = field hint or empty, text = typed text with the variable part replaced by {param}), enter, scroll (direction), back, home, wait. " +
            "Add a wait step after opening an app. Merge duplicate or noisy lines. Never include passwords."
        val user = "Name hint: ${nameHint.ifBlank { "none" }}\nRecording:\n$recording"
        val t = generate(sys, user, skillSchema)
        return try {
            json.decodeFromString(SkillDef.serializer(), t)
        } catch (e: Exception) {
            throw AiException("AI returned something I couldn't read")
        }
    }

    private val summarySchema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") { putJsonObject("summary") { put("type", "STRING") } }
        putJsonArray("required") { add("summary") }
    }

    override suspend fun summarize(text: String): String {
        val sys = "Summarize these phone notifications to be spoken aloud: at most 3 short sentences, say who and what, no lists."
        val t = generate(sys, text, summarySchema)
        return try {
            json.parseToJsonElement(t).jsonObject["summary"]?.jsonPrimitive?.content ?: throw AiException("Empty summary")
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("AI returned something I couldn't read")
        }
    }

    private fun decodeStep(t: String): AgentStep = try {
        json.decodeFromString(AgentStep.serializer(), t)
    } catch (e: Exception) {
        throw AiException("AI returned something I couldn't read")
    }

    private suspend fun generate(system: String, user: String, schema: JsonObject, imageB64: String? = null): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        if (imageB64 != null) {
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", "image/jpeg")
                                    put("data", imageB64)
                                }
                            }
                        }
                        addJsonObject { put("text", user) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0)
                put("responseMimeType", "application/json")
                put("responseSchema", schema)
            }
        }
        return call(body.toString())
    }

    private suspend fun call(body: String): String = withContext(Dispatchers.IO) {
        for (attempt in 0..1) {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val (code, s) = http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
            if (code == 429 && attempt == 0) {
                delay(1500)
                continue
            }
            if (code == 429) throw AiException("Gemini rate limit (429). Wait a few seconds and try again.")
            if (code !in 200..299) throw AiException("Gemini error $code: ${s.take(200)}")
            return@withContext extractText(s)
        }
        throw AiException("Gemini request failed")
    }

    private fun extractText(s: String): String {
        val t = json.parseToJsonElement(s).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
        return t ?: throw AiException("Empty AI reply")
    }
}
