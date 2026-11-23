package com.example.core

import android.content.Context
import com.example.ai.AiBrainEngine
import com.example.ai.MultiAgentOrchestrator
import com.example.ai.OfflineCapabilitiesEngine
import com.example.core.security.KeystoreSecretVault
import com.example.data.local.TeenaDatabase
import com.example.data.local.TeenaPreferences
import com.example.data.remote.TeenaNetworkClients
import com.example.system.ApkBuildEngine
import com.example.system.CameraMediaEngine
import com.example.system.DocumentKnowledgeEngine
import com.example.system.MapsLocationEngine
import com.example.system.PermissionStatusManager
import com.example.system.PhoneCallEngine
import com.example.system.SocialAutomationEngine

class TeenaServiceContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: TeenaDatabase by lazy { TeenaDatabase.getInstance(appContext) }
    val preferences: TeenaPreferences by lazy { TeenaPreferences(appContext) }
    val vault: KeystoreSecretVault by lazy { KeystoreSecretVault(appContext) }
    val networkClients: TeenaNetworkClients by lazy { TeenaNetworkClients() }
    val offlineEngine: OfflineCapabilitiesEngine by lazy { OfflineCapabilitiesEngine(appContext) }
    val permissionManager: PermissionStatusManager by lazy { PermissionStatusManager(appContext) }
    val phoneEngine: PhoneCallEngine by lazy { PhoneCallEngine(appContext) }
    val cameraMediaEngine: CameraMediaEngine by lazy { CameraMediaEngine(appContext) }
    val mapsEngine: MapsLocationEngine by lazy { MapsLocationEngine(appContext) }
    val socialEngine: SocialAutomationEngine by lazy { SocialAutomationEngine(appContext) }
    val documentEngine: DocumentKnowledgeEngine by lazy { DocumentKnowledgeEngine(appContext) }
    val apkBuildEngine: ApkBuildEngine by lazy { ApkBuildEngine(appContext) }

    val aiBrain: AiBrainEngine by lazy {
        AiBrainEngine(database, vault, networkClients, offlineEngine)
    }

    val orchestrator: MultiAgentOrchestrator by lazy {
        MultiAgentOrchestrator(
            db = database,
            aiBrain = aiBrain,
            network = networkClients,
            vault = vault,
            permissionManager = permissionManager,
            offlineEngine = offlineEngine
        )
    }
}
