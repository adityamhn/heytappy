package com.agentchat.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class InstalledAppsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    private val cacheMutex = Mutex()
    @Volatile
    private var cache: List<InstalledApp>? = null

    /** Loads all launchable apps once and caches them. Safe to call from the UI. */
    suspend fun loadApps(): List<InstalledApp> {
        cache?.let { return it }
        return cacheMutex.withLock {
            cache ?: loadFromPackageManager().also { cache = it }
        }
    }

    private suspend fun loadFromPackageManager(): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            installed
                .filter { it.isLaunchable() }
                .map { info ->
                    val label = pm.getApplicationLabel(info).toString()
                    val icon = runCatching {
                        pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                    }.getOrNull()
                    InstalledApp(
                        packageName = info.packageName,
                        label = label,
                        icon = icon,
                        isAgentEnabled = AgentCatalog.isAgentEnabled(info.packageName),
                    )
                }
                .sortedWith(appRanking)
        }

    private fun ApplicationInfo.isLaunchable(): Boolean =
        pm.getLaunchIntentForPackage(packageName) != null

    /** Agent-enabled apps first (in catalog order), then alphabetical by label. */
    private val appRanking: Comparator<InstalledApp> =
        compareByDescending<InstalledApp> { it.isAgentEnabled }
            .thenBy { agentOrder(it.packageName) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }

    private fun agentOrder(packageName: String): Int {
        val idx = AgentCatalog.apps.indexOfFirst { it.packageName == packageName }
        return if (idx == -1) Int.MAX_VALUE else idx
    }

    companion object {
        private const val ICON_PX = 96
    }
}
