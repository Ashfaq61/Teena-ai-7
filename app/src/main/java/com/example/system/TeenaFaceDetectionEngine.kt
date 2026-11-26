package com.example.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Ephemeral on-device face bounding box kept strictly in RAM for live UI overlay.
 * Never persisted to Room, DataStore, files, or uploaded over network.
 */
data class NormalizedFaceBox(
    val leftFraction: Float,
    val topFraction: Float,
    val rightFraction: Float,
    val bottomFraction: Float,
    val trackingId: Int? = null,
    val smilingProbability: Float? = null,
    val eyesOpenAverage: Float? = null
)

data class TransientFaceDetectionState(
    val enabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val faceCount: Int = 0,
    val faces: List<NormalizedFaceBox> = emptyList(),
    val statusNote: String = TeenaFaceDetectionEngine.PRIVACY_GUARANTEE
)

class TeenaFaceDetectionEngine(private val context: Context) {

    @Volatile
    private var detector: FaceDetector? = null

    fun hasExplicitCameraPermission(): Boolean =
        hasExplicitCameraPermission(context)

    fun createAnalyzer(
        isFrontCameraProvider: () -> Boolean,
        isEnabledProvider: () -> Boolean,
        onStateUpdated: (TransientFaceDetectionState) -> Unit
    ): ImageAnalysis.Analyzer {
        return OnDeviceFaceAnalyzer(
            isFrontCameraProvider = isFrontCameraProvider,
            isEnabledProvider = isEnabledProvider,
            onStateUpdated = onStateUpdated
        )
    }

    fun closeDetector() {
        runCatching { detector?.close() }
        detector = null
    }

    private fun getOrCreateDetector(): FaceDetector {
        val current = detector
        if (current != null) return current
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        return FaceDetection.getClient(options).also { detector = it }
    }

    private inner class OnDeviceFaceAnalyzer(
        private val isFrontCameraProvider: () -> Boolean,
        private val isEnabledProvider: () -> Boolean,
        private val onStateUpdated: (TransientFaceDetectionState) -> Unit
    ) : ImageAnalysis.Analyzer {

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            if (!hasExplicitCameraPermission()) {
                imageProxy.close()
                onStateUpdated(
                    TransientFaceDetectionState(
                        enabled = false,
                        permissionGranted = false,
                        faceCount = 0,
                        faces = emptyList(),
                        statusNote = "Blocked: explicit CAMERA permission required before face detection."
                    )
                )
                return
            }
            if (!isEnabledProvider()) {
                imageProxy.close()
                onStateUpdated(
                    TransientFaceDetectionState(
                        enabled = false,
                        permissionGranted = true,
                        faceCount = 0,
                        faces = emptyList(),
                        statusNote = PRIVACY_GUARANTEE
                    )
                )
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            runCatching {
                val rotation = imageProxy.imageInfo.rotationDegrees
                val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
                val frontCam = isFrontCameraProvider()
                val imgW = imageProxy.width
                val imgH = imageProxy.height

                getOrCreateDetector()
                    .process(inputImage)
                    .addOnSuccessListener { detectedFaces ->
                        val normalized = detectedFaces.map { face ->
                            mapMlKitFace(face, imgW, imgH, rotation, frontCam)
                        }
                        onStateUpdated(
                            TransientFaceDetectionState(
                                enabled = true,
                                permissionGranted = true,
                                faceCount = normalized.size,
                                faces = normalized,
                                statusNote = if (normalized.isEmpty()) {
                                    "Scanning on-device (0 faces) • No face data stored or uploaded"
                                } else {
                                    "${normalized.size} face(s) detected on-device • Transient RAM only"
                                }
                            )
                        )
                    }
                    .addOnFailureListener { e ->
                        onStateUpdated(
                            TransientFaceDetectionState(
                                enabled = true,
                                permissionGranted = true,
                                faceCount = 0,
                                faces = emptyList(),
                                statusNote = "On-device detector unavailable: ${e.localizedMessage ?: "model downloading"}"
                            )
                        )
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }.onFailure { e ->
                imageProxy.close()
                onStateUpdated(
                    TransientFaceDetectionState(
                        enabled = false,
                        permissionGranted = true,
                        faceCount = 0,
                        faces = emptyList(),
                        statusNote = "Face detection error: ${e.localizedMessage ?: "unknown"}"
                    )
                )
            }
        }
    }

    companion object {
        const val PRIVACY_GUARANTEE =
            "On-device ML Kit only after explicit CAMERA permission. Zero face data stored or uploaded."

        fun hasExplicitCameraPermission(context: Context): Boolean =
            runCatching {
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

        fun mapMlKitFace(
            face: Face,
            imageWidth: Int,
            imageHeight: Int,
            rotationDegrees: Int,
            isFrontCamera: Boolean
        ): NormalizedFaceBox {
            val leftEye = face.leftEyeOpenProbability
            val rightEye = face.rightEyeOpenProbability
            val eyesAvg = when {
                leftEye != null && rightEye != null -> (leftEye + rightEye) / 2f
                leftEye != null -> leftEye
                rightEye != null -> rightEye
                else -> null
            }
            val box = face.boundingBox
            return mapBoundingBoxToNormalized(
                boundingBoxLeft = box.left,
                boundingBoxTop = box.top,
                boundingBoxRight = box.right,
                boundingBoxBottom = box.bottom,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                rotationDegrees = rotationDegrees,
                isFrontCamera = isFrontCamera,
                trackingId = face.trackingId,
                smilingProbability = face.smilingProbability,
                eyesOpenAverage = eyesAvg
            )
        }

        fun mapBoundingBoxToNormalized(
            boundingBoxLeft: Int,
            boundingBoxTop: Int,
            boundingBoxRight: Int,
            boundingBoxBottom: Int,
            imageWidth: Int,
            imageHeight: Int,
            rotationDegrees: Int,
            isFrontCamera: Boolean,
            trackingId: Int? = null,
            smilingProbability: Float? = null,
            eyesOpenAverage: Float? = null
        ): NormalizedFaceBox {
            val normalizedRot = ((rotationDegrees % 360) + 360) % 360
            val swapAxes = normalizedRot == 90 || normalizedRot == 270
            val uprightW = (if (swapAxes) imageHeight else imageWidth).coerceAtLeast(1).toFloat()
            val uprightH = (if (swapAxes) imageWidth else imageHeight).coerceAtLeast(1).toFloat()

            val rawLeft = (boundingBoxLeft / uprightW).coerceIn(0f, 1f)
            val rawRight = (boundingBoxRight / uprightW).coerceIn(0f, 1f)
            val rawTop = (boundingBoxTop / uprightH).coerceIn(0f, 1f)
            val rawBottom = (boundingBoxBottom / uprightH).coerceIn(0f, 1f)

            val (l, r) = if (isFrontCamera) {
                (1f - rawRight).coerceIn(0f, 1f) to (1f - rawLeft).coerceIn(0f, 1f)
            } else {
                minOf(rawLeft, rawRight) to maxOf(rawLeft, rawRight)
            }
            return NormalizedFaceBox(
                leftFraction = l,
                topFraction = minOf(rawTop, rawBottom),
                rightFraction = r,
                bottomFraction = maxOf(rawTop, rawBottom),
                trackingId = trackingId,
                smilingProbability = smilingProbability,
                eyesOpenAverage = eyesOpenAverage
            )
        }
    }
}
