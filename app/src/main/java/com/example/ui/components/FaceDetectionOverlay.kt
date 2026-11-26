package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.system.TransientFaceDetectionState
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint

/**
 * Pure transient RAM overlay for CameraX previews.
 * Displays bounding boxes and live count without storing or uploading any face data.
 */
@Composable
fun FaceDetectionOverlay(
    state: TransientFaceDetectionState,
    modifier: Modifier = Modifier
) {
    if (!state.enabled || !state.permissionGranted) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("face_detection_overlay")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            state.faces.forEach { face ->
                val left = face.leftFraction * size.width
                val top = face.topFraction * size.height
                val w = (face.rightFraction - face.leftFraction).coerceAtLeast(0.02f) * size.width
                val h = (face.bottomFraction - face.topFraction).coerceAtLeast(0.02f) * size.height
                val boxColor = if ((face.smilingProbability ?: 0f) >= 0.65f) NeuralMint else NeuralCyan

                drawRect(
                    color = boxColor,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        val firstSmile = state.faces.firstOrNull()?.smilingProbability
        val badgeText = buildString {
            append("${state.faceCount} face(s) • On-Device")
            if (firstSmile != null) {
                append(" • Smile ${(firstSmile * 100).toInt()}%")
            }
        }
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall,
            color = NeuralMint,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
