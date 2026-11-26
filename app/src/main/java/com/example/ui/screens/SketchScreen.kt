package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.system.SketchFileProvider
import com.example.system.SketchPoint
import com.example.system.SketchStroke
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.viewmodel.TeenaViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SketchScreen(
    viewModel: TeenaViewModel,
    onNavigateToChat: () -> Unit = {}
) {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<SketchStroke>() }
    var activePoints by remember { mutableStateOf<List<SketchPoint>>(emptyList()) }
    var isEraser by remember { mutableStateOf(false) }
    var darkCanvas by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableStateOf(Color(0xFF0F172A)) }
    var strokeWidth by remember { mutableFloatStateOf(10f) }
    var savedFiles by remember { mutableStateOf(SketchFileProvider.listSavedSketches(context)) }
    var feedback by remember { mutableStateOf("") }
    var redrawTick by remember { mutableIntStateOf(0) }

    val canvasBackground = if (darkCanvas) Color(0xFF0B1220) else Color.White
    val activeDrawColor = if (isEraser) canvasBackground else selectedColor
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    val palette = listOf(
        Color(0xFF0F172A),
        NeuralCyan,
        NeuralViolet,
        NeuralMint,
        StatusCrimson,
        StatusAmber,
        Color.White
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("sketch_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            NeuralCard(borderColor = NeuralCyan.copy(alpha = 0.5f)) {
                SectionHeader(
                    title = "Teena Sketch Pad",
                    subtitle = "Draw diagrams or notes -> save PNG via SketchFileProvider or share/analyze",
                    trailing = { StatusPill("${strokes.size} STROKES") }
                )

                // Interactive drawing surface
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(canvasBackground)
                        .border(1.dp, NeuralCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .testTag("sketch_canvas_box")
                ) {
                    val widthPx = constraints.maxWidth.coerceAtLeast(1).toFloat()
                    val heightPx = constraints.maxHeight.coerceAtLeast(1).toFloat()

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(widthPx, heightPx, activeDrawColor, strokeWidth) {
                                detectTapGestures { offset ->
                                    val pt = SketchPoint(
                                        xFraction = (offset.x / widthPx).coerceIn(0f, 1f),
                                        yFraction = (offset.y / heightPx).coerceIn(0f, 1f)
                                    )
                                    strokes.add(
                                        SketchStroke(
                                            points = listOf(pt),
                                            colorArgb = activeDrawColor.toArgb(),
                                            strokeWidthPx = strokeWidth
                                        )
                                    )
                                    redrawTick++
                                }
                            }
                            .pointerInput(widthPx, heightPx, activeDrawColor, strokeWidth) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        activePoints = listOf(
                                            SketchPoint(
                                                xFraction = (start.x / widthPx).coerceIn(0f, 1f),
                                                yFraction = (start.y / heightPx).coerceIn(0f, 1f)
                                            )
                                        )
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val pt = SketchPoint(
                                            xFraction = (change.position.x / widthPx).coerceIn(0f, 1f),
                                            yFraction = (change.position.y / heightPx).coerceIn(0f, 1f)
                                        )
                                        activePoints = activePoints + pt
                                    },
                                    onDragEnd = {
                                        if (activePoints.isNotEmpty()) {
                                            strokes.add(
                                                SketchStroke(
                                                    points = activePoints,
                                                    colorArgb = activeDrawColor.toArgb(),
                                                    strokeWidthPx = strokeWidth
                                                )
                                            )
                                        }
                                        activePoints = emptyList()
                                        redrawTick++
                                    },
                                    onDragCancel = {
                                        activePoints = emptyList()
                                    }
                                )
                            }
                    ) {
                        val allStrokes = if (activePoints.isNotEmpty()) {
                            strokes + SketchStroke(activePoints, activeDrawColor.toArgb(), strokeWidth)
                        } else {
                            strokes
                        }
                        allStrokes.forEach { stroke ->
                            val pts = stroke.points
                            if (pts.isEmpty()) return@forEach
                            val strokeColor = Color(stroke.colorArgb)
                            if (pts.size == 1) {
                                val p = pts.first()
                                drawCircle(
                                    color = strokeColor,
                                    radius = (stroke.strokeWidthPx / 2f).coerceAtLeast(3f),
                                    center = Offset(p.xFraction * size.width, p.yFraction * size.height)
                                )
                            } else {
                                val path = Path().apply {
                                    moveTo(pts.first().xFraction * size.width, pts.first().yFraction * size.height)
                                    for (i in 1 until pts.size) {
                                        lineTo(pts[i].xFraction * size.width, pts[i].yFraction * size.height)
                                    }
                                }
                                drawPath(
                                    path = path,
                                    color = strokeColor,
                                    style = Stroke(
                                        width = stroke.strokeWidthPx,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    }
                }

                // Palette & tools
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    palette.forEach { color ->
                        val isSelected = !isEraser && selectedColor == color
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) NeuralCyan else Color.Gray.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    isEraser = false
                                    selectedColor = color
                                }
                        )
                    }
                }

                Text("Brush Width: ${strokeWidth.toInt()} px", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = strokeWidth,
                    onValueChange = { strokeWidth = it },
                    valueRange = 3f..36f
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isEraser,
                        onClick = { isEraser = false },
                        label = { Text("Pen") },
                        leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = isEraser,
                        onClick = { isEraser = true },
                        label = { Text("Eraser") }
                    )
                    FilterChip(
                        selected = darkCanvas,
                        onClick = { darkCanvas = !darkCanvas },
                        label = { Text(if (darkCanvas) "Dark Board" else "Light Board") }
                    )
                    OutlinedButton(
                        onClick = {
                            if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                        },
                        enabled = strokes.isNotEmpty(),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("sketch_undo_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Undo")
                    }
                    OutlinedButton(
                        onClick = {
                            strokes.clear()
                            activePoints = emptyList()
                            feedback = "Canvas cleared."
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("sketch_clear_btn")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Clear")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            SketchFileProvider.saveSketchPng(
                                context = context,
                                strokes = strokes.toList(),
                                backgroundColorArgb = canvasBackground.toArgb()
                            ).onSuccess { res ->
                                savedFiles = SketchFileProvider.listSavedSketches(context)
                                feedback = "Saved: ${res.file.name} (${res.contentUri})"
                            }.onFailure { e ->
                                feedback = "Save failed: ${e.localizedMessage}"
                            }
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("sketch_save_btn")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Save PNG")
                    }
                    FilledTonalButton(
                        onClick = {
                            SketchFileProvider.saveSketchPng(
                                context = context,
                                strokes = strokes.toList(),
                                backgroundColorArgb = canvasBackground.toArgb()
                            ).onSuccess { res ->
                                savedFiles = SketchFileProvider.listSavedSketches(context)
                                runCatching { context.startActivity(res.shareIntent) }
                                    .onSuccess { feedback = "Opened Share sheet for ${res.file.name}" }
                                    .onFailure { feedback = "Share failed: ${it.localizedMessage}" }
                            }.onFailure { e ->
                                feedback = "Share prep failed: ${e.localizedMessage}"
                            }
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("sketch_share_btn")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Save & Share")
                    }
                    OutlinedButton(
                        onClick = {
                            val bmp = SketchFileProvider.renderStrokesToBitmap(
                                strokes = strokes.toList(),
                                widthPx = 1024,
                                heightPx = 1024,
                                backgroundColorArgb = canvasBackground.toArgb()
                            )
                            viewModel.attachBitmap(bmp, "Hand-drawn sketch from Teena Sketch Pad")
                            onNavigateToChat()
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("sketch_send_ai_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Ask Teena AI")
                    }
                }

                if (feedback.isNotBlank()) {
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = NeuralMint,
                        modifier = Modifier.testTag("sketch_feedback_text")
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Saved Sketches (${savedFiles.size})",
                subtitle = "Stored safely inside app files/sketches & shared via SketchFileProvider"
            )
        }

        if (savedFiles.isEmpty()) {
            item {
                NeuralCard {
                    Text(
                        "No saved sketches yet. Draw above and tap Save PNG.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            items(savedFiles, key = { it.absolutePath }) { file: File ->
                NeuralCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.titleSmall, color = NeuralCyan)
                            Text(
                                "${file.length() / 1024} KB • ${dateFormat.format(Date(file.lastModified()))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            runCatching {
                                val uri = SketchFileProvider.getUriForSketchFile(context, file)
                                context.startActivity(SketchFileProvider.buildShareIntent(context, uri))
                            }.onFailure { feedback = "Share failed: ${it.localizedMessage}" }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share ${file.name}")
                        }
                        IconButton(onClick = {
                            SketchFileProvider.deleteSketch(file)
                            savedFiles = SketchFileProvider.listSavedSketches(context)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${file.name}")
                        }
                    }
                }
            }
        }
    }
}
