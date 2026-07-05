package com.agentchat.agent

import android.content.Context
import android.content.Intent
import com.agentchat.agent.llm.AnthropicClient
import com.agentchat.apps.InstalledApp
import com.agentchat.data.ChatRepository
import com.agentchat.data.MessageStatus
import com.agentchat.settings.AgentSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Bridges chat/voice input to the universal agent loop. With an @-mention it
 * launches that app first; without one (voice commands) the agent starts from
 * the home screen with a catalog of installed apps and picks the right one via
 * open_app. Runs stream progress to the working bubble and overlay; when the
 * loop needs the user, it parks and the next message/utterance resumes it.
 * Every entry point returns the final reply text so voice mode can speak it.
 */
class AgentOrchestrator(
    private val context: Context,
    private val repository: ChatRepository,
    private val settings: AgentSettings,
    private val installedAppsProvider: () -> List<InstalledApp>,
) {
    private val mentionRegex = Regex("""@([\w.]+)""")

    private var pendingLoop: AgentLoop? = null

    @Volatile
    private var currentWorkingId: Long = -1L

    /**
     * System-wide voice sessions set this false so finishing a run doesn't yank
     * the user out of the app they're standing in; the chat UI path leaves it true.
     */
    @Volatile
    var bringChatForwardOnFinish: Boolean = true

    /** True when a parked loop is waiting on an ask_user reply. */
    val isAwaitingUser: Boolean get() = pendingLoop?.isAwaitingUser == true

    /** Returns true if the message was an agent command (handled here). */
    suspend fun handle(message: String): Boolean {
        pendingLoop?.let { loop ->
            if (loop.isAwaitingUser) {
                resumeRun(loop, message)
                return true
            }
        }

        val token = mentionRegex.find(message)?.groupValues?.getOrNull(1)
            ?: return false

        val app = resolveApp(token)
        if (app == null) {
            repository.addAgentMessage(
                "I couldn't find an installed app matching \"@$token\". " +
                    "Type @ to pick from your installed apps.",
                status = MessageStatus.ERROR,
            )
            return true
        }

        val task = message.replaceFirst(mentionRegex, "").trim()
            .ifEmpty { "Open ${app.label} and help me." }
        startRun(task, app)
        return true
    }

    /**
     * Voice path: no @mention required. Resumes a parked loop if one is waiting,
     * otherwise runs the task, letting the agent choose the app. Returns the
     * reply to speak aloud (summary, question, or error).
     */
    suspend fun handleVoice(task: String): String {
        pendingLoop?.let { loop ->
            if (loop.isAwaitingUser) {
                return resumeRun(loop, task)
            }
        }

        // A spoken "@spotify …"-style command still gets the fast path.
        val token = mentionRegex.find(task)?.groupValues?.getOrNull(1)
        val app = token?.let { resolveApp(it) }
        val cleanTask = if (app != null) {
            task.replaceFirst(mentionRegex, "").trim().ifEmpty { "Open ${app.label} and help me." }
        } else {
            task
        }
        return startRun(cleanTask, app)
    }

    private suspend fun startRun(task: String, app: InstalledApp?): String {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            val msg = "The agent service is off. Open setup (top-right) and enable \"Tappy\"."
            repository.addAgentMessage(msg, status = MessageStatus.ERROR)
            return msg
        }
        val apiKey = settings.apiKey
        if (apiKey == null) {
            val msg = "Add your Anthropic API key first — open setup (top-right) and paste it."
            repository.addAgentMessage(msg, status = MessageStatus.ERROR)
            return msg
        }

        val label = app?.label ?: "your phone"
        val workingId = repository.addAgentMessage(
            if (app != null) "Opening ${app.label}…" else "Working on it…",
            status = MessageStatus.WORKING,
        )
        currentWorkingId = workingId
        service.overlay.show("Agent working — $label")

        AgentLog.init(context)
        if (app != null) {
            val launched = service.driver.launchApp(app.packageName)
            // Wait for the app to actually own the foreground window instead of a
            // fixed sleep — cold starts (Swiggy, Flutter apps) can take seconds and
            // observing too early yields an empty tree / blank splash.
            val foregrounded = service.driver.awaitPackage(app.packageName, APP_LAUNCH_TIMEOUT_MS)
            AgentLog.log(
                "LAUNCH",
                "package=${app.packageName} launched=$launched foregrounded=$foregrounded",
            )
            delay(APP_LAUNCH_SETTLE_MS)
        } else {
            // Clean start: the agent picks and opens an app itself via open_app.
            service.driver.pressHome()
            AgentLog.log("LAUNCH", "no mention — starting from home screen")
            delay(APP_LAUNCH_SETTLE_MS)
        }

        val loop = AgentLoop(
            client = AnthropicClient(apiKey, settings.model),
            observer = ScreenObserver(service),
            service = service,
            onProgress = ::reportProgress,
        )
        pendingLoop = loop

        return runCatchingCancellation(label) {
            val result = loop.start(
                task = task,
                mentionedPackage = app?.packageName,
                appCatalog = if (app == null) appCatalog() else null,
            )
            finish(result)
        }
    }

    private suspend fun resumeRun(loop: AgentLoop, reply: String): String {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            pendingLoop = null
            val msg = "The agent service turned off, so I can't continue. Nothing was completed."
            repository.addAgentMessage(msg, status = MessageStatus.ERROR)
            return msg
        }

        val workingId = repository.addAgentMessage("Continuing…", status = MessageStatus.WORKING)
        currentWorkingId = workingId
        service.overlay.show("Agent working")

        return runCatchingCancellation("the app") {
            val result = loop.resume(reply)
            finish(result)
        }
    }

    private suspend fun finish(result: LoopResult): String {
        val service = AgentAccessibilityService.instance
        return when (result) {
            is LoopResult.Success -> {
                updateMessage(currentWorkingId, result.summary, MessageStatus.DONE)
                pendingLoop = null
                service?.overlay?.hide()
                bringChatToForeground()
                result.summary
            }
            is LoopResult.Failure -> {
                updateMessage(currentWorkingId, result.message, MessageStatus.ERROR)
                pendingLoop = null
                service?.overlay?.hide()
                bringChatToForeground()
                result.message
            }
            is LoopResult.NeedsInput -> {
                updateMessage(
                    currentWorkingId,
                    result.question,
                    MessageStatus.AWAITING_CONFIRMATION,
                )
                // Keep pendingLoop so the next chat message resumes it.
                service?.overlay?.hide()
                bringChatToForeground()
                result.question
            }
        }
    }

    private suspend fun runCatchingCancellation(
        appLabel: String,
        block: suspend () -> String,
    ): String {
        try {
            return block()
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                updateMessage(currentWorkingId, "Stopped.", MessageStatus.DONE)
                pendingLoop = null
                AgentAccessibilityService.instance?.overlay?.hide()
                bringChatToForeground()
            }
            throw c
        } catch (e: Exception) {
            val msg = "Something went wrong driving $appLabel: ${e.message ?: e.javaClass.simpleName}"
            updateMessage(currentWorkingId, msg, MessageStatus.ERROR)
            pendingLoop = null
            AgentAccessibilityService.instance?.overlay?.hide()
            bringChatToForeground()
            return msg
        }
    }

    private suspend fun reportProgress(note: String) {
        updateMessage(currentWorkingId, note, MessageStatus.WORKING)
        AgentAccessibilityService.instance?.overlay?.update("Agent — " + note.take(40))
    }

    private fun resolveApp(token: String): InstalledApp? {
        val apps = installedAppsProvider()
        val lower = token.lowercase()
        return apps.firstOrNull { it.packageName.equals(token, ignoreCase = true) }
            ?: apps.firstOrNull { it.mentionToken.equals(lower, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.replace(" ", "").equals(lower, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.contains(token, ignoreCase = true) }
    }

    /** Compact "label — package" list so the model can pick apps with open_app. */
    private fun appCatalog(): String =
        installedAppsProvider().joinToString("\n") { "${it.label} — ${it.packageName}" }

    private suspend fun updateMessage(id: Long, text: String, status: MessageStatus) {
        if (id < 0) return
        val existing = repository.getMessage(id) ?: return
        repository.updateMessage(existing.copy(text = text, status = status))
    }

    private fun bringChatToForeground() {
        if (!bringChatForwardOnFinish) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            } ?: return
        runCatching { context.startActivity(intent) }
    }

    companion object {
        private const val APP_LAUNCH_TIMEOUT_MS = 10_000L
        private const val APP_LAUNCH_SETTLE_MS = 600L
    }
}
