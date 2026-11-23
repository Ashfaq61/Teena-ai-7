package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.teenaDataStore by preferencesDataStore(name = "teena_preferences")

data class TeenaSettings(
    val darkTheme: Boolean = true,
    val selectedVoice: String = "AOEDE",
    val continuousListening: Boolean = true,
    val wakePhraseEnabled: Boolean = false,
    val autoDetectLanguage: Boolean = true,
    val autoFailoverProviders: Boolean = true,
    val backgroundServiceEnabled: Boolean = false,
    val confirmSensitiveActions: Boolean = true,
    val saveConversationMemory: Boolean = true,
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubWorkflowId: String = "android-build.yml",
    val activeConversationId: Long = 0L
)

class TeenaPreferences(private val context: Context) {

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val SELECTED_VOICE = stringPreferencesKey("selected_voice")
        val CONTINUOUS_LISTENING = booleanPreferencesKey("continuous_listening")
        val WAKE_PHRASE = booleanPreferencesKey("wake_phrase_enabled")
        val AUTO_LANG = booleanPreferencesKey("auto_detect_language")
        val AUTO_FAILOVER = booleanPreferencesKey("auto_failover_providers")
        val BG_SERVICE = booleanPreferencesKey("background_service_enabled")
        val CONFIRM_SENSITIVE = booleanPreferencesKey("confirm_sensitive_actions")
        val SAVE_MEMORY = booleanPreferencesKey("save_conversation_memory")
        val GITHUB_OWNER = stringPreferencesKey("github_owner")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val GITHUB_WORKFLOW = stringPreferencesKey("github_workflow_id")
        val ACTIVE_CONVERSATION_ID = stringPreferencesKey("active_conversation_id")
    }

    val settingsFlow: Flow<TeenaSettings> = context.teenaDataStore.data.map { prefs ->
        TeenaSettings(
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            selectedVoice = prefs[Keys.SELECTED_VOICE] ?: "AOEDE",
            continuousListening = prefs[Keys.CONTINUOUS_LISTENING] ?: true,
            wakePhraseEnabled = prefs[Keys.WAKE_PHRASE] ?: false,
            autoDetectLanguage = prefs[Keys.AUTO_LANG] ?: true,
            autoFailoverProviders = prefs[Keys.AUTO_FAILOVER] ?: true,
            backgroundServiceEnabled = prefs[Keys.BG_SERVICE] ?: false,
            confirmSensitiveActions = prefs[Keys.CONFIRM_SENSITIVE] ?: true,
            saveConversationMemory = prefs[Keys.SAVE_MEMORY] ?: true,
            githubOwner = prefs[Keys.GITHUB_OWNER] ?: "",
            githubRepo = prefs[Keys.GITHUB_REPO] ?: "",
            githubWorkflowId = prefs[Keys.GITHUB_WORKFLOW] ?: "android-build.yml",
            activeConversationId = prefs[Keys.ACTIVE_CONVERSATION_ID]?.toLongOrNull() ?: 0L
        )
    }

    suspend fun updateSettings(transform: (TeenaSettings) -> TeenaSettings) {
        context.teenaDataStore.edit { prefs ->
            val current = TeenaSettings(
                darkTheme = prefs[Keys.DARK_THEME] ?: true,
                selectedVoice = prefs[Keys.SELECTED_VOICE] ?: "AOEDE",
                continuousListening = prefs[Keys.CONTINUOUS_LISTENING] ?: true,
                wakePhraseEnabled = prefs[Keys.WAKE_PHRASE] ?: false,
                autoDetectLanguage = prefs[Keys.AUTO_LANG] ?: true,
                autoFailoverProviders = prefs[Keys.AUTO_FAILOVER] ?: true,
                backgroundServiceEnabled = prefs[Keys.BG_SERVICE] ?: false,
                confirmSensitiveActions = prefs[Keys.CONFIRM_SENSITIVE] ?: true,
                saveConversationMemory = prefs[Keys.SAVE_MEMORY] ?: true,
                githubOwner = prefs[Keys.GITHUB_OWNER] ?: "",
                githubRepo = prefs[Keys.GITHUB_REPO] ?: "",
                githubWorkflowId = prefs[Keys.GITHUB_WORKFLOW] ?: "android-build.yml",
                activeConversationId = prefs[Keys.ACTIVE_CONVERSATION_ID]?.toLongOrNull() ?: 0L
            )
            val next = transform(current)
            prefs[Keys.DARK_THEME] = next.darkTheme
            prefs[Keys.SELECTED_VOICE] = next.selectedVoice
            prefs[Keys.CONTINUOUS_LISTENING] = next.continuousListening
            prefs[Keys.WAKE_PHRASE] = next.wakePhraseEnabled
            prefs[Keys.AUTO_LANG] = next.autoDetectLanguage
            prefs[Keys.AUTO_FAILOVER] = next.autoFailoverProviders
            prefs[Keys.BG_SERVICE] = next.backgroundServiceEnabled
            prefs[Keys.CONFIRM_SENSITIVE] = next.confirmSensitiveActions
            prefs[Keys.SAVE_MEMORY] = next.saveConversationMemory
            prefs[Keys.GITHUB_OWNER] = next.githubOwner
            prefs[Keys.GITHUB_REPO] = next.githubRepo
            prefs[Keys.GITHUB_WORKFLOW] = next.githubWorkflowId
            prefs[Keys.ACTIVE_CONVERSATION_ID] = next.activeConversationId.toString()
        }
    }
}
