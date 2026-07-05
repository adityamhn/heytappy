package com.agentchat.agent

import android.os.SystemClock
import android.util.Log
import com.agentchat.agent.llm.AnthropicClient
import com.agentchat.agent.llm.AnthropicResult
import com.agentchat.agent.llm.ApiMessage
import com.agentchat.agent.llm.CacheControl
import com.agentchat.agent.llm.ContentBlock
import com.agentchat.agent.llm.ToolDef
import kotlinx.coroutines.delay

/**
 * The universal computer-use loop. Each step it observes the screen, asks Claude
 * for one action via tool use, executes it, waits for the UI to settle, and
 * feeds the new screen back — repeating until the model calls task_complete,
 * asks the user something, or the step/time budget is exhausted.
 *
 * State (the running conversation) lives on the instance so an `ask_user` pause
 * can be resumed with the user's chat reply via [resume].
 */
class AgentLoop(
    private val client: AnthropicClient,
    private val observer: ScreenObserver,
    private val service: AgentAccessibilityService,
    private val onProgress: suspend (String) -> Unit,
) {
    private val messages = mutableListOf<ApiMessage>()
    private val systemBlocks = listOf(ContentBlock.text(SYSTEM_PROMPT, cache = true))
    private val tools: List<ToolDef> = AgentTools.definitions.mapIndexed { i, t ->
        if (i == AgentTools.definitions.lastIndex) t.copy(cacheControl = CacheControl()) else t
    }

    private var executor: ActionExecutor? = null
    private var pendingAsk: List<ContentBlock>? = null
    private var lastObservationText: String? = null
    private var noChangeStreak = 0
    private var steps = 0

    val isAwaitingUser: Boolean get() = pendingAsk != null

    suspend fun start(
        task: String,
        mentionedPackage: String?,
        appCatalog: String? = null,
    ): LoopResult {
        messages.clear()
        steps = 0
        AgentLog.startRun(task)

        // Apps with splash screens (Flutter/React Native) expose an empty a11y
        // tree for a while after launch. Re-observe until content appears or the
        // readiness budget runs out, then fall back to a screenshot so the model
        // sees the real pixels instead of a blank element list.
        var observation = observer.observe()
        if (observation.isSparse) {
            val readyDeadline = SystemClock.uptimeMillis() + FIRST_SCREEN_WAIT_MS
            while (observation.isSparse && SystemClock.uptimeMillis() < readyDeadline) {
                AgentLog.log(
                    "WAIT_FIRST_SCREEN",
                    "sparse tree (interactive=${observation.interactiveCount}), re-observing",
                )
                delay(600)
                observation = observer.observe()
            }
        }
        val introShot = if (observation.isSparse) observer.screenshotBase64() else null

        executor = ActionExecutor(service.driver, observation)
        lastObservationText = observation.text
        AgentLog.log("OBSERVATION step=0", observation.text)
        if (introShot != null) AgentLog.log("SCREENSHOT step=0", "attached (${introShot.length / 1024} KB base64)")

        val intro = buildString {
            append("Task: ").append(task).append('\n')
            if (mentionedPackage != null) {
                append("The user @-mentioned the app package: ").append(mentionedPackage)
                append(" (already opened for you if installed).\n")
            }
            if (appCatalog != null) {
                append("\nNo specific app was mentioned. Pick the right app yourself ")
                append("and launch it with open_app. Installed apps (label — package):\n")
                append(appCatalog).append('\n')
            }
            append("\nCurrent screen:\n").append(observation.text)
            if (introShot != null) {
                append("\nThe element list is sparse, so a screenshot is attached.")
            }
        }
        messages.add(
            ApiMessage(
                "user",
                buildList {
                    add(ContentBlock.text(intro))
                    if (introShot != null) add(ContentBlock.image(introShot))
                },
            ),
        )
        return runLoop()
    }

    suspend fun resume(userReply: String): LoopResult {
        val asks = pendingAsk ?: return LoopResult.Failure("No pending question to resume.")
        pendingAsk = null
        AgentLog.log("RESUME", "user reply: $userReply")
        val observation = observer.observe()
        executor = ActionExecutor(service.driver, observation)
        lastObservationText = observation.text
        AgentLog.log("OBSERVATION resume", observation.text)

        val replyBlock = ContentBlock.text(
            "User replied: \"$userReply\".\n\nCurrent screen:\n" + observation.text,
        )
        messages.add(ApiMessage("user", toolResultsFor(asks, asks.first(), listOf(replyBlock))))
        return runLoop()
    }

    private suspend fun runLoop(): LoopResult {
        val deadline = SystemClock.uptimeMillis() + DEADLINE_MS

        while (true) {
            if (steps >= MAX_STEPS) {
                AgentLog.log("RUN_END", "failure: max steps ($MAX_STEPS) reached")
                return LoopResult.Failure(
                    "Stopped after $MAX_STEPS steps without finishing. Try a more specific instruction.",
                )
            }
            if (SystemClock.uptimeMillis() > deadline) {
                AgentLog.log("RUN_END", "failure: deadline exceeded")
                return LoopResult.Failure("Timed out before finishing the task.")
            }
            steps++

            AgentLog.log("REQUEST step=$steps", "messages=${messages.size}")
            val requestStart = SystemClock.uptimeMillis()
            val result = client.send(
                system = systemBlocks,
                tools = tools,
                messages = withCacheBreakpoint(messages),
                maxTokens = MAX_TOKENS,
            )
            val latencyMs = SystemClock.uptimeMillis() - requestStart
            val response = when (result) {
                is AnthropicResult.Error -> {
                    AgentLog.log("RUN_END", "API error: ${result.message}")
                    return LoopResult.Failure(result.message)
                }
                is AnthropicResult.Success -> result.response
            }
            response.usage?.let {
                AgentLog.log(
                    "RESPONSE step=$steps",
                    "latency=${latencyMs}ms in=${it.inputTokens} out=${it.outputTokens} " +
                        "cacheWrite=${it.cacheCreationInputTokens} cacheRead=${it.cacheReadInputTokens} " +
                        "stop=${response.stopReason}",
                )
            }

            messages.add(ApiMessage("assistant", response.content))

            response.content.firstOrNull { it.type == "text" }?.text
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    AgentLog.log("MODEL_TEXT step=$steps", it.trim())
                    onProgress(it.trim())
                }

            val toolUses = response.content.filter { it.type == "tool_use" }
            if (toolUses.isEmpty()) {
                val text = response.content.firstOrNull { it.type == "text" }?.text
                AgentLog.log("RUN_END", "success (no tool call): ${text?.trim() ?: "Done."}")
                return LoopResult.Success(text?.trim() ?: "Done.")
            }

            val primary = toolUses.first()
            AgentLog.log("TOOL_USE step=$steps", "${primary.name} ${primary.input ?: "{}"}")
            val outcome = executor!!.execute(primary.name ?: "", primary.input ?: emptyInput())

            when (outcome) {
                is ActionOutcome.Complete -> {
                    messages.add(
                        ApiMessage(
                            "user",
                            toolResultsFor(toolUses, primary, listOf(ContentBlock.text("Ended."))),
                        ),
                    )
                    AgentLog.log(
                        "RUN_END",
                        "task_complete success=${outcome.success}: ${outcome.summary}",
                    )
                    return if (outcome.success) {
                        LoopResult.Success(outcome.summary)
                    } else {
                        LoopResult.Failure(outcome.summary)
                    }
                }

                is ActionOutcome.AskUser -> {
                    pendingAsk = toolUses
                    AgentLog.log("ASK_USER step=$steps", outcome.question)
                    return LoopResult.NeedsInput(outcome.question)
                }

                is ActionOutcome.Screenshot -> {
                    waitForIdle(quietMs = 250)
                    val observation = observer.observe()
                    val shot = observer.screenshotBase64()
                    AgentLog.log("OBSERVATION step=$steps", observation.text)
                    AgentLog.log(
                        "SCREENSHOT step=$steps",
                        if (shot != null) "attached (${shot.length / 1024} KB base64)" else "CAPTURE FAILED",
                    )
                    val blocks = buildList {
                        if (shot != null) {
                            add(ContentBlock.text("Screenshot attached.\n\nCurrent screen:\n" + observation.text))
                            add(ContentBlock.image(shot))
                        } else {
                            add(
                                ContentBlock.text(
                                    "Screenshot capture FAILED (the system limits capture rate — " +
                                        "wait a second and retry if you still need it).\n\n" +
                                        "Current screen:\n" + observation.text,
                                ),
                            )
                        }
                    }
                    finishStep(toolUses, primary, blocks, observation)
                }

                is ActionOutcome.Acted -> {
                    waitForIdle()
                    val observation = observer.observe()
                    val changed = observation.text != lastObservationText
                    noChangeStreak = if (changed) 0 else noChangeStreak + 1
                    val escalate = observation.isSparse || noChangeStreak >= 1

                    AgentLog.log("TOOL_RESULT step=$steps", outcome.result)
                    AgentLog.log(
                        "OBSERVATION step=$steps",
                        "changed=$changed sparse=${observation.isSparse} escalate=$escalate\n" +
                            observation.text,
                    )
                    val blocks = buildList {
                        add(ContentBlock.text(outcome.result + "\n\nCurrent screen:\n" + observation.text))
                        if (escalate) {
                            val shot = observer.screenshotBase64()
                            AgentLog.log(
                                "SCREENSHOT step=$steps",
                                if (shot != null) "escalation attached (${shot.length / 1024} KB base64)" else "escalation CAPTURE FAILED",
                            )
                            if (shot != null) add(ContentBlock.image(shot))
                        }
                    }
                    finishStep(toolUses, primary, blocks, observation)
                }
            }
            pruneHistory()
        }
    }

    private fun finishStep(
        toolUses: List<ContentBlock>,
        primary: ContentBlock,
        blocks: List<ContentBlock>,
        observation: ScreenObserver.Observation,
    ) {
        messages.add(ApiMessage("user", toolResultsFor(toolUses, primary, blocks)))
        executor = ActionExecutor(service.driver, observation)
        lastObservationText = observation.text
    }

    /** Builds one tool_result per tool_use; only [primary] carries real content. */
    private fun toolResultsFor(
        toolUses: List<ContentBlock>,
        primary: ContentBlock,
        primaryBlocks: List<ContentBlock>,
    ): List<ContentBlock> = toolUses.map { use ->
        val id = use.id ?: ""
        if (use === primary) {
            ContentBlock.toolResult(id, primaryBlocks)
        } else {
            ContentBlock.toolResult(
                id,
                listOf(ContentBlock.text("Ignored — only one action per step.")),
            )
        }
    }

    /** Waits until the UI stops emitting change events, or a cap elapses. */
    private suspend fun waitForIdle(quietMs: Long = 400, capMs: Long = 3_500) {
        delay(120)
        val deadline = SystemClock.uptimeMillis() + capMs
        while (SystemClock.uptimeMillis() < deadline) {
            val sinceLastEvent = SystemClock.uptimeMillis() - service.lastEventUptime
            if (sinceLastEvent >= quietMs) return
            delay(80)
        }
    }

    /**
     * Elides old screen observations to keep tokens bounded: only the most recent
     * tool_result keeps its screenshot, and observations older than a few turns
     * are replaced with a short placeholder.
     */
    private fun pruneHistory() {
        var toolResultTurns = 0
        for (i in messages.indices.reversed()) {
            val message = messages[i]
            if (message.content.none { it.type == "tool_result" }) continue
            toolResultTurns++
            when {
                toolResultTurns <= KEEP_IMAGE_TURNS -> Unit
                toolResultTurns <= KEEP_TEXT_TURNS ->
                    messages[i] = message.copy(content = message.content.map(::dropImages))
                else ->
                    messages[i] = message.copy(content = message.content.map(::elide))
            }
        }
    }

    private fun dropImages(block: ContentBlock): ContentBlock {
        if (block.type != "tool_result") return block
        val filtered = block.content?.filter { it.type != "image" }
        return block.copy(content = filtered)
    }

    private fun elide(block: ContentBlock): ContentBlock {
        if (block.type != "tool_result") return block
        return block.copy(content = listOf(ContentBlock.text("[screen elided]")))
    }

    /** Marks the newest message's final block as a cache breakpoint. */
    private fun withCacheBreakpoint(source: List<ApiMessage>): List<ApiMessage> {
        if (source.isEmpty()) return source
        val out = source.toMutableList()
        val lastIndex = out.lastIndex
        val last = out[lastIndex]
        if (last.content.isEmpty()) return out
        val content = last.content.toMutableList()
        val tailIndex = content.lastIndex
        content[tailIndex] = content[tailIndex].copy(cacheControl = CacheControl())
        out[lastIndex] = last.copy(content = content)
        return out
    }

    private fun emptyInput() = kotlinx.serialization.json.JsonObject(emptyMap())

    companion object {
        private const val MAX_STEPS = 25
        private const val DEADLINE_MS = 180_000L
        private const val MAX_TOKENS = 1024
        private const val FIRST_SCREEN_WAIT_MS = 6_000L
        private const val KEEP_IMAGE_TURNS = 1
        private const val KEEP_TEXT_TURNS = 3

        private val SYSTEM_PROMPT = """
            You are Tappy, an autonomous agent that operates the user's Android phone to complete tasks. You look at the screen and choose ONE action at a time using the provided tools.

            Each step you receive a text list of on-screen elements, each with an index like [5], a type, a label, flags (C=clickable, E=editable, S=scrollable, K=checkbox), and its center coordinates @(x,y). Coordinates and any screenshots share the same pixel grid (the "Screen: WxH" size in the observation). After every action you get the updated screen.

            How to act:
            - Output exactly ONE tool call per step. Before it, write a very short (<=12 word) sentence describing what you're doing; it is shown to the user as live progress.
            - Prefer interacting by element index (tap, type, scroll). Use tap_coordinates or swipe for targets that are visible in a screenshot but missing from the element list (custom-drawn screens, maps, canvases).
            - To enter text, use `type` with the editable element's index; set press_enter=true to submit a search or form.
            - If a screen looks empty, unchanged, or has no useful elements, call take_screenshot to see it visually.
            - If an action does not change the screen after 2 tries, do NOT repeat it. Change strategy: pick a different element, different coordinates, scroll, or use a deep link.
            - Use `wait` when content is still loading. Use `open_link` to jump straight to a screen when you know a deep link.

            Safety (mandatory):
            - You MUST call ask_user to get explicit confirmation BEFORE any irreversible or money/commitment action: making a payment, booking or ordering (rides, food, tickets), confirming a purchase, sending a message/email/post, or deleting data. State clearly what will happen and any cost, then only proceed after the user agrees.
            - Ask the user (ask_user) whenever you need information only they have (which option, an address, a choice) or a login is required. Never enter or guess passwords.

            Finishing:
            - Call task_complete when done or if the task is impossible, with success=true/false and a concise summary that includes concrete details (song playing, fare shown, item added, confirmation number).

            Useful deep links (open_link):
            - Spotify search: spotify:search:<query>
            - Google Maps navigation: google.navigation:q=<destination>
            - Web search: https://www.google.com/search?q=<query>
            - Dial a number: tel:<number>

            Be efficient — fewer steps is better. Never fabricate a result you did not actually see on screen.
        """.trimIndent()
    }
}

sealed interface LoopResult {
    data class Success(val summary: String) : LoopResult
    data class Failure(val message: String) : LoopResult
    data class NeedsInput(val question: String) : LoopResult
}
