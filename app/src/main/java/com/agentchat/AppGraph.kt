package com.agentchat

import android.content.Context
import com.agentchat.agent.AgentOrchestrator
import com.agentchat.apps.InstalledApp
import com.agentchat.apps.InstalledAppsRepository
import com.agentchat.data.ChatDatabase
import com.agentchat.data.ChatRepository
import com.agentchat.settings.AgentSettings
import com.agentchat.voice.VoiceSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide singletons shared by every entry point (chat UI, the voice
 * foreground service, the assistant activity, the QS tile). One orchestrator
 * and one voice controller exist per process, so an ask_user pause created in
 * chat can be resumed by voice and vice versa.
 */
object AppGraph {

    lateinit var repository: ChatRepository
        private set
    lateinit var settings: AgentSettings
        private set
    lateinit var appsRepository: InstalledAppsRepository
        private set
    lateinit var orchestrator: AgentOrchestrator
        private set
    lateinit var voiceController: VoiceSessionController
        private set

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        repository = ChatRepository(ChatDatabase.getInstance(app).messageDao())
        settings = AgentSettings(app)
        appsRepository = InstalledAppsRepository(app)
        orchestrator = AgentOrchestrator(
            context = app,
            repository = repository,
            settings = settings,
            installedAppsProvider = { _installedApps.value },
        )
        voiceController = VoiceSessionController(
            repository = repository,
            settings = settings,
            orchestrator = orchestrator,
        )
        initialized = true
        scope.launch { _installedApps.value = appsRepository.loadApps() }
    }
}
