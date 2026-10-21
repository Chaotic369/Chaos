package com.chandan.assistant

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chandan.assistant.core.Action
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.core.AgentRunner
import com.chandan.assistant.core.AssistantService
import com.chandan.assistant.core.Bus
import com.chandan.assistant.core.Triggers
import com.chandan.assistant.data.Trigger
import com.chandan.assistant.core.CommandRouter
import com.chandan.assistant.core.Outcome
import com.chandan.assistant.core.SkillRunner
import com.chandan.assistant.core.stepsJson
import com.chandan.assistant.data.Skill
import com.chandan.assistant.core.ai.AgentStep
import com.chandan.assistant.core.ai.GeminiClient
import com.chandan.assistant.core.Executor
import com.chandan.assistant.core.FastRouter
import com.chandan.assistant.core.VoiceController
import com.chandan.assistant.core.describe
import com.chandan.assistant.data.Alias
import com.chandan.assistant.data.AppDb
import com.chandan.assistant.data.LogEntry
import com.chandan.assistant.data.Nickname
import com.chandan.assistant.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    private val prefs = Prefs(app)
    private val router = CommandRouter(app, db, FastRouter(app, db))
    private val executor = Executor(app).also { ex ->
        ex.aiProvider = { if (apiKey.value.isBlank()) null else GeminiClient(apiKey.value, model.value) }
    }
    val voice = VoiceController(app)

    val transcript = MutableStateFlow("")
    val status = MutableStateFlow("Tap the mic and speak")
    val busy = MutableStateFlow(false)
    val apiKey = MutableStateFlow(prefs.apiKey)
    val model = MutableStateFlow(prefs.model)
    val lang = MutableStateFlow(prefs.lang)

    val aliases = db.aliasDao().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val nicknames = db.nicknameDao().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val log = db.logDao().recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val skills = db.skillDao().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val teaching = MutableStateFlow(false)
    private var teachHint = ""

    val onboarded = MutableStateFlow(prefs.onboarded)
    val keepRunning = MutableStateFlow(prefs.keepRunning)
    val voiceStop = MutableStateFlow(prefs.voiceStop)
    val triggers = db.triggerDao().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val stopper: () -> Unit = { stop() }

    init {
            Bus.addStopper(stopper)
            if (prefs.keepRunning) AssistantService.start(app)
            viewModelScope.launch { busy.collect { Bus.setBusy("vm", it) } }
            viewModelScope.launch { Bus.statusText.collect { if (!busy.value) status.value = it } }
        }

    fun finishOnboarding() {
        prefs.onboarded = true
        onboarded.value = true
        if (prefs.keepRunning) AssistantService.start(getApplication())
    }

    fun setKeepRunning(b: Boolean) {
        prefs.keepRunning = b
        keepRunning.value = b
        if (b) AssistantService.start(getApplication()) else AssistantService.stop(getApplication())
    }

    fun setVoiceStop(b: Boolean) {
        prefs.voiceStop = b
        voiceStop.value = b
    }

    fun saveTrigger(t: Trigger) {
        viewModelScope.launch {
            val id = db.triggerDao().upsert(t)
            Triggers.schedule(getApplication(), t.copy(id = if (t.id == 0L) id else t.id))
        }
    }

    fun deleteTrigger(t: Trigger) {
        viewModelScope.launch {
            Triggers.cancel(getApplication(), t)
            db.triggerDao().delete(t)
        }
    }

    private var job: Job? = null

    fun startListening() {
        voice.stopSpeaking()
        status.value = "Listening…"
        voice.listen(lang.value, { transcript.value = it }, { submit(it) }, { status.value = it })
    }

    fun submit(text: String) {
        val cmd = text.trim()
        if (cmd.isEmpty()) return
        job?.cancel()
        transcript.value = cmd
        job = viewModelScope.launch {
            busy.value = true
            AssistantAccessibilityService.instance?.showStop { stop() }
            try {
                val split = cmd.split(",", "।").map { it.trim() }.filter { it.isNotEmpty() }
                val parts = if (split.size > 1 && split.any { router.fastOnly(it) is Action.NoMatch }) listOf(cmd) else split
                for ((i, p) in parts.withIndex()) {
                    val t0 = SystemClock.elapsedRealtime()
                    val routed = router.route(p, apiKey.value, model.value)
                    val action = routed.action
                    if (action is Action.Stop) {
                        stop()
                        return@launch
                    }
                    status.value = "Step ${i + 1}/${parts.size}: ${describe(action)}"
                    val out = execute(action)
                    val ms = SystemClock.elapsedRealtime() - t0
                    db.logDao().insert(
                        LogEntry(
                            time = System.currentTimeMillis(), command = p, route = routed.route,
                            action = describe(action), success = out.ok, ms = ms, detail = out.message
                        )
                    )
                    status.value = out.message
                                        voice.speak(out.message)
                                        if (!out.ok) break
                                        if (i < parts.lastIndex) delay(1800)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    val msg = e.message ?: "Something went wrong"
                                    status.value = msg
                                    voice.speak(msg)
                                } finally {
                                    busy.value = false
                                    if (!teaching.value) AssistantAccessibilityService.instance?.hideStop()
                                }
                            }
                        }

    fun stop() {
        job?.cancel()
        voice.stopListening()
        voice.stopSpeaking()
        if (teaching.value) {
            teaching.value = false
            AssistantAccessibilityService.instance?.stopRecording()
        }
        busy.value = false
        AssistantAccessibilityService.instance?.hideStop()
        status.value = "Stopped"
    }

    private fun aiOrNull(): GeminiClient? = if (apiKey.value.isBlank()) null else GeminiClient(apiKey.value, model.value)

    private suspend fun execute(a: Action): Outcome = when (a) {
        is Action.Agent -> runAgent(a.goal)
        is Action.RunSkill -> runSkill(a.skillName, a.args)
        is Action.TeachStart -> startTeaching(a.nameHint)
        is Action.TeachDone -> finishTeaching()
        else -> executor.run(a)
    }

    private suspend fun runSkill(name: String, args: Map<String, String>): Outcome =
        SkillRunner(getApplication(), db, aiOrNull()) { status.value = it }.run(name, args)

    fun runSkillFromUi(name: String, args: Map<String, String>) {
        job?.cancel()
        transcript.value = "run $name"
        job = viewModelScope.launch {
            busy.value = true
            AssistantAccessibilityService.instance?.showStop { stop() }
            try {
                val t0 = SystemClock.elapsedRealtime()
                val out = runSkill(name, args)
                db.logDao().insert(
                    LogEntry(
                        time = System.currentTimeMillis(), command = "run $name", route = "skill",
                        action = "skill $name", success = out.ok, ms = SystemClock.elapsedRealtime() - t0, detail = out.message
                    )
                )
                status.value = out.message
                                voice.speak(out.message)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                status.value = e.message ?: "Skill failed"
                            } finally {
                                busy.value = false
                                AssistantAccessibilityService.instance?.hideStop()
                            }
        }
    }

    private fun startTeaching(hint: String): Outcome {
        val svc = AssistantAccessibilityService.instance ?: return Outcome(false, "Turn on the accessibility service first.")
        if (apiKey.value.isBlank()) return Outcome(false, "Teach mode needs a Gemini API key to build the skill.")
        teachHint = hint
        svc.startRecording()
        teaching.value = true
        svc.showBubble("DONE", 0xE62E7D32.toInt()) { submit("done") }
        return Outcome(true, "Recording. Do the task in your apps, then tap the green DONE bubble.")
    }

    private suspend fun finishTeaching(): Outcome {
        if (!teaching.value) return Outcome(false, "I'm not recording. Say learn this first.")
        val svc = AssistantAccessibilityService.instance
        teaching.value = false
        val events = svc?.stopRecording().orEmpty()
        svc?.hideStop()
        if (events.isEmpty()) return Outcome(false, "Nothing was recorded.")
        val ai = aiOrNull() ?: return Outcome(false, "Teach mode needs a Gemini API key.")
        status.value = "Building skill…"
        val def = ai.buildSkillFromRecording(teachHint, events.joinToString("\n"))
        val name = def.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifEmpty { "skill_${System.currentTimeMillis() % 10000}" }
        val existing = db.skillDao().list().firstOrNull { it.name == name }
        db.skillDao().upsert(
            Skill(
                id = existing?.id ?: 0, name = name, description = def.description,
                params = def.params.joinToString(","), steps = stepsJson(def.steps)
            )
        )
        val say = if (def.params.isEmpty()) name else "$name ${def.params.joinToString(" ") { "<$it>" }}"
        return Outcome(true, "Saved skill: $say (${def.steps.size} steps)")
    }

    fun saveSkill(s: Skill) { viewModelScope.launch { db.skillDao().upsert(s) } }
    fun deleteSkill(s: Skill) { viewModelScope.launch { db.skillDao().delete(s) } }

    fun createChain(name: String, skillNames: List<String>) {
        val n = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (n.isEmpty() || skillNames.isEmpty()) return
        saveSkill(
            Skill(
                name = n, description = "Chain: ${skillNames.joinToString(" > ")}", params = "",
                steps = stepsJson(skillNames.map { AgentStep(action = "run_skill", target = it) })
            )
        )
    }

    private suspend fun runAgent(goal: String): Outcome {
        if (apiKey.value.isBlank()) return Outcome(false, "Screen tasks need a Gemini API key (Settings).")
        return AgentRunner(getApplication(), GeminiClient(apiKey.value, model.value)) { status.value = it }.run(goal)
    }

    fun saveSettings(key: String, modelName: String, language: String) {
        prefs.apiKey = key.trim()
        prefs.model = modelName.trim().ifEmpty { "gemini-2.5-flash" }
        prefs.lang = language.trim().ifEmpty { "en-IN" }
        apiKey.value = prefs.apiKey
        model.value = prefs.model
        lang.value = prefs.lang
    }

    fun saveAlias(a: Alias) { viewModelScope.launch { db.aliasDao().upsert(a) } }
    fun deleteAlias(a: Alias) { viewModelScope.launch { db.aliasDao().delete(a) } }
    fun saveNickname(n: Nickname) { viewModelScope.launch { db.nicknameDao().upsert(n) } }
    fun deleteNickname(n: Nickname) { viewModelScope.launch { db.nicknameDao().delete(n) } }
    fun clearLog() { viewModelScope.launch { db.logDao().clear() } }

    override fun onCleared() {
        Bus.removeStopper(stopper)
        voice.release()
        super.onCleared()
    }
}
