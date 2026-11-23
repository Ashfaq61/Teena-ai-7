package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel

@Composable
fun ChatScreen(viewModel: TeenaViewModel) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingText by viewModel.chatStreamingText.collectAsStateWithLifecycle()
    val isLoading by viewModel.chatIsLoading.collectAsStateWithLifecycle()
    val chatError by viewModel.chatError.collectAsStateWithLifecycle()
    val attachedBitmap by viewModel.attachedImage.collectAsStateWithLifecycle()
    val attachedNote by viewModel.attachedDocNote.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) viewModel.attachUriDocument(uri)
    }

    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.attachUriDocument(uri)
    }

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Conversation tabs strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = { viewModel.createNewConversation() },
                label = { Text("New Chat") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = "New Conversation") },
                modifier = Modifier.testTag("chat_new_session_btn")
            )
            conversations.forEach { conv ->
                val selected = conv.id == activeId
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) NeuralCyan.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .clickable { viewModel.selectConversation(conv.id) }
                        .minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = conv.title.take(22),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) NeuralCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty() && !isLoading) {
                item {
                    NeuralCard {
                        Text(
                            "Welcome to Teena AI Brain",
                            style = MaterialTheme.typography.titleMedium,
                            color = NeuralCyan
                        )
                        Text(
                            "Speak or type in English, Urdu (اردو), or Roman Urdu. Attach images or PDFs for multimodal analysis, or issue device commands like 'torch on karo' or 'open WhatsApp'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "Summarize my device readiness",
                                "Torch on karo",
                                "آج کا موسم اور وقت کے مطابق ایک مختصر دعا لکھیں"
                            ).forEach { prompt ->
                                AssistChip(
                                    onClick = { viewModel.sendChatMessage(prompt) },
                                    label = { Text(prompt.take(26)) }
                                )
                            }
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                val isUser = msg.role == "user"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isUser) 18.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 18.dp
                        ),
                        color = if (isUser) {
                            NeuralViolet.copy(alpha = 0.28f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.widthIn(max = 340.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = msg.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!isUser && msg.providerUsed.isNotBlank()) {
                                Text(
                                    text = "${msg.providerUsed} • ${msg.modelUsed} • ${msg.detectedLanguage} • ${msg.latencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeuralCyan
                                )
                            }
                        }
                    }
                }
            }

            if (streamingText.isNotBlank()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 340.dp)
                    ) {
                        Text(
                            text = streamingText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            } else if (isLoading) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Teena is thinking...", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (!chatError.isNullOrBlank()) {
                item {
                    NeuralCard(borderColor = StatusCrimson) {
                        Text(
                            "AI Provider Error",
                            style = MaterialTheme.typography.titleSmall,
                            color = StatusCrimson,
                            fontWeight = FontWeight.Bold
                        )
                        Text(chatError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Attachment preview bar
        if (attachedBitmap != null || !attachedNote.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachedBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Attached preview",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Text(
                    text = attachedNote?.lines()?.firstOrNull() ?: "Image attached",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.clearAttachment() }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove attachment")
                }
            }
        }

        // Input Composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.testTag("chat_attach_photo_btn")
            ) {
                Icon(Icons.Default.Image, contentDescription = "Pick Image")
            }
            IconButton(
                onClick = {
                    docPicker.launch(arrayOf("application/pdf", "text/*", "application/json"))
                },
                modifier = Modifier.testTag("chat_attach_doc_btn")
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = "Pick PDF or Document")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask in English, Urdu, or Roman Urdu...") },
                maxLines = 3,
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input_field")
            )
            IconButton(
                onClick = {
                    val msg = input
                    input = ""
                    viewModel.sendChatMessage(msg)
                },
                enabled = input.isNotBlank() && !isLoading,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("chat_send_btn")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send Message",
                    tint = if (input.isNotBlank()) NeuralCyan else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
