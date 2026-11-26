package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class FeatureStatus(val displayLabel: String) {
    ACTIVE("ACTIVE"),
    IMPLEMENTED_NOT_DEVICE_VERIFIED("IMPLEMENTED NOT DEVICE-VERIFIED"),
    PARTIAL("PARTIAL"),
    BLOCKED("BLOCKED"),
    FAILED("FAILED"),
    NOT_IMPLEMENTED("NOT_IMPLEMENTED")
}

enum class ProviderType(val displayName: String) {
    GEMINI_DIRECT("Gemini (Google AI API Key)"),
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

@Entity(tableName = "gemini_model_cache")
data class GeminiModelCacheEntity(
    @PrimaryKey val cacheKey: String,
    val keyFingerprint: String,
    val modelId: String,
    val fullName: String,
    val displayName: String,
    val supportsGenerateContent: Boolean,
    val supportsBidiGenerateContent: Boolean,
    val inputTokenLimit: Int = 0,
    val outputTokenLimit: Int = 0,
    val probeStatus: String = "UNPROBED", // UNPROBED, WORKING, BLACKLISTED
    val probeError: String = "",
    val lastLatencyMs: Long = Long.MAX_VALUE,
    val updatedAt: Long = System.currentTimeMillis()
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
    val healthStatus: String = FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name,
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

@Entity(tableName = "external_advisors")
data class ExternalAdvisorEntity(
    @PrimaryKey val advisorId: String,
    val name: String,
    val sourceType: String, // PROVIDER_MODEL, MCP_SERVER, A2A_AGENT
    val providerType: String,
    val slotId: String = "",
    val modelId: String = "",
    val endpointUrl: String = "",
    val status: String = FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name,
    val lastResponseTimeMs: Long = 0L,
    val lastStatusMessage: String = "Not probed yet",
    val capabilities: String = "",
    val isEnabled: Boolean = true,
    val lastCheckedAt: Long = 0L
)

@Entity(tableName = "external_collab_messages")
data class ExternalCollabMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val topic: String,
    val detectedGapReason: String,
    val questionSent: String,
    val advisorId: String,
    val advisorName: String,
    val advisorModel: String,
    val protocolUsed: String, // GEMINI_REST, OPENAI_COMPAT, MCP, A2A
    val rawResponse: String,
    val comparisonNotes: String = "",
    val responseTimeMs: Long = 0L,
    val succeeded: Boolean = false,
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "feature_verifications")
data class FeatureVerificationEntity(
    @PrimaryKey val featureId: String,
    val status: String,
    val runtimeTestPassed: Boolean,
    val deviceVerified: Boolean,
    val lastTestSummary: String,
    val testedAt: Long = System.currentTimeMillis()
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
    val activationStatus: String = FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name,
    val securityScore: Int = 100,
    val securityNotes: String = "",
    val testResult: String = "Not yet tested",
    val previousVersionSpec: String = "",
    val proposedBy: String = "Teena Core",
    val scanResult: String = "Pending scan",
    val toolKind: String = "BUILT_IN", // BUILT_IN, DECLARATIVE_JSON, KOTLIN_GITHUB_BRANCH
    val pipelineStagesJson: String = "[]",
    val historyLog: String = "",
    val deviceVerified: Boolean = false,
    val branchName: String = "",
    val releaseUrl: String = "",
    val capabilitySummaryJson: String = "",
    val autoActivated: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "capability_summaries")
data class CapabilitySummaryEntity(
    @PrimaryKey val itemKey: String, // e.g. "github:owner/repo" or "hf:modelId"
    val sourceType: String, // "GITHUB" or "HUGGINGFACE"
    val title: String,
    val sourceUrl: String,
    val commitOrRevision: String = "",
    val kyaKartaHai: String,
    val teenaMeinKyaAddHoga: String,
    val zarooriCheezein: String,
    val haddAurKhatre: String,
    val modelUsed: String = "",
    val status: String = "READY", // READY, FAILED, NOT_DONE
    val errorMessage: String = "",
    val sourceFilesSampled: String = "",
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

@Entity(tableName = "code_vault_entries")
data class CodeVaultEntryEntity(
    @PrimaryKey val vaultId: String,
    val repoFullName: String,
    val repoUrl: String,
    val commitSha: String,
    val licenseSpdx: String,
    val gplWarning: String = "",
    val attributionText: String = "",
    val zipFilePath: String,
    val metadataFilePath: String,
    val licenseFilePath: String,
    val zipSha256: String,
    val zipSizeBytes: Long,
    val riskLevel: String,
    val riskScore: Int,
    val verificationStatus: String = "VERIFIED", // VERIFIED, FAILED
    val verificationMessage: String = "Verified on save",
    val downloadedAtIso: String,
    val downloadedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "screen_automation_logs")
data class ScreenAutomationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val stepNumber: Int,
    val screenshotTimestamp: Long,
    val packageName: String,
    val understoodSummary: String,
    val actionTaken: String,
    val verificationResult: String, // VERIFIED, FAILED, BLOCKED, SENSITIVE_PENDING, DONE, PARTIAL
    val details: String,
    val modelUsed: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "triggers")
data class TriggerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerType: String = "ONE_TIME", // ONE_TIME, DAILY, INTERVAL, BOOT
    val actionType: String = "NOTIFY", // NOTIFY, SPEAK_ALERT, VOICE_PROMPT, AI_CHECK
    val actionPayload: String = "",
    val triggerAtMillis: Long = System.currentTimeMillis(),
    val intervalMinutes: Int = 60,
    val enabled: Boolean = true,
    val lastFiredAtMillis: Long = 0L,
    val lastResult: String = "Scheduled",
    val createdAt: Long = System.currentTimeMillis()
)

