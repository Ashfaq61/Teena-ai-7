package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.MultiAgentOrchestrator
import com.example.ai.OfflineCapabilitiesEngine
import com.example.core.security.KeystoreSecretVault
import com.example.core.security.SecurityAuditor
import com.example.data.local.MemoryFactEntity
import com.example.data.local.TeenaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
