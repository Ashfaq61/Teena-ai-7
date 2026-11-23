package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiBrainEngine
import com.example.ai.MultiAgentOrchestrator
import com.example.ai.OfflineCapabilitiesEngine
import com.example.core.security.KeystoreSecretVault
import com.example.core.security.SecurityAuditor
import com.example.data.local.FeatureStatus
import com.example.data.local.MemoryFactEntity
import com.example.data.local.ProviderSlotEntity
import com.example.data.local.ProviderType
import com.example.data.local.TeenaDatabase
import com.example.data.remote.ModelRegistry
import com.example.data.remote.TeenaNetworkClients
import com.example.system.PermissionStatusManager
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TeenaCoreUnitTest {

    private lateinit var context: Context
    private lateinit var db: TeenaDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TeenaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun multilingualDetectionAndOfflineCommandsWork() {
        val engine = OfflineCapabilitiesEngine(context)
        assertEquals("Urdu", engine.detectLanguage("ٹارچ آن کرو"))
        assertEquals("Roman Urdu", engine.detectLanguage("Bhai mera WhatsApp kholo"))
        assertEquals("English", engine.detectLanguage("Explain Kotlin coroutines"))

        val torchCmd = engine.evaluateLocalCommandOrKnowledge(
            "Torch chalao",
            emptyList(),
            emptyList()
        )
        assertTrue(torchCmd.handledLocally)
        assertEquals("TORCH_ON", torchCmd.actionKey)

        val memoryHit = engine.evaluateLocalCommandOrKnowledge(
            "What is my favorite editor?",
            listOf(MemoryFactEntity(category = "Preference", factKey = "favorite editor", factValue = "Android Studio")),
            emptyList()
        )
        assertTrue(memoryHit.handledLocally)
        assertTrue(memoryHit.replyText.contains("Android Studio"))
    }

    @Test
    fun securityAuditorBlocksDclAndRedactsSecrets() {
        val unsafeScan = SecurityAuditor.auditToolOrCode(
            name = "SuspiciousPlugin",
            sourceCodeOrSpec = "val loader = DexClassLoader(path); val key = \"gsk_1234567890abcdef\"",
            licenseSpdx = "MIT"
        )
        assertFalse(unsafeScan.passed)
        assertTrue(unsafeScan.score < 70)

        val safeScan = SecurityAuditor.auditToolOrCode(
            name = "WeatherSummary",
            sourceCodeOrSpec = """{"endpoint":"https://api.open-meteo.com/v1/forecast"}""",
            licenseSpdx = "Apache-2.0",
            targetUrl = "https://api.open-meteo.com/v1/forecast"
        )
        assertTrue(safeScan.passed)
        assertEquals(100, safeScan.score)

        val redacted = SecurityAuditor.redactSecrets("Token gsk_abcdefghijklmnop1234 and ghp_abcdefghijklmnop1234")
        assertFalse(redacted.contains("gsk_abcdefghijklmnop"))
        assertFalse(redacted.contains("ghp_abcdefghijklmnop"))
    }

    @Test
    fun vaultEncryptsAndMasksSecrets() {
        val vault = KeystoreSecretVault(context)
        val rawKey = "gsk_testSecretValue987654321"
        vault.saveSecret("test_slot", rawKey)
        assertTrue(vault.hasSecret("test_slot"))
        assertEquals(rawKey, vault.readSecret("test_slot"))
        val masked = vault.maskedPreview("test_slot")
        assertNotEquals(rawKey, masked)
        assertTrue(masked.contains("••••"))
        vault.deleteSecret("test_slot")
        assertFalse(vault.hasSecret("test_slot"))
    }

    @Test
    fun allSeventeenAgentsAreDefinedAndUnique() {
        assertEquals(17, MultiAgentOrchestrator.REQUIRED_AGENTS.size)
        val ids = MultiAgentOrchestrator.REQUIRED_AGENTS.map { it.first }.toSet()
        assertEquals(17, ids.size)
    }

    @Test
    fun roomMemoryCrudPersistsAccurately() = runTest {
        val id = db.memoryFactDao().insertMemory(
            MemoryFactEntity(category = "Decision", factKey = "Voice", factValue = "AOEDE")
        )
        assertTrue(id > 0)
        val all = db.memoryFactDao().getAllMemoriesOnce()
        assertEquals(1, all.size)
        assertEquals("AOEDE", all.first().factValue)
    }

    @Test
    fun modelRegistryFiltersBidiGenerateContentAndGenerateContentAccurately() {
        val registry = ModelRegistry(OkHttpClient())
        val samplePayload = """
            {
              "models": [
                {
                  "name": "models/text-embedding-004",
                  "displayName": "Text Embedding",
                  "supportedGenerationMethods": ["embedContent"],
                  "inputTokenLimit": 2048
                },
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "supportedGenerationMethods": ["generateContent", "countTokens"],
                  "inputTokenLimit": 1048576
                },
                {
                  "name": "models/gemini-2.5-flash-native-audio-preview",
                  "displayName": "Gemini 2.5 Flash Native Audio",
                  "supportedGenerationMethods": ["bidiGenerateContent", "generateContent"],
                  "inputTokenLimit": 131072
                }
              ]
            }
        """.trimIndent()

        val parsed = registry.parseModelsJson(samplePayload)
        assertEquals(3, parsed.size)

        val liveOnly = parsed.filter { it.supportsBidiGenerateContent }
        assertEquals(1, liveOnly.size)
        assertEquals("gemini-2.5-flash-native-audio-preview", liveOnly.first().shortId)

        val chatOnly = parsed.filter { it.supportsGenerateContent }
        assertEquals(2, chatOnly.size)
        assertFalse(chatOnly.any { it.shortId == "text-embedding-004" })
    }

    @Test
    fun aiBrainPurgesLegacyFirebaseSlotAndMarksMissingKeyBlocked() = runTest {
        val vault = KeystoreSecretVault(context)
        vault.deleteSecret(AiBrainEngine.SLOT_GEMINI_PRIMARY)

        // Seed legacy Firebase AI Logic slot to simulate upgrade
        db.providerSlotDao().upsertSlot(
            ProviderSlotEntity(
                slotId = "slot_gemini_ai_logic",
                providerType = "GEMINI_AI_LOGIC",
                label = "Gemini Built-In (Firebase AI Logic)",
                selectedModel = "gemini-3.8-flash",
                isPrimary = true
            )
        )

        val aiBrain = AiBrainEngine(
            db = db,
            vault = vault,
            network = TeenaNetworkClients(),
            offlineEngine = OfflineCapabilitiesEngine(context)
        )
        aiBrain.ensureDefaultProviderSlots()

        val slots = db.providerSlotDao().getSlotsOnce()
        assertFalse(slots.any { it.providerType == "GEMINI_AI_LOGIC" || it.slotId == "slot_gemini_ai_logic" })

        val primaryGemini = slots.first { it.slotId == AiBrainEngine.SLOT_GEMINI_PRIMARY }
        assertEquals(ProviderType.GEMINI_DIRECT.name, primaryGemini.providerType)
        assertEquals("BLOCKED", primaryGemini.lastTestStatus)
        assertEquals("Gemini API key missing", primaryGemini.lastTestMessage)
        assertFalse(aiBrain.hasPrimaryGeminiApiKey())
        assertNull(aiBrain.resolvePrimaryGeminiSlotAndKey().second)

        // Now store a key in encrypted vault and verify resolution from PRIMARY Gemini slot
        vault.saveSecret(AiBrainEngine.SLOT_GEMINI_PRIMARY, "AIzaSyTestKeyForPrimarySlot123")
        assertTrue(aiBrain.hasPrimaryGeminiApiKey())
        val (resolvedSlot, resolvedKey) = aiBrain.resolvePrimaryGeminiSlotAndKey()
        assertEquals(AiBrainEngine.SLOT_GEMINI_PRIMARY, resolvedSlot?.slotId)
        assertEquals("AIzaSyTestKeyForPrimarySlot123", resolvedKey)
        vault.deleteSecret(AiBrainEngine.SLOT_GEMINI_PRIMARY)
    }

    @Test
    fun orchestratorHealthMatrixGatesLiveVoiceOnRealConnection() = runTest {
        val vault = KeystoreSecretVault(context)
        vault.deleteSecret(AiBrainEngine.SLOT_GEMINI_PRIMARY)

        val offlineEngine = OfflineCapabilitiesEngine(context)
        val network = TeenaNetworkClients()
        val aiBrain = AiBrainEngine(db, vault, network, offlineEngine)
        val permManager = PermissionStatusManager(context)
        val orchestrator = MultiAgentOrchestrator(db, aiBrain, network, vault, permManager, offlineEngine)

        aiBrain.ensureDefaultProviderSlots()

        // Case 1: Missing Gemini key -> BLOCKED with "Gemini API key missing"
        val blockedHealth = orchestrator.evaluateFeatureHealthMatrix(isLiveVoiceConnected = false)
        val blockedLive = blockedHealth.first { it.id == "gemini_live" }
        assertEquals(FeatureStatus.BLOCKED, blockedLive.status)
        assertEquals("Gemini API key missing", blockedLive.reason)

        // Case 2: Key stored + RECORD_AUDIO granted, but WebSocket not connected -> PARTIAL (never ACTIVE)
        vault.saveSecret(AiBrainEngine.SLOT_GEMINI_PRIMARY, "AIzaSyReadyKey123456")
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        val partialHealth = orchestrator.evaluateFeatureHealthMatrix(isLiveVoiceConnected = false)
        val partialLive = partialHealth.first { it.id == "gemini_live" }
        assertEquals(FeatureStatus.PARTIAL, partialLive.status)

        // Case 3: Real WebSocket setupComplete connected -> ACTIVE
        val activeHealth = orchestrator.evaluateFeatureHealthMatrix(
            isLiveVoiceConnected = true,
            liveVoiceModel = "gemini-2.5-flash-native-audio-preview"
        )
        val activeLive = activeHealth.first { it.id == "gemini_live" }
        assertEquals(FeatureStatus.ACTIVE, activeLive.status)
        assertTrue(activeLive.reason.contains("gemini-2.5-flash-native-audio-preview"))

        vault.deleteSecret(AiBrainEngine.SLOT_GEMINI_PRIMARY)
    }
}
