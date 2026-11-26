package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.FeatureStatus
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.PendingUserConfirmation

@Composable
fun NeuralCard(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)
        ),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun StatusPill(
    status: String,
    modifier: Modifier = Modifier
) {
    val label = if (status == FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.name) {
        FeatureStatus.IMPLEMENTED_NOT_DEVICE_VERIFIED.displayLabel
    } else {
        status.replace("_", " ").uppercase()
    }
    val upper = status.uppercase()
    val (bg, fg) = when {
        upper in setOf("ACTIVE", "VERIFIED", "SUCCESS", "COMPLETED", "PASSED", "READY") ->
            NeuralMint.copy(alpha = 0.16f) to NeuralMint
        upper in setOf(
            "PARTIAL", "WORKING", "CONNECTING", "RECONNECTING", "RUNNING",
            "LISTENING", "SPEAKING", "THINKING", "WAITING_APPROVAL", "PROPOSED",
            "WAITING_FOR_CONFIRMATION", "WAITING_FOR_USER", "SUBMITTED_UNVERIFIED"
        ) ->
            StatusAmber.copy(alpha = 0.18f) to StatusAmber
        upper in setOf("BLOCKED", "FAILED", "ERROR", "DENIED", "HALTED", "STOPPED", "EMERGENCY_STOP", "ROLLED_BACK") ->
            StatusCrimson.copy(alpha = 0.18f) to StatusCrimson
        else -> NeuralCyan.copy(alpha = 0.15f) to NeuralCyan
    }
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(50),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = 8.dp)) { trailing() }
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    accent: Color = NeuralCyan,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small
            )
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}

@Composable
fun ConfirmActionDialog(
    pending: PendingUserConfirmation?,
    onDismiss: () -> Unit
) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pending.title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(pending.description, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = pending.onConfirm,
                modifier = Modifier.testTag("confirm_sensitive_action_btn")
            ) {
                Text("Authorize")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_sensitive_action_btn")
            ) {
                Text("Cancel")
            }
        }
    )
}
