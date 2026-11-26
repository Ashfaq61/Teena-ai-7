package com.example.ui.components

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

sealed interface MathTextSegment {
    data class PlainText(val text: String) : MathTextSegment
    data class LatexFormula(val latex: String, val isBlock: Boolean) : MathTextSegment
}

object LatexMessageParser {
    // Matches $$...$$, \[...\], \(...\), or non-currency inline $...$
    private val MATH_PATTERN = Regex(
        pattern = """\$\$(.+?)\$\$|\\\[(.+?)\\\]|\\\((.+?)\\\)|(?<!\\)\$(?!\s)([^$\n]+?)(?<!\s)\$""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val LIKELY_CURRENCY = Regex("""^\d+(?:[.,]\d+)?(?:\s*(?:usd|eur|pkr|inr|gbp|k|m|b))?$""", RegexOption.IGNORE_CASE)

    fun containsLatex(input: String): Boolean {
        if (input.isBlank()) return false
        return parseSegments(input).any { it is MathTextSegment.LatexFormula }
    }

    fun parseSegments(input: String): List<MathTextSegment> {
        if (input.isEmpty()) return emptyList()
        val segments = mutableListOf<MathTextSegment>()
        var cursor = 0

        for (match in MATH_PATTERN.findAll(input)) {
            val blockDollar = match.groups[1]?.value
            val blockBracket = match.groups[2]?.value
            val inlineParen = match.groups[3]?.value
            val inlineDollar = match.groups[4]?.value

            val isBlock = blockDollar != null || blockBracket != null
            val rawFormula = (blockDollar ?: blockBracket ?: inlineParen ?: inlineDollar).orEmpty().trim()

            // Skip empty or plain currency values like "$25" or "$12.50" inside inline single-$
            val isCurrencyOnly = inlineDollar != null && LIKELY_CURRENCY.matches(rawFormula)
            if (rawFormula.isBlank() || isCurrencyOnly) {
                continue
            }

            if (match.range.first > cursor) {
                val plain = input.substring(cursor, match.range.first)
                if (plain.isNotEmpty()) {
                    appendPlainText(segments, plain)
                }
            }

            segments.add(MathTextSegment.LatexFormula(latex = rawFormula, isBlock = isBlock))
            cursor = match.range.last + 1
        }

        if (cursor < input.length) {
            val trailing = input.substring(cursor)
            if (trailing.isNotEmpty()) {
                appendPlainText(segments, trailing)
            }
        }

        if (segments.isEmpty()) {
            return listOf(MathTextSegment.PlainText(input))
        }
        return segments
    }

    private fun appendPlainText(list: MutableList<MathTextSegment>, text: String) {
        val last = list.lastOrNull()
        if (last is MathTextSegment.PlainText) {
            list[list.lastIndex] = MathTextSegment.PlainText(last.text + text)
        } else {
            list.add(MathTextSegment.PlainText(text))
        }
    }
}

@Composable
fun RichMathChatText(
    text: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val segments = remember(text) { LatexMessageParser.parseSegments(text) }
    val hasFormula = remember(segments) { segments.any { it is MathTextSegment.LatexFormula } }

    if (!hasFormula) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier.testTag("chat_rich_math_block"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is MathTextSegment.PlainText -> {
                    val trimmed = segment.text.trim('\n')
                    if (trimmed.isNotEmpty()) {
                        Text(
                            text = trimmed,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }
                }
                is MathTextSegment.LatexFormula -> {
                    LatexFormulaBox(
                        latex = segment.latex,
                        isBlock = segment.isBlock,
                        textColor = textColor,
                        modifier = Modifier.testTag("chat_latex_formula_$index")
                    )
                }
            }
        }
    }
}

@Composable
private fun LatexFormulaBox(
    latex: String,
    isBlock: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textSizePx = with(density) { (if (isBlock) 18.dp else 15.dp).toPx() }
    val colorArgb = textColor.toArgb()

    val drawableResult = remember(latex, textSizePx, colorArgb) {
        runCatching {
            JLatexMathAndroid.init(context.applicationContext)
            JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .padding(8)
                .color(colorArgb)
                .align(if (isBlock) JLatexMathDrawable.ALIGN_CENTER else JLatexMathDrawable.ALIGN_LEFT)
                .build()
        }
    }

    val drawable = drawableResult.getOrNull()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                RoundedCornerShape(8.dp)
            )
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_START
                    }
                },
                update = { imageView ->
                    imageView.setImageDrawable(drawable)
                }
            )
        } else {
            Text(
                text = latex,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = textColor
            )
        }
    }
}
