package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ContactEntry(
    val name: String,
    val phoneNumber: String
)

data class CallLogSummary(
    val number: String,
    val cachedName: String,
    val typeLabel: String,
    val durationSec: Long,
    val timestamp: Long
)

data class LiveCallInfo(
    val number: String = "",
    val stateLabel: String = "IDLE",
    val isRinging: Boolean = false,
    val isActive: Boolean = false
)

class TeenaInCallService : InCallService() {
    companion object {
        @Volatile
        var currentCall: Call? = null
            private set

        private val _liveCallInfo = MutableStateFlow(LiveCallInfo())
        val liveCallInfo: StateFlow<LiveCallInfo> = _liveCallInfo.asStateFlow()

        fun answerActiveRingingCall(): Boolean {
            val call = currentCall ?: return false
            call.answer(0)
            return true
        }

        fun rejectOrDisconnectCall(): Boolean {
            val call = currentCall ?: return false
            if (call.state == Call.STATE_RINGING) {
                call.reject(false, null)
            } else {
                call.disconnect()
            }
            return true
        }
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            updateCallState(call, state)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        call.registerCallback(callback)
        updateCallState(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callback)
        if (currentCall == call) {
            currentCall = null
            _liveCallInfo.value = LiveCallInfo()
        }
    }

    private fun updateCallState(call: Call, state: Int) {
        val number = call.details?.handle?.schemeSpecificPart.orEmpty()
        val label = when (state) {
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "ON_HOLD"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "CONNECTING"
        }
        _liveCallInfo.value = LiveCallInfo(
            number = number,
            stateLabel = label,
            isRinging = state == Call.STATE_RINGING,
            isActive = state == Call.STATE_ACTIVE
        )
    }
}

class PhoneCallEngine(private val context: Context) {

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val emergencyNumbers = setOf("911", "112", "999", "15", "16", "1122")

    fun isDefaultDialer(): Boolean {
        return telecomManager?.defaultDialerPackage == context.packageName
    }

    fun isEmergencyNumber(number: String): Boolean {
        val normalized = number.filter { it.isDigit() }
        return normalized in emergencyNumbers
    }

    fun searchContacts(query: String): Result<List<ContactEntry>> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(SecurityException("READ_CONTACTS permission not granted."))
        }
        return runCatching {
            val list = mutableListOf<ContactEntry>()
            val selection = if (query.isBlank()) null else
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val args = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                selection,
                args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && list.size < 30) {
                    val name = cursor.getString(nameIdx).orEmpty()
                    val num = cursor.getString(numIdx).orEmpty()
                    if (name.isNotBlank() && num.isNotBlank()) {
                        list.add(ContactEntry(name, num))
                    }
                }
            }
            list.distinctBy { "${it.name}:${it.phoneNumber}" }
        }
    }

    fun getRecentCallLogs(limit: Int = 20): Result<List<CallLogSummary>> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(SecurityException("READ_CALL_LOG permission not granted."))
        }
        return runCatching {
            val logs = mutableListOf<CallLogSummary>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                while (cursor.moveToNext() && logs.size < limit) {
                    val typeLabel = when (cursor.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        else -> "Call"
                    }
                    logs.add(
                        CallLogSummary(
                            number = cursor.getString(numIdx).orEmpty(),
                            cachedName = cursor.getString(nameIdx).orEmpty(),
                            typeLabel = typeLabel,
                            durationSec = cursor.getLong(durIdx),
                            timestamp = cursor.getLong(dateIdx)
                        )
                    )
                }
            }
            logs
        }
    }

    @SuppressLint("MissingPermission")
    fun placeCall(phoneNumber: String): Result<String> {
        val clean = phoneNumber.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Phone number is empty."))
        val uri = Uri.fromParts("tel", clean, null)

        // Emergency numbers always open the system dialer safely
        if (isEmergencyNumber(clean)) {
            val dialIntent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(dialIntent)
            return Result.success("Opened system emergency dialer for $clean.")
        }

        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val tm = telecomManager
        return if (hasCallPerm && tm != null) {
            runCatching {
                tm.placeCall(uri, null)
                "Placed call to $clean via Android TelecomManager."
            }.recoverCatching {
                val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Initiated call to $clean via ACTION_CALL."
            }
        } else {
            val dialIntent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(dialIntent)
            Result.success("CALL_PHONE permission not granted; opened system dialer with $clean.")
        }
    }

    @SuppressLint("MissingPermission")
    fun answerIncomingCall(): Result<String> {
        if (TeenaInCallService.answerActiveRingingCall()) {
            return Result.success("Answered incoming call via TeenaInCallService.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return runCatching {
                @Suppress("DEPRECATION")
                telecomManager?.acceptRingingCall()
                "Triggered TelecomManager.acceptRingingCall()."
            }
        }
        return Result.failure(
            IllegalStateException("Requires ANSWER_PHONE_CALLS permission or Default Phone App role.")
        )
    }

    @SuppressLint("MissingPermission")
    fun rejectOrEndCall(): Result<String> {
        if (TeenaInCallService.rejectOrDisconnectCall()) {
            return Result.success("Ended call via TeenaInCallService.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return runCatching {
                @Suppress("DEPRECATION")
                val ended = telecomManager?.endCall() ?: false
                if (ended) "Ended call via TelecomManager.endCall()."
                else throw IllegalStateException("No active call ended.")
            }
        }
        return Result.failure(
            IllegalStateException("Requires ANSWER_PHONE_CALLS (API 28+) or Default Phone App role.")
        )
    }

    fun setSpeakerphone(enabled: Boolean): Boolean {
        val am = audioManager ?: return false
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = enabled
        return true
    }

    fun isSpeakerphoneOn(): Boolean {
        @Suppress("DEPRECATION")
        return audioManager?.isSpeakerphoneOn ?: false
    }
}
