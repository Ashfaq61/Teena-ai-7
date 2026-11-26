package com.example.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.MainActivity
import com.example.ai.SocialTaskKind
import com.example.core.IncomingShareBridge
import com.example.core.IncomingSharePayload
import com.example.core.ShareHandoffDestination
import com.example.core.TeenaServiceContainer
import com.example.system.SocialPlatform
import com.example.ui.screens.NeuralCard
import com.example.ui.screens.SectionHeader
import com.example.ui.screens.StatusPill
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.TeenaTheme
import kotlinx.coroutines.launch

data class ParsedIncomingShare(
    val subject: String = "",
    val text: String = "",
    val imageUris: List<Uri> = emptyList()
)

class ShareToTeenaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val parsed = extractShareFromIntent(intent)
        val container = TeenaServiceContainer.getShared(applicationContext)

        setContent {
            val settings by container.preferences.settingsFlow.collectAsStateWithLifecycle(
                initialValue = com.example.data.local.TeenaSettings()
            )
            TeenaTheme(darkTheme = settings.darkTheme) {
                ShareToTeenaScreen(
                    parsed = parsed,
                    onSendToChat = { finalText ->
                        IncomingShareBridge.publish(
                            IncomingSharePayload(
                                text = finalText,
                                subject = parsed.subject,
                                imageUris = parsed.imageUris.map { it.toString() },
                                destination = ShareHandoffDestination.CHAT_ASK
                            )
                        )
                        launchMainRoute("chat")
                    },
                    onStageSocial = { platform, kind, captionText ->
                        val firstImg = parsed.imageUris.firstOrNull()?.toString()
                        IncomingShareBridge.publish(
                            IncomingSharePayload(
                                text = captionText,
                                subject = parsed.subject,
                                imageUris = parsed.imageUris.map { it.toString() },
                                destination = ShareHandoffDestination.SOCIAL_DRAFT,
                                targetPlatform = platform,
                                targetTaskKind = kind
                            )
                        )
                        // Also pre-stage into SocialTaskEngine (stops at WAITING_FOR_CONFIRMATION or WAITING_FOR_USER — never auto-posts)
                        val scope = kotlinx.coroutines.MainScope()
                        scope.launch {
                            runCatching {
                                container.socialTaskEngine.startSocialTask(
                                    platform = platform,
                                    taskKind = kind,
                                    caption = captionText,
                                    mediaUriString = firstImg
                                )
                            }
                            launchMainRoute("automation", openTab = 1)
                        }
                    },
                    onStageWhatsApp = { recipient, messageText ->
                        IncomingShareBridge.publish(
                            IncomingSharePayload(
                                text = messageText,
                                subject = parsed.subject,
                                imageUris = parsed.imageUris.map { it.toString() },
                                destination = ShareHandoffDestination.WHATSAPP_DRAFT,
                                targetPlatform = SocialPlatform.WHATSAPP,
                                targetTaskKind = SocialTaskKind.WHATSAPP_MESSAGE,
                                recipientHint = recipient
                            )
                        )
                        val scope = kotlinx.coroutines.MainScope()
                        scope.launch {
                            runCatching {
                                container.socialTaskEngine.startWhatsAppTask(recipient, messageText)
                            }
                            launchMainRoute("automation", openTab = 1)
                        }
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun launchMainRoute(route: String, openTab: Int = 0) {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(IncomingShareBridge.EXTRA_SHARE_ROUTE, route)
            putExtra(IncomingShareBridge.EXTRA_OPEN_AUTOMATION_TAB, openTab)
        }
        startActivity(mainIntent)
        finish()
    }

    companion object {
        fun extractShareFromIntent(intent: Intent?): ParsedIncomingShare {
            if (intent == null) return ParsedIncomingShare()
            return runCatching {
                val action = intent.action
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
                val uris = buildList {
                    when (action) {
                        Intent.ACTION_SEND -> {
                            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                                add(it)
                            }
                        }
                        Intent.ACTION_SEND_MULTIPLE -> {
                            val list = IntentCompat.getParcelableArrayListExtra(
                                intent,
                                Intent.EXTRA_STREAM,
                                Uri::class.java
                            )
                            if (list != null) addAll(list.take(6))
                        }
                    }
                }
                ParsedIncomingShare(subject = subject, text = text, imageUris = uris)
            }.getOrElse {
                ParsedIncomingShare()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShareToTeenaScreen(
    parsed: ParsedIncomingShare,
    onSendToChat: (String) -> Unit,
    onStageSocial: (SocialPlatform, SocialTaskKind, String) -> Unit,
    onStageWhatsApp: (String, String) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var editableText by remember {
        mutableStateOf(
            listOf(parsed.subject, parsed.text).filter { it.isNotBlank() }.joinToString("\n\n")
        )
    }
    var selectedPlatform by remember { mutableStateOf(SocialPlatform.INSTAGRAM) }
    var selectedKind by remember { mutableStateOf(SocialTaskKind.CREATE_POST) }
    var recipientInput by remember { mutableStateOf("") }
    var isDispatching by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Share to Teena AI", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("share_close_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Share Sheet")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NeuralCard {
                SectionHeader(
                    title = "Shared Content Received",
                    subtitle = "Teena keeps shared items strictly user-controlled — never auto-sends or posts.",
                    trailing = {
                        StatusPill(if (parsed.imageUris.isNotEmpty() || editableText.isNotBlank()) "READY" else "EMPTY")
                    }
                )
                if (parsed.imageUris.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        parsed.imageUris.take(3).forEach { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = "Shared image preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(92.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        }
                    }
                    Text(
                        "${parsed.imageUris.size} shared image(s) attached via Android Share URI",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeuralCyan
                    )
                }
                OutlinedTextField(
                    value = editableText,
                    onValueChange = { editableText = it },
                    label = { Text("Shared text / caption / prompt") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_text_input")
                )
            }

            NeuralCard {
                SectionHeader(
                    title = "Send to Teena Chat",
                    subtitle = "Ask Teena to explain, summarize, translate, or build from this shared content"
                )
                Button(
                    onClick = {
                        if (!isDispatching) {
                            isDispatching = true
                            onSendToChat(editableText.ifBlank { "Is shared image/content ko samjhao." })
                        }
                    },
                    enabled = !isDispatching && (editableText.isNotBlank() || parsed.imageUris.isNotEmpty()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("share_to_chat_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Ask Teena in Chat", maxLines = 1, softWrap = false)
                }
            }

            NeuralCard {
                SectionHeader(
                    title = "Stage Social Post / Reel / Story",
                    subtitle = "Stages into Teena Social Task State Machine (requires explicit CONFIRM)"
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        SocialPlatform.INSTAGRAM,
                        SocialPlatform.FACEBOOK,
                        SocialPlatform.TIKTOK,
                        SocialPlatform.YOUTUBE,
                        SocialPlatform.TELEGRAM
                    ).forEach { plat ->
                        FilterChip(
                            selected = selectedPlatform == plat,
                            onClick = { selectedPlatform = plat },
                            label = { Text(plat.displayName, maxLines = 1, softWrap = false) }
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        SocialTaskKind.CREATE_POST,
                        SocialTaskKind.CREATE_REEL,
                        SocialTaskKind.CREATE_STORY
                    ).forEach { kind ->
                        FilterChip(
                            selected = selectedKind == kind,
                            onClick = { selectedKind = kind },
                            label = { Text(kind.displayLabel, maxLines = 1, softWrap = false) }
                        )
                    }
                }
                FilledTonalButton(
                    onClick = {
                        if (!isDispatching) {
                            isDispatching = true
                            scope.launch {
                                onStageSocial(
                                    selectedPlatform,
                                    selectedKind,
                                    editableText.ifBlank { "Shared via Teena AI" }
                                )
                            }
                        }
                    },
                    enabled = !isDispatching && (editableText.isNotBlank() || parsed.imageUris.isNotEmpty()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("share_stage_social_btn")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Stage ${selectedKind.displayLabel} (Needs Confirm)", maxLines = 1, softWrap = false)
                }
            }

            NeuralCard {
                SectionHeader(
                    title = "Stage WhatsApp Draft",
                    subtitle = "Resolves contact safely and waits at WAITING_FOR_CONFIRMATION"
                )
                OutlinedTextField(
                    value = recipientInput,
                    onValueChange = { recipientInput = it },
                    label = { Text("Contact name / relation (ammi, bhai) or phone") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_whatsapp_recipient_input")
                )
                OutlinedButton(
                    onClick = {
                        if (!isDispatching) {
                            isDispatching = true
                            scope.launch {
                                onStageWhatsApp(recipientInput, editableText)
                            }
                        }
                    },
                    enabled = !isDispatching && editableText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("share_stage_whatsapp_btn")
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Stage WhatsApp Task (Needs Confirm)", maxLines = 1, softWrap = false)
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
