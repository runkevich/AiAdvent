package day8

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import kotlinx.serialization.json.*

object Day8TokenCounter {
    private val encoding by lazy { Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE) }

    fun countText(text: String): Int = encoding.encode(text).size()

    /** Estimate text and JSON framing; API usage remains the authoritative request count. */
    fun estimateJson(value: JsonElement): Int = when (value) {
        is JsonObject -> value.entries.sumOf { (key, child) -> countText(key) + estimateJson(child) } + value.size * 2
        is JsonArray -> value.sumOf(::estimateJson) + value.size * 2
        is JsonPrimitive -> if (value.isString) countText(value.content) else 1
        JsonNull -> 1
    }
}

data class Day8TurnStats(
    val createdAt: String,
    val status: String,
    val currentPromptTokens: Int,
    val historyTokensBefore: Int,
    val estimatedRequestTokens: Int,
    val historyTokensAfter: Int,
    val apiInputTokens: Int,
    val cachedInputTokens: Int,
    val apiOutputTokens: Int,
    val finalAnswerTokensEstimate: Int,
    val apiCalls: Int,
    val estimatedCostUsd: Double,
    val contextLimitTokens: Int,
    val error: String? = null,
) {
    val totalApiTokens: Int get() = apiInputTokens + apiOutputTokens
}

class Day8AgentFailure(val stats: Day8TurnStats, message: String) : RuntimeException(message)

fun Day8TurnStats.toJson(): JsonObject = buildJsonObject {
    put("createdAt", createdAt)
    put("status", status)
    put("currentPromptTokens", currentPromptTokens)
    put("historyTokensBefore", historyTokensBefore)
    put("estimatedRequestTokens", estimatedRequestTokens)
    put("historyTokensAfter", historyTokensAfter)
    put("apiInputTokens", apiInputTokens)
    put("cachedInputTokens", cachedInputTokens)
    put("apiOutputTokens", apiOutputTokens)
    put("finalAnswerTokensEstimate", finalAnswerTokensEstimate)
    put("apiCalls", apiCalls)
    put("totalApiTokens", totalApiTokens)
    put("estimatedCostUsd", estimatedCostUsd)
    put("contextLimitTokens", contextLimitTokens)
    error?.let { put("error", it) }
}

fun JsonObject.toDay8TurnStats() = Day8TurnStats(
    createdAt = this["createdAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    status = this["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    currentPromptTokens = int("currentPromptTokens"),
    historyTokensBefore = int("historyTokensBefore"),
    estimatedRequestTokens = int("estimatedRequestTokens"),
    historyTokensAfter = int("historyTokensAfter"),
    apiInputTokens = int("apiInputTokens"),
    cachedInputTokens = int("cachedInputTokens"),
    apiOutputTokens = int("apiOutputTokens"),
    finalAnswerTokensEstimate = int("finalAnswerTokensEstimate"),
    apiCalls = int("apiCalls"),
    estimatedCostUsd = this["estimatedCostUsd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    contextLimitTokens = int("contextLimitTokens"),
    error = this["error"]?.jsonPrimitive?.contentOrNull,
)

private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0
