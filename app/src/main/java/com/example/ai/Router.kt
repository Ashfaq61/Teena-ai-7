package com.example.ai

import com.example.data.local.ProviderSlotEntity
import com.example.data.local.ProviderType
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class RoutedProvider(
    val providerType: ProviderType,
    val displayName: String
) {
    GEMINI(ProviderType.GEMINI_DIRECT, "Gemini"),
    GROQ(ProviderType.GROQ, "Groq"),
    OPENROUTER(ProviderType.OPENROUTER, "OpenRouter");

    companion object {
        fun fromProviderType(type: ProviderType): RoutedProvider? = when (type) {
            ProviderType.GEMINI_DIRECT -> GEMINI
            ProviderType.GROQ -> GROQ
            ProviderType.OPENROUTER -> OPENROUTER
            else -> null
        }
    }
}

enum class PromptComplexityTier {
    SIMPLE,
    MODERATE,
    HIGH
}

enum class RoutingCostStrategy {
    BALANCED,
    COST_FIRST,
    QUALITY_FIRST;

    companion object {
        fun fromString(raw: String?): RoutingCostStrategy {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: BALANCED
        }
    }
}

data class PromptEvaluation(
    val complexityScore: Double,
    val complexityTier: PromptComplexityTier,
    val estimatedInputTokens: Int,
    val estimatedOutputTokens: Int,
    val requiresVision: Boolean,
    val isCodeHeavy: Boolean,
    val isReasoningHeavy: Boolean,
    val matchedSignals: List<String>
)

data class ProviderCostProfile(
    val provider: RoutedProvider,
    val modelId: String,
    val inputCostPer1MTokensUsd: Double,
    val outputCostPer1MTokensUsd: Double,
    val estimatedCostUsd: Double,
    val isFreeTier: Boolean,
    val speedScore: Double,
    val capabilityScore: Double
)

data class RouterDecision(
    val selectedProvider: RoutedProvider,
    val orderedProviders: List<RoutedProvider>,
    val evaluation: PromptEvaluation,
    val costProfiles: Map<RoutedProvider, ProviderCostProfile>,
    val providerScores: Map<RoutedProvider, Double>,
    val strategyUsed: RoutingCostStrategy,
    val reason: String
)

/**
 * Evaluates a user prompt and dynamically selects the optimal AI provider
 * ([RoutedProvider.GEMINI], [RoutedProvider.GROQ], or [RoutedProvider.OPENROUTER])
 * using prompt complexity signals, capability fit, latency/speed, and estimated token cost metrics.
 */
class Router {

    fun evaluatePrompt(
        prompt: String,
        historyTurns: Int = 0,
        hasImageAttachment: Boolean = false
    ): PromptEvaluation {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()
        val words = if (trimmed.isEmpty()) emptyList() else trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val wordCount = words.size
        val charLen = trimmed.length

        val estimatedInputTokens = max(1, ceil(charLen / 4.0).toInt()) + (historyTurns.coerceAtLeast(0) * 55)
        val signals = mutableListOf<String>()
        var score = 0.08

        if (hasImageAttachment) {
            signals.add("multimodal_image")
            score += 0.55
        }

        // Length & context signals
        when {
            wordCount >= 140 || charLen >= 850 -> {
                score += 0.34
                signals.add("long_prompt")
            }
            wordCount >= 40 || charLen >= 220 -> {
                score += 0.22
                signals.add("medium_length")
            }
            wordCount <= 12 && charLen <= 70 -> {
                score += 0.04
                signals.add("short_query")
            }
            else -> {
                score += 0.12
            }
        }

        if (historyTurns >= 6) {
            score += 0.14
            signals.add("deep_context")
        } else if (historyTurns >= 3) {
            score += 0.07
        }

        val codeMatches = CODE_KEYWORDS.count { kw -> lower.contains(kw) }
        val hasCodeFence = trimmed.contains("```") || trimmed.contains("{") && trimmed.contains("}")
        val isCodeHeavy = hasCodeFence || codeMatches >= 2 || AiBrainEngine.detectWebsiteBanaoIntent(trimmed)
        if (isCodeHeavy) {
            score += if (hasCodeFence || codeMatches >= 3) 0.32 else 0.22
            signals.add("code_generation")
        } else if (codeMatches == 1) {
            score += 0.12
            signals.add("technical_term")
        }

        val reasoningMatches = REASONING_KEYWORDS.count { kw -> lower.contains(kw) }
        val hasMathOrLatex = trimmed.contains("$$") || trimmed.contains("\\frac") || trimmed.contains("\\sqrt")
        val isReasoningHeavy = reasoningMatches >= 2 || hasMathOrLatex ||
            (reasoningMatches == 1 && wordCount >= 18)
        if (isReasoningHeavy) {
            score += if (reasoningMatches >= 3 || hasMathOrLatex) 0.35 else 0.24
            signals.add("multi_step_reasoning")
        } else if (reasoningMatches == 1) {
            score += 0.12
            signals.add("analytical_cue")
        }

        val normalizedScore = score.coerceIn(0.0, 1.0)
        val tier = when {
            hasImageAttachment -> PromptComplexityTier.HIGH
            normalizedScore >= 0.62 -> PromptComplexityTier.HIGH
            normalizedScore >= 0.30 -> PromptComplexityTier.MODERATE
            else -> PromptComplexityTier.SIMPLE
        }

        val estimatedOutputTokens = when (tier) {
            PromptComplexityTier.SIMPLE -> 110
            PromptComplexityTier.MODERATE -> 360
            PromptComplexityTier.HIGH -> 950
        }

        return PromptEvaluation(
            complexityScore = normalizedScore,
            complexityTier = tier,
            estimatedInputTokens = estimatedInputTokens,
            estimatedOutputTokens = estimatedOutputTokens,
            requiresVision = hasImageAttachment,
            isCodeHeavy = isCodeHeavy,
            isReasoningHeavy = isReasoningHeavy,
            matchedSignals = signals
        )
    }

    fun estimateProviderCost(
        provider: RoutedProvider,
        modelId: String = "",
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int
    ): ProviderCostProfile {
        val m = modelId.trim().lowercase()
        val (inPer1M, outPer1M, speed, capability) = when (provider) {
            RoutedProvider.GROQ -> {
                if (m.contains("8b") || m.contains("instant") || m.contains("gemma")) {
                    PricingTier(0.05, 0.08, speed = 0.97, capability = 0.76)
                } else {
                    PricingTier(0.59, 0.79, speed = 0.95, capability = 0.83)
                }
            }

            RoutedProvider.OPENROUTER -> {
                when {
                    m.isBlank() || m.contains(":free") -> {
                        PricingTier(0.0, 0.0, speed = 0.78, capability = 0.85)
                    }
                    m.contains("opus") || m.contains("claude-3.5") || m.contains("claude-3.7") ||
                        m.contains("gpt-4o") && !m.contains("mini") || m.contains("o1") -> {
                        PricingTier(2.50, 10.00, speed = 0.74, capability = 0.94)
                    }
                    else -> {
                        PricingTier(0.15, 0.60, speed = 0.80, capability = 0.87)
                    }
                }
            }

            RoutedProvider.GEMINI -> {
                when {
                    m.contains("pro") -> {
                        PricingTier(1.25, 5.00, speed = 0.80, capability = 0.98)
                    }
                    m.contains("lite") -> {
                        PricingTier(0.075, 0.30, speed = 0.91, capability = 0.88)
                    }
                    else -> {
                        // Auto-probed Flash / balanced Gemini default
                        PricingTier(0.10, 0.40, speed = 0.89, capability = 0.95)
                    }
                }
            }
        }

        val estimatedUsd = ((estimatedInputTokens / 1_000_000.0) * inPer1M) +
            ((estimatedOutputTokens / 1_000_000.0) * outPer1M)

        return ProviderCostProfile(
            provider = provider,
            modelId = modelId.ifBlank { defaultModelLabel(provider) },
            inputCostPer1MTokensUsd = inPer1M,
            outputCostPer1MTokensUsd = outPer1M,
            estimatedCostUsd = estimatedUsd,
            isFreeTier = (inPer1M == 0.0 && outPer1M == 0.0),
            speedScore = speed,
            capabilityScore = capability
        )
    }

    fun route(
        prompt: String,
        availableProviders: List<RoutedProvider> = RoutedProvider.entries,
        historyTurns: Int = 0,
        hasImageAttachment: Boolean = false,
        strategy: RoutingCostStrategy = RoutingCostStrategy.BALANCED,
        slotModels: Map<RoutedProvider, String> = emptyMap(),
        primaryPreference: RoutedProvider? = null
    ): RouterDecision {
        val candidates = availableProviders.distinct().ifEmpty { RoutedProvider.entries }
        val eval = evaluatePrompt(prompt, historyTurns, hasImageAttachment)

        val profiles = candidates.associateWith { provider ->
            estimateProviderCost(
                provider = provider,
                modelId = slotModels[provider].orEmpty(),
                estimatedInputTokens = eval.estimatedInputTokens,
                estimatedOutputTokens = eval.estimatedOutputTokens
            )
        }

        val neededCapability = when (eval.complexityTier) {
            PromptComplexityTier.SIMPLE -> 0.65
            PromptComplexityTier.MODERATE -> 0.84
            PromptComplexityTier.HIGH -> 0.95
        }

        val (wCap, wCost, wSpeed) = when (strategy) {
            RoutingCostStrategy.COST_FIRST -> Triple(0.25, 0.60, 0.15)
            RoutingCostStrategy.QUALITY_FIRST -> Triple(0.70, 0.10, 0.20)
            RoutingCostStrategy.BALANCED -> when (eval.complexityTier) {
                PromptComplexityTier.SIMPLE -> Triple(0.15, 0.40, 0.45)
                PromptComplexityTier.MODERATE -> Triple(0.45, 0.35, 0.20)
                PromptComplexityTier.HIGH -> Triple(0.65, 0.20, 0.15)
            }
        }

        val rawScores = candidates.associateWith { provider ->
            val profile = profiles.getValue(provider)
            val deficit = max(0.0, neededCapability - profile.capabilityScore)
            val headroom = max(0.0, profile.capabilityScore - neededCapability)
            val capFit = (1.0 - deficit * 2.4 + headroom * 0.25).coerceIn(0.1, 1.1)

            val costEfficiency = if (profile.isFreeTier) {
                1.0
            } else {
                // Cost reference ~$0.001 per typical turn so $0.00 free and sub-cent flash/8b separate cleanly
                (1.0 - min(0.95, profile.estimatedCostUsd / 0.0012)).coerceIn(0.05, 0.97)
            }

            var total = (wCap * capFit) + (wCost * costEfficiency) + (wSpeed * profile.speedScore)

            if (eval.requiresVision) {
                total += if (provider == RoutedProvider.GEMINI) 0.85 else -0.75
            }
            if (primaryPreference == provider) {
                total += 0.005
            }
            total
        }

        val ordered = candidates.sortedByDescending { rawScores.getValue(it) }
        val selected = ordered.first()
        val chosenCost = profiles.getValue(selected)
        val costText = if (chosenCost.isFreeTier) {
            "FREE ($0.00)"
        } else {
            "~$${"%.5f".format(Locale.US, chosenCost.estimatedCostUsd)}"
        }

        val reason = buildString {
            append("${selected.displayName} selected [${eval.complexityTier.name}")
            if (eval.requiresVision) append(" + VISION")
            append(" | ${strategy.name} | est. $costText")
            if (eval.matchedSignals.isNotEmpty()) {
                append(" | signals=${eval.matchedSignals.joinToString(",")}")
            }
            append("]")
        }

        return RouterDecision(
            selectedProvider = selected,
            orderedProviders = ordered,
            evaluation = eval,
            costProfiles = profiles,
            providerScores = rawScores,
            strategyUsed = strategy,
            reason = reason
        )
    }

    /**
     * Orders database [ProviderSlotEntity] instances dynamically for chat execution while
     * preserving Custom OpenAI and Offline Local fallbacks at the end.
     */
    fun orderSlotsForPrompt(
        prompt: String,
        enabledSlots: List<ProviderSlotEntity>,
        configuredCloudTypes: Set<ProviderType>,
        historyTurns: Int = 0,
        hasImageAttachment: Boolean = false,
        strategy: RoutingCostStrategy = RoutingCostStrategy.BALANCED
    ): Pair<List<ProviderSlotEntity>, RouterDecision> {
        val routedCandidates = enabledSlots.mapNotNull { slot ->
            val pType = runCatching { ProviderType.valueOf(slot.providerType) }.getOrNull() ?: return@mapNotNull null
            val rp = RoutedProvider.fromProviderType(pType) ?: return@mapNotNull null
            if (pType in configuredCloudTypes) rp to slot else null
        }

        val availableProviders = if (routedCandidates.isNotEmpty()) {
            routedCandidates.map { it.first }.distinct()
        } else {
            enabledSlots.mapNotNull { slot ->
                runCatching { ProviderType.valueOf(slot.providerType) }.getOrNull()
                    ?.let { RoutedProvider.fromProviderType(it) }
            }.distinct().ifEmpty { RoutedProvider.entries }
        }

        val slotModels = mutableMapOf<RoutedProvider, String>()
        enabledSlots.sortedByDescending { it.isPrimary }.forEach { slot ->
            val rp = runCatching { ProviderType.valueOf(slot.providerType) }.getOrNull()
                ?.let { RoutedProvider.fromProviderType(it) } ?: return@forEach
            slotModels.putIfAbsent(rp, slot.selectedModel)
        }
        val primaryPref = enabledSlots.firstOrNull { it.isPrimary }?.let { slot ->
            runCatching { ProviderType.valueOf(slot.providerType) }.getOrNull()
                ?.let { RoutedProvider.fromProviderType(it) }
        }

        val decision = route(
            prompt = prompt,
            availableProviders = availableProviders,
            historyTurns = historyTurns,
            hasImageAttachment = hasImageAttachment,
            strategy = strategy,
            slotModels = slotModels,
            primaryPreference = primaryPref
        )

        val priorityIndex = decision.orderedProviders.withIndex().associate { it.value.providerType to it.index }
        val sortedSlots = enabledSlots.sortedWith(
            compareBy<ProviderSlotEntity> { slot ->
                val pType = runCatching { ProviderType.valueOf(slot.providerType) }.getOrNull()
                when {
                    pType == null -> 999
                    pType in priorityIndex -> {
                        val base = priorityIndex.getValue(pType)
                        if (pType in configuredCloudTypes || configuredCloudTypes.isEmpty()) base else base + 100
                    }
                    pType == ProviderType.CUSTOM_OPENAI -> 200
                    pType == ProviderType.OFFLINE_LOCAL -> 300
                    else -> 250
                }
            }.thenByDescending { it.isPrimary }
        )
        return sortedSlots to decision
    }

    private data class PricingTier(
        val inputPer1M: Double,
        val outputPer1M: Double,
        val speed: Double,
        val capability: Double
    )

    companion object {
        private val CODE_KEYWORDS = listOf(
            "kotlin", "python", "java", "javascript", "typescript", "sql", "html", "css",
            "algorithm", "refactor", "debug", "stacktrace", "exception", "compile", "gradle",
            "regex", "compose", "coroutine", "api", "json", "schema", "function", "class"
        )

        private val REASONING_KEYWORDS = listOf(
            "explain", "why", "compare", "tradeoff", "trade-off", "architecture", "prove",
            "derive", "step by step", "step-by-step", "analyze", "analyse", "evaluate",
            "strategy", "design", "optimize", "complexity", "theorem", "critique", "pros and cons"
        )

        private fun defaultModelLabel(provider: RoutedProvider): String = when (provider) {
            RoutedProvider.GEMINI -> "gemini-flash-auto"
            RoutedProvider.GROQ -> "llama-3.3-70b-versatile"
            RoutedProvider.OPENROUTER -> "meta-llama/llama-3.3-70b-instruct:free"
        }
    }
}
