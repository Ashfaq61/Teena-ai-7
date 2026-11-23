package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class FeatureStatus {
    ACTIVE,
    PARTIAL,
    BLOCKED,
    FAILED,
    NOT_IMPLEMENTED
}

enum class ProviderType(val displayName: String) {
    GEMINI_AI_LOGIC("Gemini (Firebase AI Logic)"),
    GEMINI_DIRECT("Gemini Direct REST"),
    GROQ("Groq Cloud"),
    OPENROUTER("OpenRouter"),
    CUSTOM_OPENAI("Custom OpenAI-Compatible"),
    OFFLINE_LOCAL("Local / Offline Engine")
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val languageHint: String = "Auto",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // "user", "assistant", "system", "voice"
    val content: String,
    val providerUsed: String = "",
    val modelUsed: String = "",
    val detectedLanguage: String = "",
    val citations: String = "",
    val latencyMs: Long = 0,
    val isVoiceMessage: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val factKey: String,
    val factValue: String,
    val source: String = "Conversation",
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "provider_slots")
data class ProviderSlotEntity(
    @PrimaryKey val slotId: String,
    val providerType: String,
    val label: String,
    val selectedModel: String,
    val baseUrl: String = "",
    val isEnabled: Boolean = true,
    val isPrimary: Boolean = false,
    val lastTestStatus: String = "UNTESTED",
    val lastTestMessage: String = "Not tested yet",
    val lastLatencyMs: Long = 0,
    val lastTestedAt: Long = 0
)

@Entity(tableName = "agent_states")
data class AgentStateEntity(
    @PrimaryKey val agentId: String,
    val displayName: String,
    val specialty: String,
    val status: String = "IDLE",
    val currentTask: String = "Ready for assignment",
    val tasksCompleted: Int = 0,
    val tasksFailed: Int = 0,
    val healthStatus: String = "ACTIVE",
    val healthReason: String = "",
    val lastActiveAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_collaborations")
data class AgentCollaborationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromAgentId: String,
    val toAgentId: String,
    val messageType: String,
    val protocolPayload: String,
    val outcomeStatus: String = "DELIVERED",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "marketplace_tools")
data class MarketplaceToolEntity(
    @PrimaryKey val toolId: String,
    val name: String,
    val description: String,
    val version: String,
    val category: String,
    val sourceRepo: String,
    val licenseSpdx: String,
    val executionSpec: String,
    val isBuiltIn: Boolean = false,
    val isInstalled: Boolean = false,
    val activationStatus: String = "NOT_IMPLEMENTED",
    val securityScore: Int = 100,
    val securityNotes: String = "",
    val testResult: String = "Not yet tested",
    val previousVersionSpec: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "research_entries")
data class ResearchEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String,
    val title: String,
    val summary: String,
    val sourceUrl: String,
    val licenseInfo: String = "",
    val compatibilityNotes: String = "",
    val securityScore: Int = 100,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "self_improvement_logs")
data class SelfImprovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cycleTitle: String,
    val stageReached: String,
    val targetComponent: String,
    val findings: String,
    val actionTaken: String,
    val verified: Boolean = false,
    val rolledBack: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "notification_records")
data class NotificationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val isImportant: Boolean = false,
    val hasReplyAction: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val triggerAtMillis: Long,
    val locationLabel: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Float = 200f,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "build_records")
data class BuildRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val versionName: String,
    val versionCode: Int,
    val buildSource: String,
    val status: String,
    val sha256Checksum: String,
    val signingCertSha256: String,
    val apkSizeBytes: Long,
    val logs: String,
    val downloadUrl: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val action: String,
    val outcome: String,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)
