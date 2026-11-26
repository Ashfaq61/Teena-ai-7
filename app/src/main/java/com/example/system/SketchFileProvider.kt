package com.example.system

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

data class SketchPoint(
    val xFraction: Float,
    val yFraction: Float
)

data class SketchStroke(
    val points: List<SketchPoint>,
    val colorArgb: Int,
    val strokeWidthPx: Float
)

data class SavedSketchResult(
    val file: File,
    val contentUri: Uri,
    val shareIntent: Intent
)

class SketchFileProvider : FileProvider() {

    companion object {
        const val AUTHORITY_SUFFIX = ".sketchprovider"

        fun authority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"

        fun sketchesDirectory(context: Context): File =
            File(context.filesDir, "sketches").apply { mkdirs() }

        fun getUriForSketchFile(context: Context, file: File): Uri =
            getUriForFile(context, authority(context), file)

        fun renderStrokesToBitmap(
            strokes: List<SketchStroke>,
            widthPx: Int = 1080,
            heightPx: Int = 1080,
            backgroundColorArgb: Int = Color.WHITE
        ): Bitmap {
            val safeW = widthPx.coerceIn(64, 4096)
            val safeH = heightPx.coerceIn(64, 4096)
            val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(backgroundColorArgb)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            strokes.forEach { stroke ->
                val pts = stroke.points
                if (pts.isEmpty()) return@forEach
                paint.color = stroke.colorArgb
                paint.strokeWidth = stroke.strokeWidthPx.coerceIn(1f, 96f)
                if (pts.size == 1) {
                    val p = pts.first()
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(
                        p.xFraction.coerceIn(0f, 1f) * safeW,
                        p.yFraction.coerceIn(0f, 1f) * safeH,
                        (paint.strokeWidth / 2f).coerceAtLeast(2f),
                        paint
                    )
                    paint.style = Paint.Style.STROKE
                } else {
                    val path = Path()
                    val first = pts.first()
                    path.moveTo(
                        first.xFraction.coerceIn(0f, 1f) * safeW,
                        first.yFraction.coerceIn(0f, 1f) * safeH
                    )
                    for (i in 1 until pts.size) {
                        val pt = pts[i]
                        path.lineTo(
                            pt.xFraction.coerceIn(0f, 1f) * safeW,
                            pt.yFraction.coerceIn(0f, 1f) * safeH
                        )
                    }
                    canvas.drawPath(path, paint)
                }
            }
            return bitmap
        }

        fun saveSketchPng(
            context: Context,
            strokes: List<SketchStroke>,
            backgroundColorArgb: Int = Color.WHITE,
            filePrefix: String = "teena_sketch"
        ): Result<SavedSketchResult> = runCatching {
            val dir = sketchesDirectory(context)
            val cleanPrefix = filePrefix.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "teena_sketch" }
            val outFile = File(dir, "${cleanPrefix}_${System.currentTimeMillis()}.png")
            val bitmap = renderStrokesToBitmap(
                strokes = strokes,
                widthPx = 1080,
                heightPx = 1080,
                backgroundColorArgb = backgroundColorArgb
            )
            FileOutputStream(outFile).use { fos ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    throw IllegalStateException("Unable to encode sketch PNG.")
                }
            }
            val uri = getUriForSketchFile(context, outFile)
            val shareIntent = buildShareIntent(context, uri)
            SavedSketchResult(file = outFile, contentUri = uri, shareIntent = shareIntent)
        }

        fun buildShareIntent(context: Context, contentUri: Uri): Intent {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TEXT, "Created in Teena AI Sketch Pad")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(send, "Share Teena Sketch").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun listSavedSketches(context: Context): List<File> =
            runCatching {
                sketchesDirectory(context)
                    .listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
            }.getOrDefault(emptyList())

        fun deleteSketch(file: File): Boolean =
            runCatching { file.exists() && file.delete() }.getOrDefault(false)
    }
}
