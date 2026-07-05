package com.agentchat.apps

import androidx.compose.ui.graphics.ImageBitmap

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val isAgentEnabled: Boolean,
) {
    /** Handle inserted into the input, e.g. "spotify" or "gmail". */
    val mentionToken: String =
        AgentCatalog.byPackage(packageName)?.mentionToken
            ?: label.filterNot { it.isWhitespace() }.lowercase()
}
