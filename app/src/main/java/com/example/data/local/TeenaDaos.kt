package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAllConversationsOnce(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET updatedAt = :updatedAt, title = :title WHERE id = :id")
    suspend fun updateConversationMeta(id: Long, title: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 100")
    suspend fun searchMessages(query: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 200")
    suspend fun getAllMessagesOnce(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE isVoiceMessage = 1 ORDER BY timestamp DESC LIMIT 50")
    fun observeVoiceMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: Long)
}

@Dao
interface MemoryFactDao {
    @Query("SELECT * FROM memory_facts ORDER BY createdAt DESC")
    fun observeMemories(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts ORDER BY createdAt DESC")
    suspend fun getAllMemoriesOnce(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE factKey LIKE '%' || :query || '%' OR factValue LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun searchMemories(query: String): List<MemoryFactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(fact: MemoryFactEntity): Long

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM memory_facts")
    suspend fun clearAllMemories()
}

@Dao
interface ProviderSlotDao {
    @Query("SELECT * FROM provider_slots ORDER BY isPrimary DESC, providerType ASC")
    fun observeSlots(): Flow<List<ProviderSlotEntity>>

    @Query("SELECT * FROM provider_slots ORDER BY isPrimary DESC, providerType ASC")
    suspend fun getSlotsOnce(): List<ProviderSlotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSlot(slot: ProviderSlotEntity)

    @Query("UPDATE provider_slots SET isPrimary = CASE WHEN slotId = :slotId THEN 1 ELSE 0 END")
    suspend fun setPrimarySlot(slotId: String)

    @Query("DELETE FROM provider_slots WHERE slotId = :slotId")
    suspend fun deleteSlot(slotId: String)
}

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent_states ORDER BY agentId ASC")
    fun observeAgents(): Flow<List<AgentStateEntity>>

    @Query("SELECT * FROM agent_states")
    suspend fun getAgentsOnce(): List<AgentStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgent(agent: AgentStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgents(agents: List<AgentStateEntity>)

    @Query("SELECT * FROM agent_collaborations ORDER BY timestamp DESC LIMIT 150")
    fun observeCollaborations(): Flow<List<AgentCollaborationEntity>>

    @Query("SELECT * FROM agent_collaborations ORDER BY timestamp DESC LIMIT 150")
    suspend fun getCollaborationsOnce(): List<AgentCollaborationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollaboration(log: AgentCollaborationEntity): Long
}

@Dao
interface MarketplaceToolDao {
    @Query("SELECT * FROM marketplace_tools ORDER BY isInstalled DESC, name ASC")
    fun observeTools(): Flow<List<MarketplaceToolEntity>>

    @Query("SELECT * FROM marketplace_tools")
    suspend fun getToolsOnce(): List<MarketplaceToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTool(tool: MarketplaceToolEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTools(tools: List<MarketplaceToolEntity>)

    @Query("DELETE FROM marketplace_tools WHERE toolId = :toolId AND isBuiltIn = 0")
    suspend fun deleteCustomTool(toolId: String)
}

@Dao
interface ResearchDao {
    @Query("SELECT * FROM research_entries ORDER BY timestamp DESC LIMIT 150")
    fun observeResearch(): Flow<List<ResearchEntryEntity>>

    @Query("SELECT * FROM research_entries ORDER BY timestamp DESC LIMIT 150")
    suspend fun getResearchOnce(): List<ResearchEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResearch(entry: ResearchEntryEntity): Long

    @Query("SELECT * FROM self_improvement_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeImprovementLogs(): Flow<List<SelfImprovementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImprovementLog(log: SelfImprovementEntity): Long
}

@Dao
interface SystemRecordsDao {
    @Query("SELECT * FROM notification_records ORDER BY timestamp DESC LIMIT 100")
    fun observeNotifications(): Flow<List<NotificationRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(record: NotificationRecordEntity): Long

    @Query("DELETE FROM notification_records")
    suspend fun clearNotifications()

    @Query("SELECT * FROM reminders ORDER BY isCompleted ASC, triggerAtMillis ASC")
    fun observeReminders(): Flow<List<ReminderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReminder(reminder: ReminderEntity): Long

    @Query("UPDATE reminders SET isCompleted = 1 WHERE id = :id")
    suspend fun markReminderCompleted(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("SELECT * FROM build_records ORDER BY timestamp DESC LIMIT 50")
    fun observeBuildRecords(): Flow<List<BuildRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBuildRecord(record: BuildRecordEntity): Long

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT 200")
    fun observeAuditLogs(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: AuditLogEntity): Long
}
