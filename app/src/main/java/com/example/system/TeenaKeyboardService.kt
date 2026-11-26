package com.example.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.core.TeenaServiceContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class TeenaKeyboardService : InputMethodService() {

    private val imeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var isCaps = false
    private var isSymbols = false
    private var isListening = false
    private var pendingAiSuggestion: String = ""

    private var statusTextView: TextView? = null
    private var aiSuggestionBtn: Button? = null
    private var micButton: Button? = null
    private var keyboardContainer: LinearLayout? = null

    companion object {
        fun isKeyboardEnabled(context: Context): Boolean {
            return runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    ?: return false
                val targetId = ComponentName(context, TeenaKeyboardService::class.java).flattenToShortString()
                imm.enabledInputMethodList.any {
                    it.id.equals(targetId, ignoreCase = true) ||
                        it.packageName == context.packageName
                }
            }.getOrDefault(false)
        }

        fun isKeyboardSelected(context: Context): Boolean {
            return runCatching {
                val current = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                ).orEmpty()
                val targetShort = ComponentName(context, TeenaKeyboardService::class.java).flattenToShortString()
                current.equals(targetShort, ignoreCase = true) || current.contains("TeenaKeyboardService")
            }.getOrDefault(false)
        }

        fun openInputMethodSettings(context: Context) {
            runCatching {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }

        fun showInputMethodPicker(context: Context) {
            runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
        }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1018"))
            setPadding(dp(6), dp(8), dp(6), dp(10))
        }

        // Top Teena AI + Mic bar
        val aiBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }

        val micBtn = createBarButton("Mic", Color.parseColor("#00E5FF"), Color.BLACK).apply {
            setOnClickListener { toggleVoiceInput() }
        }
        micButton = micBtn

        val aiBtn = createBarButton("Teena AI", Color.parseColor("#47F5B8"), Color.BLACK).apply {
            setOnClickListener { triggerAiSmartCompose() }
        }

        val statusTv = TextView(this).apply {
            text = "Teena Keyboard ready"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            setPadding(dp(8), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTextView = statusTv

        val appBtn = createBarButton("App", Color.parseColor("#1E293B"), Color.WHITE).apply {
            setOnClickListener {
                runCatching {
                    val intent = Intent(this@TeenaKeyboardService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
            }
        }

        aiBar.addView(micBtn)
        aiBar.addView(spacer(dp(6)))
        aiBar.addView(aiBtn)
        aiBar.addView(statusTv)
        aiBar.addView(appBtn)
        root.addView(aiBar)

        val suggestionRow = Button(this).apply {
            visibility = View.GONE
            isAllCaps = false
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = pillDrawable(Color.parseColor("#151D2B"), Color.parseColor("#00E5FF"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                val ic = currentInputConnection
                if (ic != null && pendingAiSuggestion.isNotBlank()) {
                    ic.commitText("$pendingAiSuggestion ", 1)
                    pendingAiSuggestion = ""
                    visibility = View.GONE
                    statusTextView?.text = "Inserted Teena AI text"
                }
            }
        }
        aiSuggestionBtn = suggestionRow
        root.addView(suggestionRow)

        val keysHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        keyboardContainer = keysHolder
        root.addView(keysHolder)
        rebuildKeysLayout()

        return root
    }

    private fun rebuildKeysLayout() {
        val holder = keyboardContainer ?: return
        holder.removeAllViews()

        val letterRows = if (isSymbols) {
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
                listOf("SYM", "*", "\"", "'", ":", ";", "!", "?", "DEL")
            )
        } else {
            val base = listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("SHIFT", "z", "x", "c", "v", "b", "n", "m", "DEL")
            )
            if (isCaps) {
                base.map { r -> r.map { k -> if (k.length == 1) k.uppercase(Locale.US) else k } }
            } else {
                base
            }
        }

        letterRows.forEach { rowKeys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            rowKeys.forEach { label ->
                val weight = when (label) {
                    "SHIFT", "DEL", "SYM" -> 1.45f
                    else -> 1f
                }
                val displayText = when (label) {
                    "SHIFT" -> if (isCaps) "CAPS" else "Shift"
                    "DEL" -> "⌫"
                    else -> label
                }
                val btn = Button(this).apply {
                    text = displayText
                    isAllCaps = false
                    typeface = Typeface.DEFAULT_BOLD
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.WHITE)
                    minHeight = dp(44)
                    minimumWidth = 0
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    background = pillDrawable(
                        fillColor = if (label in listOf("SHIFT", "DEL", "SYM")) {
                            Color.parseColor("#1E293B")
                        } else {
                            Color.parseColor("#151D2B")
                        },
                        strokeColor = Color.parseColor("#263247")
                    )
                    layoutParams = LinearLayout.LayoutParams(0, dp(46), weight).apply {
                        setMargins(dp(2), 0, dp(2), 0)
                    }
                    setOnClickListener { handleKeyPress(label) }
                }
                row.addView(btn)
            }
            holder.addView(row)
        }

        // Bottom spacebar + punctuation + enter row
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val bottomSpecs = listOf(
            (if (isSymbols) "ABC" else "?123") to 1.4f,
            "GLOBE" to 1.1f,
            "," to 0.9f,
            "SPACE" to 3.6f,
            "." to 0.9f,
            "ENTER" to 1.6f
        )
        bottomSpecs.forEach { (key, weight) ->
            val display = when (key) {
                "GLOBE" -> "🌐"
                "SPACE" -> "Teena Space"
                "ENTER" -> "↵"
                else -> key
            }
            val isAction = key == "ENTER"
            val btn = Button(this).apply {
                text = display
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (isAction) Color.BLACK else Color.WHITE)
                minHeight = dp(44)
                minimumWidth = 0
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = pillDrawable(
                    fillColor = if (isAction) Color.parseColor("#00E5FF") else Color.parseColor("#1A2436"),
                    strokeColor = Color.parseColor("#263247")
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(46), weight).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener { handleKeyPress(key) }
            }
            bottomRow.addView(btn)
        }
        holder.addView(bottomRow)
    }

    private fun handleKeyPress(key: String) {
        val ic = currentInputConnection
        when (key) {
            "SHIFT" -> {
                isCaps = !isCaps
                rebuildKeysLayout()
            }
            "?123", "ABC", "SYM" -> {
                isSymbols = !isSymbols
                rebuildKeysLayout()
            }
            "GLOBE" -> {
                showInputMethodPicker(this)
            }
            "DEL" -> {
                ic?.deleteSurroundingText(1, 0)
            }
            "SPACE" -> {
                ic?.commitText(" ", 1)
            }
            "ENTER" -> {
                if (ic != null) {
                    val handled = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) &&
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    if (!handled) ic.commitText("\n", 1)
                }
            }
            else -> {
                ic?.commitText(key, 1)
                if (isCaps && !isSymbols) {
                    isCaps = false
                    rebuildKeysLayout()
                }
            }
        }
    }

    private fun toggleVoiceInput() {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            statusTextView?.text = "Grant RECORD_AUDIO in Teena AI first"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusTextView?.text = "On-device speech recognizer unavailable"
            return
        }
        if (isListening) {
            runCatching { speechRecognizer?.stopListening() }
            isListening = false
            micButton?.text = "Mic"
            statusTextView?.text = "Stopped listening"
            return
        }

        runCatching {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                            micButton?.text = "Stop"
                            statusTextView?.text = "Listening..."
                        }

                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListening = false
                            micButton?.text = "Mic"
                        }

                        override fun onError(error: Int) {
                            isListening = false
                            micButton?.text = "Mic"
                            statusTextView?.text = "Mic ready (code $error)"
                        }

                        override fun onResults(results: Bundle?) {
                            isListening = false
                            micButton?.text = "Mic"
                            val spoken = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            if (spoken.isNotBlank()) {
                                currentInputConnection?.commitText("$spoken ", 1)
                                statusTextView?.text = "Dictated: ${spoken.take(28)}"
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speechRecognizer?.startListening(recognizerIntent)
        }.onFailure { err ->
            isListening = false
            micButton?.text = "Mic"
            statusTextView?.text = err.localizedMessage ?: "Mic start error"
        }
    }

    private fun triggerAiSmartCompose() {
        val before = runCatching {
            currentInputConnection?.getTextBeforeCursor(280, 0)?.toString().orEmpty().trim()
        }.getOrDefault("")
        statusTextView?.text = "Teena AI thinking..."
        imeScope.launch {
            val outcome = runCatching {
                val container = TeenaServiceContainer.getShared(applicationContext)
                if (container.emergencyStopController.isEmergencyStopped.value) {
                    statusTextView?.text = "Blocked: Emergency Stop active"
                    return@launch
                }
                val prompt = if (before.isBlank()) {
                    "Write one concise friendly reply greeting (max 15 words)."
                } else {
                    "Complete or polish this short message naturally (output only the suggested continuation/reply, max 22 words): \"$before\""
                }
                container.aiBrain.generateQuickAnalysisWithMeta(prompt).getOrThrow()
            }
            outcome.onSuccess { reply ->
                val cleaned = reply.text.trim().replace("\n", " ").take(160)
                pendingAiSuggestion = cleaned
                aiSuggestionBtn?.apply {
                    text = "Tap to insert: $cleaned"
                    visibility = View.VISIBLE
                }
                statusTextView?.text = "AI suggestion ready (${reply.modelUsed})"
            }.onFailure { err ->
                statusTextView?.text = err.localizedMessage?.take(40) ?: "Configure API key in Teena"
            }
        }
    }

    private fun createBarButton(label: String, fillColor: Int, textColor: Int): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textColor)
            minHeight = dp(36)
            minimumHeight = dp(36)
            minimumWidth = 0
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = pillDrawable(fillColor, Color.TRANSPARENT)
        }
    }

    private fun pillDrawable(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun spacer(widthPx: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(widthPx, 1)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        imeScope.cancel()
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        super.onDestroy()
    }
}
