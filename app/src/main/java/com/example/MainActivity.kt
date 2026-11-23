package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.configureAppCheck
import com.example.ui.screens.AboutTeenaScreen
import com.example.ui.screens.AgentsAndCollabScreen
import com.example.ui.screens.ApiManagerScreen
import com.example.ui.screens.ApkBuildCenterScreen
import com.example.ui.screens.AutomationAndDeviceScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ConfirmActionDialog
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HubDirectoryScreen
import com.example.ui.screens.MarketplaceAndSelfImproveScreen
import com.example.ui.screens.MemoryAndHistoryScreen
import com.example.ui.screens.NotificationsAndRemindersScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.ResearchCenterScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VoiceAssistantScreen
import com.example.ui.theme.TeenaTheme
import com.example.ui.viewmodel.TeenaViewModel

private data class TopNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Mandatory App Check configuration with launch intent before UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            val viewModel: TeenaViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            TeenaTheme(darkTheme = settings.darkTheme) {
                TeenaRootScaffold(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeenaRootScaffold(viewModel: TeenaViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "dashboard"

    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val pendingConfirm by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(banner) {
        val msg = banner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
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
        "agents" -> "Multi-Agent Network"
        "hub" -> "Teena Systems Hub"
        "automation" -> "Automation & Device"
        "marketplace" -> "Marketplace & Self-Code"
        "research" -> "Research Center"
        "api_manager" -> "API Manager"
        "memory" -> "Memory & History"
        "build_center" -> "APK Build Center"
        "notifications" -> "Notifications & Tasks"
        "permissions" -> "Permissions"
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
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
                        composable("chat") { ChatScreen(viewModel) }
                        composable("voice") { VoiceAssistantScreen(viewModel) }
                        composable("agents") { AgentsAndCollabScreen(viewModel) }
                        composable("hub") { HubDirectoryScreen(onNavigate = ::navigateTo) }
                        composable("automation") {
                            AutomationAndDeviceScreen(viewModel, onNavigateToChat = { navigateTo("chat") })
                        }
                        composable("marketplace") { MarketplaceAndSelfImproveScreen(viewModel) }
                        composable("research") { ResearchCenterScreen(viewModel) }
                        composable("api_manager") { ApiManagerScreen(viewModel) }
                        composable("memory") { MemoryAndHistoryScreen(viewModel) }
                        composable("build_center") { ApkBuildCenterScreen(viewModel) }
                        composable("notifications") { NotificationsAndRemindersScreen(viewModel) }
                        composable("permissions") { PermissionsScreen(viewModel) }
                        composable("settings") { SettingsScreen(viewModel) }
                        composable("about") { AboutTeenaScreen(viewModel) }
                    }
                }
            }
        }
    }
}
