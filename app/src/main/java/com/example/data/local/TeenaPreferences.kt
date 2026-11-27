package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ai.RoutingCostStrategy
import com.example.ai.TeenaIdentityEngine
import com.example.ai.TeenaIdentityProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.teenaDataStore by preferencesDataStore(name = "teena_preferences")

data class TeenaSettings(
    val darkTheme: Boolean = true,
    val selectedVoice: String = "AOEDE",
    val continuousListening: Boolean = true,
    val wakePhraseEnabled: Boolean = false,
    val autoDetectLanguage: Boolean = true,
    val echoGuardEnabled: Boolean = true,
    val autoFailoverProviders: Boolean = true,
    val backgroundServiceEnabled: Boolean = false,
    val confirmSensitiveActions: Boolean = true,
    val saveConversationMemory: Boolean = true,
    val externalAiCallsEnabled: Boolean = true,
    val advisorHourlyRateLimit: Int = 12,
    val autoActivateSafeDeclarativeTools: Boolean = false,
    val autoSelfImproveEnabled: Boolean = false,
    val selfImproveIntervalHours: Int = 24,
    val lastSelfImproveRunAt: Long = 0L,
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubWorkflowId: String = "build-apk.yml",
    val activeConversationId: Long = 0L,
    // Phase 2: Teena Personalities & User Identity
    val personalityId: String = TeenaIdentityEngine.DEFAULT_PERSONALITY_ID,
    val userName: String = "",
    val userPreferredAddressing: String = "By Name",
    val userBioOrFocus: String = "",
    val customPersonaInstructions: String = "",
    val syncVoiceWithPersonality: Boolean = true,
    // Phase 4: Voice Rooms & LiveKit Endpoint Connector
    val voiceRoomsServerUrl: String = "",
    val liveKitTokenEndpoint: String = "",
    val liveKitWsUrl: String = "",
    val voiceRoomParticipantName: String = "",
    // Phase 6: Direct Accessibility & Social Agent toggle (default ON)
    val directAccessibilityAgentEnabled: Boolean = true,
    // Phase 7: WhatsApp Notification Reader toggle & Trigger Monitor toggle
    val whatsappNotifReaderEnabled: Boolean = true,
    val triggerMonitorEnabled: Boolean = false,
    // Phase 8: Optional Device Admin opt-in toggle (default OFF; user must also enable in Android Settings)
    val directDeviceAdminEnabled: Boolean = false,
    // Dynamic Prompt Router (Gemini / Groq / OpenRouter)
    val dynamicPromptRoutingEnabled: Boolean = true,
    val routingCostStrategy: String = RoutingCostStrategy.BALANCED.name
)

fun TeenaSettings.toIdentityProfile(): TeenaIdentityProfile = TeenaIdentityProfile(
    personalityId = personalityId,
    userName = userName,
    preferredAddressing = userPreferredAddressing,
    userBioOrFocus = userBioOrFocus,
    customPersonaInstructions = customPersonaInstructions,
    syncVoiceWithPersonality = syncVoiceWithPersonality,
    activeVoiceId = selectedVoice
)

class TeenaPreferences(private val context: Context) {

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val SELECTED_VOICE = stringPreferencesKey("selected_voice")
        val CONTINUOUS_LISTENING = booleanPreferencesKey("continuous_listening")
        val WAKE_PHRASE = booleanPreferencesKey("wake_phrase_enabled")
        val AUTO_LANG = booleanPreferencesKey("auto_detect_language")
        val ECHO_GUARD = booleanPreferencesKey("echo_guard_enabled")
        val AUTO_FAILOVER = booleanPreferencesKey("auto_failover_providers")
        val BG_SERVICE = booleanPreferencesKey("background_service_enabled")
        val CONFIRM_SENSITIVE = booleanPreferencesKey("confirm_sensitive_actions")
        val SAVE_MEMORY = booleanPreferencesKey("save_conversation_memory")
        val EXTERNAL_AI_CALLS = booleanPreferencesKey("external_ai_calls_enabled")
        val ADVISOR_HOURLY_RATE_LIMIT = intPreferencesKey("advisor_hourly_rate_limit")
        val AUTO_ACTIVATE_DECLARATIVE = booleanPreferencesKey("auto_activate_safe_declarative_tools")
        val AUTO_SELF_IMPROVE = booleanPreferencesKey("auto_self_improve_enabled")
        val SELF_IMPROVE_INTERVAL_HOURS = intPreferencesKey("self_improve_interval_hours")
        val LAST_SELF_IMPROVE_RUN_AT = longPreferencesKey("last_self_improve_run_at")
        val GITHUB_OWNER = stringPreferencesKey("github_owner")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val GITHUB_WORKFLOW = stringPreferencesKey("github_workflow_id")
        val ACTIVE_CONVERSATION_ID = stringPreferencesKey("active_conversation_id")
        val PERSONALITY_ID = stringPreferencesKey("personality_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_PREFERRED_ADDRESSING = stringPreferencesKey("user_preferred_addressing")
        val USER_BIO_OR_FOCUS = stringPreferencesKey("user_bio_or_focus")
        val CUSTOM_PERSONA_INSTRUCTIONS = stringPreferencesKey("custom_persona_instructions")
        val SYNC_VOICE_WITH_PERSONALITY = booleanPreferencesKey("sync_voice_with_personality")
        val VOICE_ROOMS_SERVER_URL = stringPreferencesKey("voice_rooms_server_url")
        val LIVEKIT_TOKEN_ENDPOINT = stringPreferencesKey("livekit_token_endpoint")
        val LIVEKIT_WS_URL = stringPreferencesKey("livekit_ws_url")
        val VOICE_ROOM_PARTICIPANT_NAME = stringPreferencesKey("voice_room_participant_name")
        val DIRECT_ACCESSIBILITY_AGENT_ENABLED = booleanPreferencesKey("direct_accessibility_agent_enabled")
        val WHATSAPP_NOTIF_READER_ENABLED = booleanPreferencesKey("whatsapp_notif_reader_enabled")
        val TRIGGER_MONITOR_ENABLED = booleanPreferencesKey("trigger_monitor_enabled")
        val DIRECT_DEVICE_ADMIN_ENABLED = booleanPreferencesKey("direct_device_admin_enabled")
        val DYNAMIC_PROMPT_ROUTING_ENABLED = booleanPreferencesKey("dynamic_prompt_routing_enabled")
        val ROUTING_COST_STRATEGY = stringPreferencesKey("routing_cost_strategy")
    }

    val settingsFlow: Flow<TeenaSettings> = context.teenaDataStore.data.map { prefs ->
        TeenaSettings(
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            selectedVoice = prefs[Keys.SELECTED_VOICE] ?: "AOEDE",
            continuousListening = prefs[Keys.CONTINUOUS_LISTENING] ?: true,
            wakePhraseEnabled = prefs[Keys.WAKE_PHRASE] ?: false,
            autoDetectLanguage = prefs[Keys.AUTO_LANG] ?: true,
            echoGuardEnabled = prefs[Keys.ECHO_GUARD] ?: true,
            autoFailoverProviders = prefs[Keys.AUTO_FAILOVER] ?: true,
            backgroundServiceEnabled = prefs[Keys.BG_SERVICE] ?: false,
            confirmSensitiveActions = prefs[Keys.CONFIRM_SENSITIVE] ?: true,
            saveConversationMemory = prefs[Keys.SAVE_MEMORY] ?: true,
            externalAiCallsEnabled = prefs[Keys.EXTERNAL_AI_CALLS] ?: true,
            advisorHourlyRateLimit = (prefs[Keys.ADVISOR_HOURLY_RATE_LIMIT] ?: 12).coerceIn(2, 120),
            autoActivateSafeDeclarativeTools = prefs[Keys.AUTO_ACTIVATE_DECLARATIVE] ?: false,
            autoSelfImproveEnabled = prefs[Keys.AUTO_SELF_IMPROVE] ?: false,
            selfImproveIntervalHours = prefs[Keys.SELF_IMPROVE_INTERVAL_HOURS] ?: 24,
            lastSelfImproveRunAt = prefs[Keys.LAST_SELF_IMPROVE_RUN_AT] ?: 0L,
            githubOwner = prefs[Keys.GITHUB_OWNER] ?: "",
            githubRepo = prefs[Keys.GITHUB_REPO] ?: "",
            githubWorkflowId = prefs[Keys.GITHUB_WORKFLOW] ?: "build-apk.yml",
            activeConversationId = prefs[Keys.ACTIVE_CONVERSATION_ID]?.toLongOrNull() ?: 0L,
            personalityId = prefs[Keys.PERSONALITY_ID] ?: TeenaIdentityEngine.DEFAULT_PERSONALITY_ID,
            userName = prefs[Keys.USER_NAME] ?: "",
            userPreferredAddressing = prefs[Keys.USER_PREFERRED_ADDRESSING] ?: "By Name",
            userBioOrFocus = prefs[Keys.USER_BIO_OR_FOCUS] ?: "",
            customPersonaInstructions = prefs[Keys.CUSTOM_PERSONA_INSTRUCTIONS] ?: "",
            syncVoiceWithPersonality = prefs[Keys.SYNC_VOICE_WITH_PERSONALITY] ?: true,
            voiceRoomsServerUrl = prefs[Keys.VOICE_ROOMS_SERVER_URL] ?: "",
            liveKitTokenEndpoint = prefs[Keys.LIVEKIT_TOKEN_ENDPOINT] ?: "",
            liveKitWsUrl = prefs[Keys.LIVEKIT_WS_URL] ?: "",
            voiceRoomParticipantName = prefs[Keys.VOICE_ROOM_PARTICIPANT_NAME] ?: "",
            directAccessibilityAgentEnabled = prefs[Keys.DIRECT_ACCESSIBILITY_AGENT_ENABLED] ?: true,
            whatsappNotifReaderEnabled = prefs[Keys.WHATSAPP_NOTIF_READER_ENABLED] ?: true,
            triggerMonitorEnabled = prefs[Keys.TRIGGER_MONITOR_ENABLED] ?: false,
            directDeviceAdminEnabled = prefs[Keys.DIRECT_DEVICE_ADMIN_ENABLED] ?: false,
            dynamicPromptRoutingEnabled = prefs[Keys.DYNAMIC_PROMPT_ROUTING_ENABLED] ?: true,
            routingCostStrategy = prefs[Keys.ROUTING_COST_STRATEGY] ?: RoutingCostStrategy.BALANCED.name
        )
    }

    suspend fun getSettingsSnapshot(): TeenaSettings = settingsFlow.first()

    suspend fun updateSettings(transform: (TeenaSettings) -> TeenaSettings) {
        context.teenaDataStore.edit { prefs ->
            val current = TeenaSettings(
                darkTheme = prefs[Keys.DARK_THEME] ?: true,
                selectedVoice = prefs[Keys.SELECTED_VOICE] ?: "AOEDE",
                continuousListening = prefs[Keys.CONTINUOUS_LISTENING] ?: true,
                wakePhraseEnabled = prefs[Keys.WAKE_PHRASE] ?: false,
                autoDetectLanguage = prefs[Keys.AUTO_LANG] ?: true,
                echoGuardEnabled = prefs[Keys.ECHO_GUARD] ?: true,
                autoFailoverProviders = prefs[Keys.AUTO_FAILOVER] ?: true,
                backgroundServiceEnabled = prefs[Keys.BG_SERVICE] ?: false,
                confirmSensitiveActions = prefs[Keys.CONFIRM_SENSITIVE] ?: true,
                saveConversationMemory = prefs[Keys.SAVE_MEMORY] ?: true,
                externalAiCallsEnabled = prefs[Keys.EXTERNAL_AI_CALLS] ?: true,
                advisorHourlyRateLimit = (prefs[Keys.ADVISOR_HOURLY_RATE_LIMIT] ?: 12).coerceIn(2, 120),
                autoActivateSafeDeclarativeTools = prefs[Keys.AUTO_ACTIVATE_DECLARATIVE] ?: false,
                autoSelfImproveEnabled = prefs[Keys.AUTO_SELF_IMPROVE] ?: false,
                selfImproveIntervalHours = prefs[Keys.SELF_IMPROVE_INTERVAL_HOURS] ?: 24,
                lastSelfImproveRunAt = prefs[Keys.LAST_SELF_IMPROVE_RUN_AT] ?: 0L,
                githubOwner = prefs[Keys.GITHUB_OWNER] ?: "",
                githubRepo = prefs[Keys.GITHUB_REPO] ?: "",
                githubWorkflowId = prefs[Keys.GITHUB_WORKFLOW] ?: "build-apk.yml",
                activeConversationId = prefs[Keys.ACTIVE_CONVERSATION_ID]?.toLongOrNull() ?: 0L,
                personalityId = prefs[Keys.PERSONALITY_ID] ?: TeenaIdentityEngine.DEFAULT_PERSONALITY_ID,
                userName = prefs[Keys.USER_NAME] ?: "",
                userPreferredAddressing = prefs[Keys.USER_PREFERRED_ADDRESSING] ?: "By Name",
                userBioOrFocus = prefs[Keys.USER_BIO_OR_FOCUS] ?: "",
                customPersonaInstructions = prefs[Keys.CUSTOM_PERSONA_INSTRUCTIONS] ?: "",
                syncVoiceWithPersonality = prefs[Keys.SYNC_VOICE_WITH_PERSONALITY] ?: true,
                voiceRoomsServerUrl = prefs[Keys.VOICE_ROOMS_SERVER_URL] ?: "",
                liveKitTokenEndpoint = prefs[Keys.LIVEKIT_TOKEN_ENDPOINT] ?: "",
                liveKitWsUrl = prefs[Keys.LIVEKIT_WS_URL] ?: "",
                voiceRoomParticipantName = prefs[Keys.VOICE_ROOM_PARTICIPANT_NAME] ?: "",
                directAccessibilityAgentEnabled = prefs[Keys.DIRECT_ACCESSIBILITY_AGENT_ENABLED] ?: true,
                whatsappNotifReaderEnabled = prefs[Keys.WHATSAPP_NOTIF_READER_ENABLED] ?: true,
                triggerMonitorEnabled = prefs[Keys.TRIGGER_MONITOR_ENABLED] ?: false,
                directDeviceAdminEnabled = prefs[Keys.DIRECT_DEVICE_ADMIN_ENABLED] ?: false,
                dynamicPromptRoutingEnabled = prefs[Keys.DYNAMIC_PROMPT_ROUTING_ENABLED] ?: true,
                routingCostStrategy = prefs[Keys.ROUTING_COST_STRATEGY] ?: RoutingCostStrategy.BALANCED.name
            )
            val next = transform(current)
            prefs[Keys.DARK_THEME] = next.darkTheme
            prefs[Keys.SELECTED_VOICE] = next.selectedVoice
            prefs[Keys.CONTINUOUS_LISTENING] = next.continuousListening
            prefs[Keys.WAKE_PHRASE] = next.wakePhraseEnabled
            prefs[Keys.AUTO_LANG] = next.autoDetectLanguage
            prefs[Keys.ECHO_GUARD] = next.echoGuardEnabled
            prefs[Keys.AUTO_FAILOVER] = next.autoFailoverProviders
            prefs[Keys.BG_SERVICE] = next.backgroundServiceEnabled
            prefs[Keys.CONFIRM_SENSITIVE] = next.confirmSensitiveActions
            prefs[Keys.SAVE_MEMORY] = next.saveConversationMemory
            prefs[Keys.EXTERNAL_AI_CALLS] = next.externalAiCallsEnabled
            prefs[Keys.ADVISOR_HOURLY_RATE_LIMIT] = next.advisorHourlyRateLimit.coerceIn(2, 120)
            prefs[Keys.AUTO_ACTIVATE_DECLARATIVE] = next.autoActivateSafeDeclarativeTools
            prefs[Keys.AUTO_SELF_IMPROVE] = next.autoSelfImproveEnabled
            prefs[Keys.SELF_IMPROVE_INTERVAL_HOURS] = next.selfImproveIntervalHours
            prefs[Keys.LAST_SELF_IMPROVE_RUN_AT] = next.lastSelfImproveRunAt
            prefs[Keys.GITHUB_OWNER] = next.githubOwner
            prefs[Keys.GITHUB_REPO] = next.githubRepo
            prefs[Keys.GITHUB_WORKFLOW] = next.githubWorkflowId
            prefs[Keys.ACTIVE_CONVERSATION_ID] = next.activeConversationId.toString()
            prefs[Keys.PERSONALITY_ID] = next.personalityId
            prefs[Keys.USER_NAME] = next.userName
            prefs[Keys.USER_PREFERRED_ADDRESSING] = next.userPreferredAddressing
            prefs[Keys.USER_BIO_OR_FOCUS] = next.userBioOrFocus
            prefs[Keys.CUSTOM_PERSONA_INSTRUCTIONS] = next.customPersonaInstructions
            prefs[Keys.SYNC_VOICE_WITH_PERSONALITY] = next.syncVoiceWithPersonality
            prefs[Keys.VOICE_ROOMS_SERVER_URL] = next.voiceRoomsServerUrl
            prefs[Keys.LIVEKIT_TOKEN_ENDPOINT] = next.liveKitTokenEndpoint
            prefs[Keys.LIVEKIT_WS_URL] = next.liveKitWsUrl
            prefs[Keys.VOICE_ROOM_PARTICIPANT_NAME] = next.voiceRoomParticipantName
            prefs[Keys.DIRECT_ACCESSIBILITY_AGENT_ENABLED] = next.directAccessibilityAgentEnabled
            prefs[Keys.WHATSAPP_NOTIF_READER_ENABLED] = next.whatsappNotifReaderEnabled
            prefs[Keys.TRIGGER_MONITOR_ENABLED] = next.triggerMonitorEnabled
            prefs[Keys.DIRECT_DEVICE_ADMIN_ENABLED] = next.directDeviceAdminEnabled
            prefs[Keys.DYNAMIC_PROMPT_ROUTING_ENABLED] = next.dynamicPromptRoutingEnabled
            prefs[Keys.ROUTING_COST_STRATEGY] = next.routingCostStrategy
        }
    }
}

