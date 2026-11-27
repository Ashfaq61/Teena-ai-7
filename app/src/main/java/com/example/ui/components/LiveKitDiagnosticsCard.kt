package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai.LiveKitMediaState
import com.example.ui.screens.NeuralCard
import com.example.ui.screens.SectionHeader
import com.example.ui.screens.StatusPill
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson

@Composable
fun LiveKitDiagnosticsCard(
    mediaState: LiveKitMediaState,
    modifier: Modifier = Modifier
) {
    val qualityUpper = mediaState.networkQuality.uppercase()
    val (activeBars, qualityColor) = when (qualityUpper) {
        "EXCELLENT" -> 4 to NeuralMint
        "GOOD" -> 3 to NeuralCyan
        "POOR" -> 2 to StatusAmber
        "LOST" -> 1 to StatusCrimson
        else -> 0 to MaterialTheme.colorScheme.onSurfaceVariant
    }

    val participantCount = maxOf(mediaState.activeParticipantCount, mediaState.participants.size)

    NeuralCard(
        modifier = modifier.testTag("livekit_diagnostics_card"),
        borderColor = when (mediaState.connectionState) {
            "CONNECTED" -> NeuralMint.copy(alpha = 0.55f)
            "CONNECTING", "RECONNECTING" -> StatusAmber.copy(alpha = 0.55f)
            "ERROR" -> StatusCrimson.copy(alpha = 0.65f)
            else -> NeuralCyan.copy(alpha = 0.28f)
        }
    ) {
        SectionHeader(
            title = "LiveKit Room Diagnostics",
            subtitle = "Real-time WebRTC connection, signal quality & participant telemetry",
            trailing = {
                StatusPill(
                    mediaState.connectionState,
                    modifier = Modifier.testTag("livekit_diag_status_pill")
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Network Quality Bars + Label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NetworkCheck,
                    contentDescription = "Network quality",
                    tint = qualityColor,
                    modifier = Modifier.size(18.dp)
                )
                SignalQualityBars(activeBars = activeBars, activeColor = qualityColor)
                Column {
                    Text(
                        text = "Signal: $qualityUpper",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = qualityColor,
                        modifier = Modifier.testTag("livekit_diag_quality_label")
                    )
                    Text(
                        text = "Last event: ${mediaState.lastEventName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Active Participants Counter
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Groups,
                    contentDescription = "Active participants",
                    tint = NeuralCyan,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "$participantCount active",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeuralCyan,
                    modifier = Modifier.testTag("livekit_diag_participant_count")
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusPill(if (mediaState.micEnabled) "MIC: ON" else "MIC: OFF")
            StatusPill(if (mediaState.cameraEnabled) "CAM: ON" else "CAM: OFF")
            StatusPill(if (mediaState.screenShareEnabled) "SCREEN: LIVE" else "SCREEN: OFF")
        }

        Text(
            text = mediaState.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SignalQualityBars(
    activeBars: Int,
    activeColor: Color
) {
    val heights = listOf(6.dp, 10.dp, 14.dp, 18.dp)
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        heights.forEachIndexed { idx, barHeight ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .background(
                        color = if (idx < activeBars) activeColor else inactiveColor,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
        Spacer(Modifier.width(2.dp))
    }
}
