package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryFactEntity::class,
        ProviderSlotEntity::class,
        AgentStateEntity::class,
        AgentCollaborationEntity::class,
        MarketplaceToolEntity::class,
        ResearchEntryEntity::class,
        SelfImprovementEntity::class,
        NotificationRecordEntity::class,
        ReminderEntity::class,
        BuildRecordEntity::class,
        AuditLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TeenaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun providerSlotDao(): ProviderSlotDao
    abstract fun agentDao(): AgentDao
    abstract fun marketplaceToolDao(): MarketplaceToolDao
    abstract fun researchDao(): ResearchDao
    abstract fun systemRecordsDao(): SystemRecordsDao

    companion object {
        @Volatile
        private var INSTANCE: TeenaDatabase? = null

        fun getInstance(context: Context): TeenaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TeenaDatabase::class.java,
                    "teena_ai_core.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
