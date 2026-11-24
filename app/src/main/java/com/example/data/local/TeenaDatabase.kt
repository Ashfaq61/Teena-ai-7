package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryFactEntity::class,
        ProviderSlotEntity::class,
        GeminiModelCacheEntity::class,
        AgentStateEntity::class,
        AgentCollaborationEntity::class,
        ExternalAdvisorEntity::class,
        ExternalCollabMessageEntity::class,
        FeatureVerificationEntity::class,
        MarketplaceToolEntity::class,
        ResearchEntryEntity::class,
        SelfImprovementEntity::class,
        NotificationRecordEntity::class,
        ReminderEntity::class,
        BuildRecordEntity::class,
        AuditLogEntity::class,
        CodeVaultEntryEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class TeenaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun providerSlotDao(): ProviderSlotDao
    abstract fun geminiModelDao(): GeminiModelDao
    abstract fun agentDao(): AgentDao
    abstract fun externalAdvisorDao(): ExternalAdvisorDao
    abstract fun externalCollabDao(): ExternalCollabDao
    abstract fun featureVerificationDao(): FeatureVerificationDao
    abstract fun marketplaceToolDao(): MarketplaceToolDao
    abstract fun researchDao(): ResearchDao
    abstract fun systemRecordsDao(): SystemRecordsDao
    abstract fun codeVaultDao(): CodeVaultDao

    companion object {
        @Volatile
        private var INSTANCE: TeenaDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gemini_model_cache` (
                        `cacheKey` TEXT NOT NULL,
                        `keyFingerprint` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `fullName` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `supportsGenerateContent` INTEGER NOT NULL,
                        `supportsBidiGenerateContent` INTEGER NOT NULL,
                        `inputTokenLimit` INTEGER NOT NULL,
                        `outputTokenLimit` INTEGER NOT NULL,
                        `probeStatus` TEXT NOT NULL,
                        `probeError` TEXT NOT NULL,
                        `lastLatencyMs` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `external_advisors` (
                        `advisorId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `providerType` TEXT NOT NULL,
                        `slotId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `endpointUrl` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `lastResponseTimeMs` INTEGER NOT NULL,
                        `lastStatusMessage` TEXT NOT NULL,
                        `capabilities` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `lastCheckedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`advisorId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `external_collab_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `topic` TEXT NOT NULL,
                        `detectedGapReason` TEXT NOT NULL,
                        `questionSent` TEXT NOT NULL,
                        `advisorId` TEXT NOT NULL,
                        `advisorName` TEXT NOT NULL,
                        `advisorModel` TEXT NOT NULL,
                        `protocolUsed` TEXT NOT NULL,
                        `rawResponse` TEXT NOT NULL,
                        `comparisonNotes` TEXT NOT NULL,
                        `responseTimeMs` INTEGER NOT NULL,
                        `succeeded` INTEGER NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `feature_verifications` (
                        `featureId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `runtimeTestPassed` INTEGER NOT NULL,
                        `deviceVerified` INTEGER NOT NULL,
                        `lastTestSummary` TEXT NOT NULL,
                        `testedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`featureId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `marketplace_tools` ADD COLUMN `proposedBy` TEXT NOT NULL DEFAULT 'Teena Core'")
                db.execSQL("ALTER TABLE `marketplace_tools` ADD COLUMN `scanResult` TEXT NOT NULL DEFAULT 'Pending scan'")
                db.execSQL("ALTER TABLE `marketplace_tools` ADD COLUMN `toolKind` TEXT NOT NULL DEFAULT 'BUILT_IN'")
                db.execSQL("ALTER TABLE `marketplace_tools` ADD COLUMN `pipelineStagesJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `marketplace_tools` ADD COLUMN `historyLog` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `marketplace_tools` ADD COLUMN `deviceVerified` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `marketplace_tools` ADD COLUMN `branchName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `marketplace_tools` ADD COLUMN `releaseUrl` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `code_vault_entries` (
                        `vaultId` TEXT NOT NULL,
                        `repoFullName` TEXT NOT NULL,
                        `repoUrl` TEXT NOT NULL,
                        `commitSha` TEXT NOT NULL,
                        `licenseSpdx` TEXT NOT NULL,
                        `gplWarning` TEXT NOT NULL,
                        `attributionText` TEXT NOT NULL,
                        `zipFilePath` TEXT NOT NULL,
                        `metadataFilePath` TEXT NOT NULL,
                        `licenseFilePath` TEXT NOT NULL,
                        `zipSha256` TEXT NOT NULL,
                        `zipSizeBytes` INTEGER NOT NULL,
                        `riskLevel` TEXT NOT NULL,
                        `riskScore` INTEGER NOT NULL,
                        `verificationStatus` TEXT NOT NULL,
                        `verificationMessage` TEXT NOT NULL,
                        `downloadedAtIso` TEXT NOT NULL,
                        `downloadedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`vaultId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): TeenaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TeenaDatabase::class.java,
                    "teena_ai_core.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
