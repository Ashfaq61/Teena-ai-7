package com.example.system

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraMediaEngine(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()

    fun toggleFlashlight(enable: Boolean): Result<Boolean> {
        val cm = cameraManager ?: return Result.failure(IllegalStateException("CameraManager unavailable."))
        return runCatching {
            val torchCameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw IllegalStateException("No camera flashlight hardware found on this device.")

            cm.setTorchMode(torchCameraId, enable)
            _torchEnabled.value = enable
            enable
        }
    }

    fun getMediaVolumePercent(): Int {
        val am = audioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curr = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (curr * 100) / max
    }

    fun setMediaVolumePercent(percent: Int): Int {
        val am = audioManager ?: return 0
        val clamped = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * clamped) / 100
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return getMediaVolumePercent()
    }

    fun adjustVolume(raise: Boolean): Int {
        val am = audioManager ?: return 0
        val dir = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return getMediaVolumePercent()
    }

    fun sendMediaKey(keyCode: Int): Boolean {
        val am = audioManager ?: return false
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return true
    }

    fun canWriteSystemSettings(): Boolean {
        return Settings.System.canWrite(context)
    }

    fun openWriteSettingsPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun getSystemBrightnessPercent(): Int {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return ((raw.coerceIn(0, 255)) * 100) / 255
    }

    fun setSystemBrightnessPercent(percent: Int): Result<Int> {
        if (!canWriteSystemSettings()) {
            return Result.failure(SecurityException("WRITE_SETTINGS permission not granted by user."))
        }
        val clamped = percent.coerceIn(5, 100)
        val value = (clamped * 255) / 100
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return Result.success(clamped)
    }
}
