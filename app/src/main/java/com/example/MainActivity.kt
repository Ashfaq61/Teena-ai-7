package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.IncomingShareBridge
import com.example.ui.screens.AboutTeenaScreen
import com.example.ui.screens.AgentsAndCollabScreen
import com.example.ui.screens.ApiManagerScreen
import com.example.ui.screens.ApkBuildCenterScreen
import com.example.ui.screens.AutomationAndDeviceScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ConfirmActionDialog
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DevStudioScreen
import com.example.ui.screens.HubDirectoryScreen
import com.example.ui.screens.IdentityAndPersonaScreen
import com.example.ui.screens.MapsScreen
import com.example.ui.screens.MarketplaceAndSelfImproveScreen
import com.example.ui.screens.MemoryAndHistoryScreen
import com.example.ui.screens.MusicStudioScreen
import com.example.ui.screens.NotificationsAndRemindersScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.ResearchCenterScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SketchScreen
import com.example.ui.screens.TriggersScreen
import com.example.ui.screens.VoiceAssistantScreen
import com.example.ui.screens.VoiceRoomsScreen
import com.example.ui.screens.WebsiteForgeScreen
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.TeenaTheme
import com.example.ui.viewmodel.TeenaViewModel

private data class TopNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {
    private var intentTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: TeenaViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            TeenaTheme(darkTheme = settings.darkTheme) {
                TeenaRootScaffold(
                    viewModel = viewModel,
                    shareRouteExtra = intent?.getStringExtra(IncomingShareBridge.EXTRA_SHARE_ROUTE),
                    shareTabExtra = intent?.getIntExtra(IncomingShareBridge.EXTRA_OPEN_AUTOMATION_TAB, -1),
                    shareEventTick = intentTick
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentTick++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeenaRootScaffold(
    viewModel: TeenaViewModel,
    shareRouteExtra: String? = null,
    shareTabExtra: Int? = null,
    shareEventTick: Int = 0
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "dashboard"

    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val pendingConfirm by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val pendingScreenConfirm by viewModel.pendingScreenConfirmation.collectAsStateWithLifecycle()
    val screenWatchState by viewModel.screenWatchState.collectAsStateWithLifecycle()
    val emergencyStopped by viewModel.isEmergencyStopped.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(banner) {
        val msg = banner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    LaunchedEffect(shareEventTick, shareRouteExtra, shareTabExtra) {
        val destRoute = viewModel.consumePendingIncomingShare(
            requestedRoute = shareRouteExtra,
            requestedTab = shareTabExtra?.takeIf { it >= 0 }
        )
        if (!destRoute.isNullOrBlank() && destRoute != currentRoute) {
            navController.navigate(destRoute) {
                launchSingleTop = true
            }
        }
    }

    val topDestinations = listOf(
        TopNavItem("dashboard", "Home", Icons.Default.Dashboard),
        TopNavItem("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
        TopNavItem("voice", "Voice", Icons.Default.GraphicEq),
        TopNavItem("agents", "Agents", Icons.Default.Psychology),
        TopNavItem("hub", "Hub", Icons.Default.Apps)
    )
    val isTopLevel = topDestinations.any { it.route == currentRoute }

    val routeTitle = when (currentRoute) {
        "dashboard" -> "Teena AI Command"
        "chat" -> "AI Chat"
        "voice" -> "Live Voice"
        "agents" -> "Agents & External AI"
        "hub" -> "Teena Systems Hub"
        "automation" -> "Automation & Device"
        "marketplace" -> "Marketplace & Pipeline"
        "research" -> "Research Center"
        "api_manager" -> "API Manager"
        "memory" -> "Memory & History"
        "dev_studio" -> "Teena Dev Studio"
        "build_center" -> "APK Build Center"
        "notifications" -> "Notifications & Tasks"
        "triggers" -> "Triggers & Keyboard"
        "permissions" -> "Permissions"
        "identity" -> "Teena Identity & Persona"
        "voice_rooms" -> "Voice Rooms & LiveKit"
        "music_studio" -> "Teena Music Studio"
        "website_forge" -> "Teena Website Forge"
        "maps" -> "Teena Interactive Maps"
        "sketch" -> "Teena Sketch Pad"
        "settings" -> "Settings"
        "about" -> "About Teena"
        else -> "Teena AI"
    }

    fun navigateTo(route: String) {
        if (currentRoute == route) return
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    if (!isTopLevel) {
        BackHandler { navController.popBackStack() }
    }

    ConfirmActionDialog(
        pending = pendingConfirm,
        onDismiss = { viewModel.dismissConfirmation() }
    )

    pendingScreenConfirm?.let { screenPending ->
        AlertDialog(
            onDismissRequest = { viewModel.respondToPendingScreenConfirmation(false) },
            title = { Text(screenPending.title, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "App: ${screenPending.targetApp.ifBlank { "Unknown" }} • Action: ${screenPending.actionType}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeuralCyan
                    )
                    Text(screenPending.exactActionDescription, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.respondToPendingScreenConfirmation(true) },
                    modifier = Modifier.testTag("screen_confirm_allow_btn")
                ) {
                    Text("Authorize")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.respondToPendingScreenConfirmation(false) },
                    modifier = Modifier.testTag("screen_confirm_cancel_btn")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(routeTitle, style = MaterialTheme.typography.titleLarge) },
                        navigationIcon = {
                            if (!isTopLevel) {
                                IconButton(
                                    onClick = { navController.popBackStack() },
                                    modifier = Modifier.testTag("top_back_btn")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate Back")
                                }
                            }
                        },
                        actions = {
                            if (emergencyStopped) {
                                Button(
                                    onClick = { viewModel.resumeFromEmergencyStop() },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeuralMint, contentColor = Color.Black),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .testTag("emergency_stop_btn")
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("RESUME", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.triggerEmergencyStop() },
                                    colors = ButtonDefaults.buttonColors(containerColor = StatusCrimson, contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .testTag("emergency_stop_btn")
                                ) {
                                    Icon(Icons.Default.StopCircle, contentDescription = "Emergency Stop", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("STOP", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                    if (emergencyStopped) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(StatusCrimson.copy(alpha = 0.22f))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "EMERGENCY STOP ACTIVE — Automation, agents, voice & external calls halted.",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusCrimson,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { viewModel.resumeFromEmergencyStop() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Resume", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else if (screenWatchState.isWatching) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(StatusAmber.copy(alpha = 0.20f))
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("screen_watching_banner"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = "Screen watching active",
                                tint = StatusAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Teena screen dekh rahi hai • Step ${screenWatchState.stepNumber}/${screenWatchState.maxSteps} (${screenWatchState.phase})",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusAmber,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { viewModel.stopScreenAgentGoal() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Stop", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        topDestinations.forEach { item ->
                            NavigationBarItem(
                                selected = currentRoute == item.route,
                                onClick = { navigateTo(item.route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag("nav_${item.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail {
                        topDestinations.forEach { item ->
                            NavigationRailItem(
                                selected = currentRoute == item.route,
                                onClick = { navigateTo(item.route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = "dashboard",
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable("dashboard") { DashboardScreen(viewModel, onNavigate = ::navigateTo) }
                        composable("chat") { ChatScreen(viewModel, onOpenWebsiteForge = { navigateTo("website_forge") }) }
                        composable("voice") { VoiceAssistantScreen(viewModel) }
                        composable("agents") { AgentsAndCollabScreen(viewModel) }
                        composable("hub") { HubDirectoryScreen(onNavigate = ::navigateTo) }
                        composable("automation") {
                            AutomationAndDeviceScreen(
                                viewModel = viewModel,
                                onNavigateToChat = { navigateTo("chat") },
                                onOpenMaps = { navigateTo("maps") }
                            )
                        }
                        composable("marketplace") { MarketplaceAndSelfImproveScreen(viewModel) }
                        composable("research") { ResearchCenterScreen(viewModel) }
                        composable("api_manager") { ApiManagerScreen(viewModel) }
                        composable("memory") { MemoryAndHistoryScreen(viewModel) }
                        composable("dev_studio") { DevStudioScreen(viewModel) }
                        composable("build_center") { ApkBuildCenterScreen(viewModel) }
                        composable("notifications") {
                            NotificationsAndRemindersScreen(viewModel, onOpenTriggers = { navigateTo("triggers") })
                        }
                        composable("triggers") { TriggersScreen(viewModel) }
                        composable("permissions") { PermissionsScreen(viewModel) }
                        composable("identity") { IdentityAndPersonaScreen(viewModel) }
                        composable("voice_rooms") { VoiceRoomsScreen(viewModel, onOpenSettings = { navigateTo("settings") }) }
                        composable("music_studio") { MusicStudioScreen(viewModel, onOpenApiManager = { navigateTo("api_manager") }) }
                        composable("website_forge") { WebsiteForgeScreen(viewModel, onOpenApiManager = { navigateTo("api_manager") }) }
                        composable("maps") { MapsScreen(viewModel) }
                        composable("sketch") { SketchScreen(viewModel, onNavigateToChat = { navigateTo("chat") }) }
                        composable("settings") { SettingsScreen(viewModel, onOpenIdentity = { navigateTo("identity") }) }
                        composable("about") { AboutTeenaScreen(viewModel) }
                    }
                }
            }
        }
    }
}
