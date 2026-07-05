package com.agentchat.apps

/**
 * Central registry of apps that AgentChat can drive with a dedicated flow.
 *
 * [mentionToken] is the short handle inserted into the input when the app is
 * picked (e.g. "@spotify"), and is what the intent parser resolves back to a
 * package name in Phase 4.
 */
data class AgentApp(
    val packageName: String,
    val mentionToken: String,
    val displayName: String,
)

object AgentCatalog {
    val apps: List<AgentApp> = listOf(
        AgentApp(
            packageName = "com.spotify.music",
            mentionToken = "spotify",
            displayName = "Spotify",
        ),
        AgentApp(
            packageName = "com.ubercab",
            mentionToken = "uber",
            displayName = "Uber",
        ),
    )

    private val byPackage = apps.associateBy { it.packageName }
    private val byToken = apps.associateBy { it.mentionToken.lowercase() }

    fun isAgentEnabled(packageName: String): Boolean = byPackage.containsKey(packageName)

    fun byPackage(packageName: String): AgentApp? = byPackage[packageName]

    fun byToken(token: String): AgentApp? = byToken[token.lowercase()]
}
