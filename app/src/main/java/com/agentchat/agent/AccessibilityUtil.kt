package com.agentchat.agent

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityUtil {

    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context, AgentAccessibilityService::class.java)

    /** True if the AgentChat accessibility service is currently enabled by the user. */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = serviceComponent(context)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }
}
