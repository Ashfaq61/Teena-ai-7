package com.example.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class LoadedDocument(
    val displayName: String,
    val mimeType: String,
    val textContent: String,
    val renderedPages: List<Bitmap> = emptyList(),
    val pageCount: Int = 0
)

class DocumentKnowledgeEngine(private val context: Context) {

    suspend fun readUriDocument(uri: Uri): Result<LoadedDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val name = queryFileName(uri) ?: "selected_document"

            when {
                mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalStateException("Unable to open PDF descriptor.")
                    val bitmaps = mutableListOf<Bitmap>()
                    var totalPages = 0
                    pfd.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            totalPages = renderer.pageCount
                            val pagesToRender = minOf(totalPages, 3)
                            for (i in 0 until pagesToRender) {
                                renderer.openPage(i).use { page ->
                                    val w = page.width.coerceAtMost(1240)
                                    val h = (page.height * (w.toFloat() / page.width.coerceAtLeast(1))).toInt()
                                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    Canvas(bmp).drawColor(Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    bitmaps.add(bmp)
                                }
                            }
                        }
                    }
                    LoadedDocument(
                        displayName = name,
                        mimeType = "application/pdf",
                        textContent = "PDF document '$name' loaded ($totalPages pages; ${bitmaps.size} pages rendered for Gemini multimodal OCR/analysis).",
                        renderedPages = bitmaps,
                        pageCount = totalPages
                    )
                }

                mime.startsWith("image/") -> {
                    val bmp = context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    } ?: throw IllegalStateException("Could not decode selected image.")
                    LoadedDocument(
                        displayName = name,
                        mimeType = mime,
                        textContent = "Image '$name' (${bmp.width}x${bmp.height}) loaded for visual understanding.",
                        renderedPages = listOf(scaleDownBitmap(bmp, 1280)),
                        pageCount = 1
                    )
                }

                else -> {
                    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream)).readText()
                    }.orEmpty().take(24000)
                    LoadedDocument(
                        displayName = name,
                        mimeType = mime.ifBlank { "text/plain" },
                        textContent = text.ifBlank { "File '$name' appears empty or binary." }
                    )
                }
            }
        }
    }

    fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream) ?: return@use null
                scaleDownBitmap(bmp, 1280)
            }
        }.getOrNull()
    }

    suspend fun writeBackupJsonToUri(uri: Uri, jsonPayload: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(jsonPayload.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not open backup output stream.")
                "Backup exported successfully (${jsonPayload.length} bytes)."
            }
        }

    suspend fun readBackupJsonFromUri(uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    BufferedReader(InputStreamReader(ins)).readText()
                } ?: throw IllegalStateException("Could not read selected backup file.")
            }
        }

    private fun queryFileName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun scaleDownBitmap(source: Bitmap, maxDim: Int): Bitmap {
        val ratio = minOf(maxDim.toFloat() / source.width, maxDim.toFloat() / source.height)
        if (ratio >= 1f) return source
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
