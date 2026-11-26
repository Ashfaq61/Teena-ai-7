package com.example.system

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Optional Device Admin Receiver (direct flavor/mode).
 * Never auto-enabled; user must explicitly approve in Android Device Admin Settings.
 */
class TeenaDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        runCatching { super.onEnabled(context, intent) }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling Teena Device Admin removes optional screen-lock capability."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        runCatching { super.onDisabled(context, intent) }
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, TeenaDeviceAdminReceiver::class.java)

        fun isAdminActive(context: Context): Boolean =
            runCatching {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    ?: return false
                dpm.isAdminActive(componentName(context))
            }.getOrDefault(false)

        fun buildEnableAdminIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Optional: allows Teena AI to lock your screen when you explicitly request it."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun requestEnableAdmin(context: Context): Result<String> = runCatching {
            if (isAdminActive(context)) {
                "Teena Device Admin is already active."
            } else {
                context.startActivity(buildEnableAdminIntent(context))
                "Opened Android Device Admin confirmation prompt (user approval required)."
            }
        }

        fun disableAdmin(context: Context): Result<String> = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return@runCatching "DevicePolicyManager unavailable."
            val admin = componentName(context)
            if (dpm.isAdminActive(admin)) {
                dpm.removeActiveAdmin(admin)
                "Teena Device Admin disabled."
            } else {
                "Teena Device Admin was already inactive."
            }
        }

        fun lockScreenIfAuthorized(context: Context, featureEnabled: Boolean): Result<String> = runCatching {
            if (!featureEnabled) {
                throw IllegalStateException("Optional Device Admin feature is OFF in Teena Settings.")
            }
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: throw IllegalStateException("DevicePolicyManager unavailable on this device.")
            val admin = componentName(context)
            if (!dpm.isAdminActive(admin)) {
                throw IllegalStateException("Teena Device Admin is not active. Enable it manually in Settings first.")
            }
            dpm.lockNow()
            "Screen locked via user-enabled Teena Device Admin."
        }
    }
}
