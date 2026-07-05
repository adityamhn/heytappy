package com.agentchat.agent

import android.util.Log
import com.agentchat.agent.llm.ToolDef
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The action space exposed to Claude, plus the executor that carries out a chosen
 * [com.agentchat.agent.llm.ContentBlock] tool_use against the live screen. Every
 * action targets a semantic element index (from [ScreenObserver]) or raw
 * coordinates — never a scraped selector — so it generalizes to any app.
 */
object AgentTools {

    val definitions: List<ToolDef> = buildList {
        add(
            tool(
                "tap",
                "Tap/click the on-screen element with the given index from the elements list.",
                buildJsonObject {
                    putJsonObject("index") {
                        put("type", "integer")
                        put("description", "Index of the element to tap, e.g. 5 for [5].")
                    }
                },
                required = listOf("index"),
            ),
        )
        add(
            tool(
                "tap_coordinates",
                "Tap at raw screen pixel coordinates. Use only when the target is not in the elements list (custom-drawn screens, maps, games).",
                buildJsonObject {
                    putJsonObject("x") { put("type", "integer") }
                    putJsonObject("y") { put("type", "integer") }
                },
                required = listOf("x", "y"),
            ),
        )
        add(
            tool(
                "type",
                "Focus the editable element at index and set its text. Optionally submit with the keyboard action.",
                buildJsonObject {
                    putJsonObject("index") { put("type", "integer") }
                    putJsonObject("text") { put("type", "string") }
                    putJsonObject("press_enter") {
                        put("type", "boolean")
                        put("description", "Submit after typing (search/go/send). Default false.")
                    }
                },
                required = listOf("index", "text"),
            ),
        )
        add(
            tool(
                "scroll",
                "Scroll the screen to reveal more content.",
                buildJsonObject {
                    putJsonObject("direction") {
                        put("type", "string")
                        putJsonArray("enum", listOf("up", "down", "left", "right"))
                    }
                    putJsonObject("index") {
                        put("type", "integer")
                        put("description", "Optional element index to center the scroll on.")
                    }
                },
                required = listOf("direction"),
            ),
        )
        add(
            tool(
                "swipe",
                "Free-form swipe between two pixel points (drag, dismiss, unlock, sliders).",
                buildJsonObject {
                    putJsonObject("x1") { put("type", "integer") }
                    putJsonObject("y1") { put("type", "integer") }
                    putJsonObject("x2") { put("type", "integer") }
                    putJsonObject("y2") { put("type", "integer") }
                },
                required = listOf("x1", "y1", "x2", "y2"),
            ),
        )
        add(tool("back", "Press the system Back button.", buildJsonObject {}))
        add(tool("home", "Go to the device home screen.", buildJsonObject {}))
        add(
            tool(
                "open_app",
                "Launch an installed app by its package name (e.g. com.spotify.music).",
                buildJsonObject { putJsonObject("package") { put("type", "string") } },
                required = listOf("package"),
            ),
        )
        add(
            tool(
                "open_link",
                "Open a URL or deep link (e.g. https://…, spotify:search:jazz, geo:0,0?q=…).",
                buildJsonObject { putJsonObject("uri") { put("type", "string") } },
                required = listOf("uri"),
            ),
        )
        add(
            tool(
                "wait",
                "Wait for the screen to load/animate before observing again.",
                buildJsonObject {
                    putJsonObject("seconds") {
                        put("type", "number")
                        put("description", "How long to wait, max 5.")
                    }
                },
            ),
        )
        add(
            tool(
                "take_screenshot",
                "Attach a screenshot of the current screen on the next observation. Use when the element list is insufficient.",
                buildJsonObject {},
            ),
        )
        add(
            tool(
                "ask_user",
                "Pause and ask the user a question in chat. Required before any payment, booking, purchase, or sending a message on their behalf. The loop resumes with their reply.",
                buildJsonObject { putJsonObject("question") { put("type", "string") } },
                required = listOf("question"),
            ),
        )
        add(
            tool(
                "task_complete",
                "End the task. Set success and give a short summary to post in chat.",
                buildJsonObject {
                    putJsonObject("success") { put("type", "boolean") }
                    putJsonObject("summary") { put("type", "string") }
                },
                required = listOf("success", "summary"),
            ),
        )
    }

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String> = emptyList(),
    ): ToolDef =
        ToolDef(
            name = name,
            description = description,
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", properties)
                putJsonArray("required", required)
            },
        )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
    key: String,
    values: List<String>,
) {
    put(key, buildJsonArray { values.forEach { add(it) } })
}

/** Outcome of executing one tool call, interpreted by [AgentLoop]. */
sealed interface ActionOutcome {
    /** A normal action ran; [result] is fed back and the loop re-observes. */
    data class Acted(val result: String) : ActionOutcome

    /** Attach a screenshot on the next observation. */
    data object Screenshot : ActionOutcome

    /** Pause the loop and surface [question] to the user. */
    data class AskUser(val question: String) : ActionOutcome

    /** Terminal: task finished. */
    data class Complete(val success: Boolean, val summary: String) : ActionOutcome
}

/**
 * Runs a single tool call against the live screen using [UiDriver] and the node
 * snapshot from the most recent [ScreenObserver.Observation].
 */
class ActionExecutor(
    private val driver: UiDriver,
    private val observation: ScreenObserver.Observation,
) {
    private val scaler = CoordinateScaler.of(observation)

    suspend fun execute(name: String, input: JsonObject): ActionOutcome {
        Log.d(TAG, "tool=$name input=$input")
        return when (name) {
            "tap" -> tap(input.int("index"))
            "tap_coordinates" -> tapCoordinates(input.int("x"), input.int("y"))
            "type" -> type(input.int("index"), input.str("text"), input.bool("press_enter"))
            "scroll" -> scroll(input.str("direction"), input.intOrNull("index"))
            "swipe" -> swipe(
                input.int("x1"), input.int("y1"), input.int("x2"), input.int("y2"),
            )
            "back" -> {
                driver.pressBack()
                ActionOutcome.Acted("Pressed back.")
            }
            "home" -> {
                driver.pressHome()
                ActionOutcome.Acted("Went to home screen.")
            }
            "open_app" -> openApp(input.str("package"))
            "open_link" -> openLink(input.str("uri"))
            "wait" -> {
                val seconds = (input.doubleOrNull("seconds") ?: 1.0).coerceIn(0.0, 5.0)
                delay((seconds * 1000).toLong())
                ActionOutcome.Acted("Waited ${seconds}s.")
            }
            "take_screenshot" -> ActionOutcome.Screenshot
            "ask_user" -> ActionOutcome.AskUser(
                input.str("question") ?: "Could you clarify what you'd like me to do?",
            )
            "task_complete" -> ActionOutcome.Complete(
                input.boolOrNull("success") ?: true,
                input.str("summary") ?: "Done.",
            )
            else -> ActionOutcome.Acted("Unknown tool \"$name\" — ignored.")
        }
    }

    private suspend fun tap(index: Int?): ActionOutcome {
        val element = index?.let { observation.elements[it] }
            ?: return ActionOutcome.Acted("No element with index $index. Re-check the elements list.")
        element.node.refresh()
        if (driver.click(element.node)) {
            return ActionOutcome.Acted("Tapped element [$index].")
        }
        val ok = driver.tapAt(element.bounds.centerX(), element.bounds.centerY())
        return ActionOutcome.Acted(
            if (ok) "Tapped [$index] by coordinates." else "Tap on [$index] failed.",
        )
    }

    private suspend fun tapCoordinates(x: Int?, y: Int?): ActionOutcome {
        if (x == null || y == null) return ActionOutcome.Acted("tap_coordinates needs x and y.")
        val sx = scaleX(x)
        val sy = scaleY(y)
        // Node click first: gesture injection is unreliable on some devices
        // (silently swallowed), while ACTION_CLICK goes through the app itself.
        val node = driver.clickableNodeAt(sx, sy)
        if (node != null && driver.click(node)) {
            AgentLog.log("DISPATCH", "tap model=($x,$y) device=($sx,$sy) via node click")
            return ActionOutcome.Acted("Tapped ($x, $y).")
        }
        AgentLog.log("DISPATCH", "tap model=($x,$y) device=($sx,$sy) via gesture (no clickable node)")
        val ok = driver.tapAt(sx, sy)
        return ActionOutcome.Acted(if (ok) "Tapped ($x, $y)." else "Tap at ($x, $y) failed.")
    }

    private fun type(index: Int?, text: String?, pressEnter: Boolean?): ActionOutcome {
        if (text == null) return ActionOutcome.Acted("type needs text.")
        val element = index?.let { observation.elements[it] }
            ?: return ActionOutcome.Acted("No element with index $index to type into.")
        element.node.refresh()
        val set = driver.setText(element.node, text)
        if (!set) return ActionOutcome.Acted("Could not set text on [$index] — is it editable?")
        var msg = "Typed \"$text\" into [$index]."
        if (pressEnter == true) {
            val submitted = driver.pressImeAction(element.node)
            msg += if (submitted) " Submitted." else " (Could not press enter; tap the search/go button.)"
        }
        return ActionOutcome.Acted(msg)
    }

    private suspend fun scroll(direction: String?, index: Int?): ActionOutcome {
        val dir = direction?.lowercase() ?: "down"
        val bounds = index?.let { observation.elements[it]?.bounds }
        val cx = bounds?.centerX() ?: (observation.screenWidth / 2)
        val cy = bounds?.centerY() ?: (observation.screenHeight / 2)
        val w = observation.screenWidth
        val h = observation.screenHeight
        val (x1, y1, x2, y2) = when (dir) {
            "up" -> Quad(cx, (h * 0.35).toInt(), cx, (h * 0.7).toInt())
            "left" -> Quad((w * 0.3).toInt(), cy, (w * 0.7).toInt(), cy)
            "right" -> Quad((w * 0.7).toInt(), cy, (w * 0.3).toInt(), cy)
            else -> Quad(cx, (h * 0.7).toInt(), cx, (h * 0.35).toInt()) // down
        }
        val ok = driver.swipe(x1, y1, x2, y2, durationMs = 300)
        return ActionOutcome.Acted(if (ok) "Scrolled $dir." else "Scroll $dir failed.")
    }

    private suspend fun swipe(x1: Int?, y1: Int?, x2: Int?, y2: Int?): ActionOutcome {
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return ActionOutcome.Acted("swipe needs x1,y1,x2,y2.")
        }
        val ok = driver.swipe(scaleX(x1), scaleY(y1), scaleX(x2), scaleY(y2))
        return ActionOutcome.Acted(if (ok) "Swiped." else "Swipe failed.")
    }

    /**
     * The model gives coordinates in the downscaled screenshot's pixel grid
     * (Observation.imageWidth/Height); the gesture dispatcher needs real pixels.
     */
    private fun scaleX(x: Int): Int = scaler.x(x)

    private fun scaleY(y: Int): Int = scaler.y(y)

    private suspend fun openApp(pkg: String?): ActionOutcome {
        if (pkg.isNullOrBlank()) return ActionOutcome.Acted("open_app needs a package name.")
        val ok = driver.launchApp(pkg)
        return ActionOutcome.Acted(
            if (ok) "Launched $pkg." else "Could not launch $pkg (not installed?).",
        )
    }

    private suspend fun openLink(uri: String?): ActionOutcome {
        if (uri.isNullOrBlank()) return ActionOutcome.Acted("open_link needs a uri.")
        val ok = driver.launchDeepLink(uri)
        return ActionOutcome.Acted(if (ok) "Opened $uri." else "Could not open $uri.")
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)

    companion object {
        private const val TAG = "ActionExecutor"
    }
}

// ---- JsonObject accessors ----------------------------------------------------

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.doubleOrNull(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNullSafe()
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.boolOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
