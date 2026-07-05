package com.agentchat.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Describes an in-progress "@" mention at the caret: [start] is the index of the
 * '@', and [query] is the (whitespace-free) text typed after it.
 */
data class MentionContext(val start: Int, val query: String)

/**
 * Returns the active mention at the caret, or null. A mention is active when the
 * caret sits inside a run of non-whitespace text that begins with '@', where the
 * '@' is at the start of the input or preceded by whitespace.
 */
fun detectMention(value: TextFieldValue): MentionContext? {
    val selection = value.selection
    if (!selection.collapsed) return null
    val caret = selection.end
    val text = value.text
    if (caret > text.length) return null

    var i = caret - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '@') {
            val atStart = i == 0 || text[i - 1].isWhitespace()
            if (!atStart) return null
            val query = text.substring(i + 1, caret)
            return if (query.any { it.isWhitespace() }) null else MentionContext(i, query)
        }
        if (c.isWhitespace()) return null
        i--
    }
    return null
}

/**
 * Replaces the mention starting at [mention].start (through the current caret)
 * with "@[token] " and places the caret right after the inserted space.
 */
fun applyMention(
    value: TextFieldValue,
    mention: MentionContext,
    token: String,
): TextFieldValue {
    val caret = value.selection.end
    val prefix = value.text.substring(0, mention.start)
    val suffix = value.text.substring(caret)
    val inserted = "@$token "
    val newText = prefix + inserted + suffix
    val newCaret = prefix.length + inserted.length
    return TextFieldValue(text = newText, selection = TextRange(newCaret))
}
