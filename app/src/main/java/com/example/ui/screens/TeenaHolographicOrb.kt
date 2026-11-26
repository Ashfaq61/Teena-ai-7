package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ai.LiveVoiceState
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun personalityPalette(personalityId: String): Pair<Color, Color> = when (personalityId.lowercase()) {
    "dev_architect" -> NeuralCyan to NeuralViolet
    "desi_dost" -> StatusAmber to NeuralMint
    "executive_brief" -> NeuralViolet to NeuralCyan
    "calm_mentor" -> NeuralMint to NeuralCyan
    else -> NeuralCyan to NeuralMint
}

@Composable
fun TeenaHolographicOrb(
    voiceState: LiveVoiceState,
    personalityId: String,
    micLevel: Float,
    playbackLevel: Float,
    isLiveActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (personaPrimary, personaSecondary) = personalityPalette(personalityId)
    val stateAccent = when (voiceState) {
        LiveVoiceState.LISTENING -> personaPrimary
        LiveVoiceState.SPEAKING -> NeuralMint
        LiveVoiceState.THINKING, LiveVoiceState.CONNECTING, LiveVoiceState.RECONNECTING -> StatusAmber
        LiveVoiceState.ERROR, LiveVoiceState.BLOCKED -> StatusCrimson
        LiveVoiceState.DISCONNECTED -> personaSecondary
    }

    val targetEnergy = when (voiceState) {
        LiveVoiceState.SPEAKING -> playbackLevel.coerceIn(0.1f, 1f)
        LiveVoiceState.LISTENING -> micLevel.coerceIn(0.05f, 1f)
        LiveVoiceState.THINKING, LiveVoiceState.CONNECTING, LiveVoiceState.RECONNECTING -> 0.45f
        else -> 0.08f
    }
    val smoothEnergy by animateFloatAsState(
        targetValue = targetEnergy,
        animationSpec = tween(durationMillis = 90),
        label = "orb_energy"
    )

    val transition = rememberInfiniteTransition(label = "holographic_orb")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_phase"
    )
    val orbitDeg by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_rotation"
    )

    val actionDesc = if (isLiveActive) "Stop Gemini Live" else "Start Gemini Live"

    Box(
        modifier = modifier
            .size(184.dp)
            .clip(CircleShape)
            .semantics { contentDescription = actionDesc }
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("voice_orb_canvas"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f

            // Outer ambient halo driven by audio RMS energy
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        stateAccent.copy(alpha = 0.28f + 0.25f * smoothEnergy),
                        personaSecondary.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxR
                ),
                radius = maxR * (0.88f + 0.12f * smoothEnergy),
                center = center
            )

            // Reactive sinusoidal neural ring
            val wavePath = Path()
            val baseWaveR = maxR * (0.64f + 0.06f * smoothEnergy)
            val waveAmp = maxR * (0.015f + 0.085f * smoothEnergy)
            val steps = 72
            for (step in 0..steps) {
                val theta = (step.toFloat() / steps) * 2f * PI.toFloat()
                val ripple = sin(theta * 5f + phase * 2f) * 0.6f +
                    cos(theta * 3f - phase) * 0.4f
                val r = baseWaveR + ripple * waveAmp
                val x = center.x + r * cos(theta)
                val y = center.y + r * sin(theta)
                if (step == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            }
            wavePath.close()
            drawPath(
                path = wavePath,
                color = stateAccent.copy(alpha = 0.85f),
                style = Stroke(width = (2.5f + 2.5f * smoothEnergy).dp.toPx(), cap = StrokeCap.Round)
            )

            // Rotating dual holographic arc
            rotate(degrees = orbitDeg, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(personaPrimary, personaSecondary, Color.Transparent, personaPrimary)
                    ),
                    startAngle = 0f,
                    sweepAngle = 210f,
                    useCenter = false,
                    topLeft = Offset(center.x - maxR * 0.78f, center.y - maxR * 0.78f),
                    size = androidx.compose.ui.geometry.Size(maxR * 1.56f, maxR * 1.56f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Inner core orb
            val coreR = maxR * (0.44f + 0.07f * smoothEnergy)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        stateAccent.copy(alpha = 0.92f),
                        personaSecondary.copy(alpha = 0.35f)
                    ),
                    center = center,
                    radius = coreR
                ),
                radius = coreR,
                center = center
            )
        }

        Icon(
            imageVector = if (isLiveActive) Icons.Default.CallEnd else Icons.Default.Mic,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(44.dp)
        )
    }
}
