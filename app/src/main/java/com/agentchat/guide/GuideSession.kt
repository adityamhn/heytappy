package com.agentchat.guide

import android.os.SystemClock
import com.agentchat.agent.AgentAccessibilityService
import com.agentchat.agent.AgentLog
import com.agentchat.agent.CoordinateScaler
import com.agentchat.agent.ScreenObserver
import com.agentchat.agent.llm.AnthropicClient
import com.agentchat.agent.llm.AnthropicResult
import com.agentchat.agent.llm.ApiMessage
import com.agentchat.agent.llm.CacheControl
import com.agentchat.agent.llm.ContentBlock
import com.agentchat.agent.llm.ToolDef
import com.agentchat.voice.DeepgramTts
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** How a guide turn ended, as seen by the voice session controller. */
sealed interface GuideOutcome {
    /** The guide answered (and already spoke) — [text] is what was said. */
    data class Spoken(val text: String) : GuideOutcome

    /** The user wants it done, not taught: run [task] through the agent. */
    data class Delegate(val task: String) : GuideOutcome
}

/**
 * The HeyClicky-style tutor: a multi-turn conversation with Claude that sees
 * the live screen (element list + screenshot) and answers out loud while
 * pointing at things through the [GuideOverlayController]. Replies carry inline
 * annotation tags ([POINT:x,y:label] etc.) that are rendered segment-by-segment
 * in sync with TTS. An [AWAIT_ACTION] tag turns the reply into a walkthrough
 * step: the session waits for the user to act, re-observes, and continues.
 *
 * When the user asks for execution instead of teaching, the model calls the
 * delegate_to_agent tool and the caller routes the task to the agent loop.
 */
class GuideSession(
    private val client: AnthropicClient,
    private val service: AgentAccessibilityService,
    private val tts: DeepgramTts,
    private val onSpeaking: (String) -> Unit,
) {
    private val observer = ScreenObserver(service)
    private val messages = mutableListOf<ApiMessage>()
    private val systemBlocks = listOf(ContentBlock.text(SYSTEM_PROMPT, cache = true))
    private val tools = listOf(
        ToolDef(
            name = "delegate_to_agent",
            description = "Hand the task to the phone-operating agent to DO it for the user " +
                "(instead of teaching). Use when the user asks you to do/perform/complete " +
                "something for them, e.g. \"just do it for me\".",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("task") {
                        put("type", "string")
                        put("description", "The full task for the agent, self-contained.")
                    }
                }
            },
            cacheControl = CacheControl(),
        ),
    )

    /** Agent result to relay into the next model turn, set after a delegation. */
    private var pendingAgentNote: String? = null

    /** Handles one spoken utterance; speaks the reply and draws annotations. */
    suspend fun handle(utterance: String): GuideOutcome {
        service.guideOverlay.clear()

        val observation = observer.observe()
        val shot = observer.screenshotBase64()
        AgentLog.log("GUIDE_TURN", "utterance=\"$utterance\" shot=${shot != null}")

        val intro = buildString {
            pendingAgentNote?.let {
                append("(The agent you delegated to earlier reported: ").append(it).append(")\n\n")
            }
            append("User said: \"").append(utterance).append("\"\n\n")
            append("Current screen:\n").append(observation.text)
        }
        pendingAgentNote = null
        messages.add(
            ApiMessage(
                "user",
                buildList {
                    add(ContentBlock.text(intro))
                    if (shot != null) add(ContentBlock.image(shot))
                },
            ),
        )
        return runTurn(observation)
    }

    /** Called by the controller after a delegated agent run finishes. */
    fun recordAgentResult(result: String) {
        pendingAgentNote = result.take(500)
    }

    /** Wipes any lingering annotations (called when a new listen turn starts). */
    fun clearOverlay() {
        service.guideOverlay.clear()
    }

    private suspend fun runTurn(startObservation: ScreenObserver.Observation): GuideOutcome {
        var observation = startObservation
        var continuations = 0

        while (true) {
            val result = client.send(
                system = systemBlocks,
                tools = tools,
                messages = messages,
                maxTokens = MAX_TOKENS,
            )
            val response = when (result) {
                is AnthropicResult.Error -> {
                    AgentLog.log("GUIDE_ERROR", result.message)
                    messages.removeAt(messages.lastIndex) // keep history consistent
                    val text = result.message
                    onSpeaking(text)
                    runCatching { tts.speak(text) }
                    return GuideOutcome.Spoken(text)
                }
                is AnthropicResult.Success -> result.response
            }
            messages.add(ApiMessage("assistant", response.content))

            // Delegation tool call ends the guide turn; the controller runs the agent.
            val delegation = response.content.firstOrNull {
                it.type == "tool_use" && it.name == "delegate_to_agent"
            }
            if (delegation != null) {
                val task = delegation.input?.get("task")?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
                messages.add(
                    ApiMessage(
                        "user",
                        listOf(
                            ContentBlock.toolResult(
                                delegation.id ?: "",
                                listOf(ContentBlock.text("Handed off to the agent; its result will be reported back.")),
                            ),
                        ),
                    ),
                )
                if (task == null) {
                    val text = "I wanted to hand that to the agent but couldn't tell what to do — could you rephrase?"
                    onSpeaking(text)
                    runCatching { tts.speak(text) }
                    return GuideOutcome.Spoken(text)
                }
                AgentLog.log("GUIDE_DELEGATE", task)
                return GuideOutcome.Delegate(task)
            }

            val reply = response.content.firstOrNull { it.type == "text" }?.text.orEmpty().trim()
            if (reply.isEmpty()) {
                val text = "Sorry, I lost my train of thought — ask me again?"
                onSpeaking(text)
                runCatching { tts.speak(text) }
                return GuideOutcome.Spoken(text)
            }

            val parsed = AnnotationParser.parse(reply)
            AgentLog.log(
                "GUIDE_REPLY",
                "segments=${parsed.segments.size} await=${parsed.segments.any { it.awaitAction }}\n$reply",
            )
            val scaler = CoordinateScaler.of(observation)
            onSpeaking(parsed.spokenText)

            var awaited = false
            for (segment in parsed.segments) {
                segment.annotation?.let { service.guideOverlay.showAnnotation(it, scaler) }
                if (segment.text.isNotEmpty()) {
                    runCatching { tts.speak(segment.text) }
                        .onFailure { AgentLog.log("GUIDE_TTS", "speak error: ${it.message}") }
                }
                if (segment.awaitAction) {
                    awaited = true
                    break // anything after AWAIT_ACTION belongs to the next step
                }
            }

            if (!awaited || continuations >= MAX_CONTINUATIONS) {
                pruneHistory()
                scheduleAutoClear()
                return GuideOutcome.Spoken(parsed.spokenText)
            }

            // Walkthrough step: wait for the user to actually do it.
            continuations++
            val acted = awaitUserAction()
            service.guideOverlay.clear()
            if (!acted) {
                pruneHistory()
                return GuideOutcome.Spoken(parsed.spokenText)
            }

            delay(SETTLE_AFTER_ACTION_MS)
            observation = observer.observe()
            val shot = observer.screenshotBase64()
            AgentLog.log("GUIDE_CONTINUE", "step=$continuations user acted, re-observed")
            messages.add(
                ApiMessage(
                    "user",
                    buildList {
                        add(
                            ContentBlock.text(
                                "The user performed the step. Continue the walkthrough " +
                                    "(or wrap up if done).\n\nCurrent screen:\n" + observation.text,
                            ),
                        )
                        if (shot != null) add(ContentBlock.image(shot))
                    },
                ),
            )
            pruneHistory()
        }
    }

    /**
     * Watches [AgentAccessibilityService.lastEventUptime] for a burst of UI
     * change events (the user tapping/scrolling), then for the screen to settle.
     * Returns false if the user never acts within the window.
     */
    private suspend fun awaitUserAction(): Boolean {
        val start = SystemClock.uptimeMillis()
        val baseline = service.lastEventUptime
        val deadline = start + AWAIT_ACTION_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (service.lastEventUptime > baseline &&
                service.lastEventUptime > start
            ) {
                // Something changed — now wait for it to go quiet.
                val settleDeadline = SystemClock.uptimeMillis() + SETTLE_CAP_MS
                while (SystemClock.uptimeMillis() < settleDeadline) {
                    if (SystemClock.uptimeMillis() - service.lastEventUptime >= QUIET_MS) return true
                    delay(100)
                }
                return true
            }
            delay(150)
        }
        AgentLog.log("GUIDE_AWAIT", "user did not act within ${AWAIT_ACTION_TIMEOUT_MS / 1000}s")
        return false
    }

    /** Clears leftover annotations a while after the last spoken segment. */
    private fun scheduleAutoClear() {
        val overlay = service.guideOverlay
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { overlay.clear() },
            AUTO_CLEAR_MS,
        )
    }

    /** Keeps token usage bounded: only the newest [KEEP_IMAGE_TURNS] user turns keep images. */
    private fun pruneHistory() {
        var imageTurns = 0
        for (i in messages.indices.reversed()) {
            val message = messages[i]
            if (message.role != "user" || message.content.none { it.type == "image" }) continue
            imageTurns++
            if (imageTurns > KEEP_IMAGE_TURNS) {
                messages[i] = message.copy(content = message.content.filter { it.type != "image" })
            }
        }
        while (messages.size > MAX_HISTORY_MESSAGES) {
            messages.removeAt(0)
            // Never start history on an assistant/tool_result message.
            while (messages.isNotEmpty() && messages.first().role != "user") {
                messages.removeAt(0)
            }
        }
    }

    companion object {
        private const val MAX_TOKENS = 1024
        private const val MAX_CONTINUATIONS = 8
        private const val AWAIT_ACTION_TIMEOUT_MS = 45_000L
        private const val SETTLE_AFTER_ACTION_MS = 700L
        private const val QUIET_MS = 600L
        private const val SETTLE_CAP_MS = 4_000L
        private const val AUTO_CLEAR_MS = 15_000L
        private const val KEEP_IMAGE_TURNS = 2
        private const val MAX_HISTORY_MESSAGES = 24

        private val SYSTEM_PROMPT = """
            You are Tappy, a friendly voice tutor living on the user's Android phone. The user talks to you out loud and your reply is spoken back with text-to-speech, so write like you actually talk: short, warm, conversational sentences. No markdown, no bullet lists, no headings, no emojis.

            You can SEE the user's screen: each turn you get a screenshot plus a list of UI elements with labels and center coordinates like [5] Button "Search" C @(x,y). Coordinates are in the screenshot's pixel grid (the "Screen: WxH" size in the observation).

            You can POINT at the screen while you talk by embedding tags inline in your reply. Place a tag right before the sentence that talks about that spot — the annotation appears as the words are spoken:
            - [POINT:x,y:label] — fly the cursor to that spot (your main tool; label is 1-3 words like "search bar")
            - [CIRCLE:x,y,r:label] — neon circle highlight of radius r
            - [BOX:left,top,right,bottom:label] — rectangle highlight around a region
            - [ARROW:x1,y1,x2,y2:label] — arrow from one place to another (e.g. showing a swipe)
            - [PATH:x1,y1 x2,y2 x3,y3:label] — a curve through points
            - [POLYGON:x1,y1 x2,y2 x3,y3:label] — a closed shape around an irregular area
            Prefer the coordinates from the element list when the target is listed — they are exact. Use the screenshot for custom-drawn UI. Use at most 3 annotations per reply so the screen stays readable.

            WALKTHROUGHS: when teaching a multi-step flow, explain ONE step, point at it, then end your reply with [AWAIT_ACTION]. The system waits for the user to do the step, then sends you the new screen and you continue with the next step. Keep each step to a sentence or two. When the flow is done, wrap up warmly without [AWAIT_ACTION].

            DOING vs TEACHING: you only teach and explain. If the user wants something DONE for them ("do it for me", "book it", "order it", "play it"), call the delegate_to_agent tool with a complete, self-contained task description instead of explaining.

            If the screen is unrelated to the question, still answer, and mention which app to open. Never invent UI you cannot see. Keep replies under 80 words unless the user asks for depth.
        """.trimIndent()
    }
}
