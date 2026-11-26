package com.example.system

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.data.local.NotificationRecordEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

class TeenaSpeechHelper(context: Context) {
    private val appContext = context.applicationContext
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var isReady: Boolean = false

    @Volatile
    private var pendingUtterance: Pair<String, Locale>? = null

    private fun ensureInitialized() {
        if (tts != null) return
        runCatching {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isReady = true
                    runCatching {
                        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                _isSpeaking.value = true
                            }

                            override fun onDone(utteranceId: String?) {
                                _isSpeaking.value = false
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                _isSpeaking.value = false
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                _isSpeaking.value = false
                            }
                        })
                    }
                    val queued = pendingUtterance
                    pendingUtterance = null
                    if (queued != null) {
                        speakInternal(queued.first, queued.second)
                    }
                } else {
                    isReady = false
                    _lastError.value = "Text-to-Speech engine unavailable (status $status)."
                    _isSpeaking.value = false
                }
            }
            tts = engine
        }.onFailure { err ->
            isReady = false
            _isSpeaking.value = false
            _lastError.value = err.localizedMessage ?: "TTS initialization failed"
        }
    }

    fun speak(text: String, locale: Locale = Locale.ENGLISH): Result<String> {
        val clean = text.trim()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("Nothing to read aloud."))
        }
        return runCatching {
            _lastError.value = null
            ensureInitialized()
            if (!isReady) {
                pendingUtterance = clean to locale
                _isSpeaking.value = true
                "Initializing speech engine and reading aloud..."
            } else {
                speakInternal(clean, locale)
                "Reading aloud via Teena TTS."
            }
        }
    }

    private fun speakInternal(text: String, locale: Locale) {
        val engine = tts ?: return
        runCatching {
            val langResult = engine.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.ENGLISH)
            }
            val utteranceId = "teena_${UUID.randomUUID().toString().take(8)}"
            _isSpeaking.value = true
            val code = engine.speak(text.take(1500), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (code != TextToSpeech.SUCCESS) {
                _isSpeaking.value = false
                _lastError.value = "TTS speak failed (code $code)."
            }
        }.onFailure { err ->
            _isSpeaking.value = false
            _lastError.value = err.localizedMessage ?: "TTS speak error"
        }
    }

    fun stop() {
        pendingUtterance = null
        _isSpeaking.value = false
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        pendingUtterance = null
        _isSpeaking.value = false
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isReady = false
    }

    companion object {
        fun buildWhatsAppReadAloudScript(records: List<NotificationRecordEntity>, maxItems: Int = 5): String {
            val whatsappOnly = records
                .filter { isWhatsAppPackageName(it.packageName) }
                .take(maxItems.coerceAtLeast(1))
            if (whatsappOnly.isEmpty()) {
                return "Koi WhatsApp notification capture nahi hui hai."
            }
            return buildString {
                append("Latest ${whatsappOnly.size} WhatsApp notification")
                if (whatsappOnly.size > 1) append("s")
                append(". ")
                whatsappOnly.forEachIndexed { idx, item ->
                    val sender = item.title.ifBlank { "Unknown sender" }
                    val msg = item.text.ifBlank { "No message text" }
                    append("Message ${idx + 1} from $sender: $msg. ")
                }
            }.trim()
        }

        fun isWhatsAppPackageName(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower == "com.whatsapp" || lower == "com.whatsapp.w4b" || lower.contains("whatsapp")
        }
    }
}
