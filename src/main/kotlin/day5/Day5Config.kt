package day5

/** Shared settings for comparable Day 5 Responses API requests. */
internal object Day5Config {
    const val api = "responses"
    const val reasoningEffort = "low"
    const val maxOutputTokens = 1_024

    val models: List<Day5ModelConfig> = listOf(
        Day5ModelConfig(
            id = "gpt-6-luna",
            displayName = "GPT-6 Luna",
            tier = Day5ModelTier.SMALL,
            pricing = Day5TokenPricing(
                inputUsdPerMillion = 0.10,
                cachedInputUsdPerMillion = 0.01,
                outputUsdPerMillion = 0.50,
            ),
        ),
        Day5ModelConfig(
            id = "gpt-6.1-sol",
            displayName = "GPT-6.1 Sol",
            tier = Day5ModelTier.MEDIUM,
            pricing = Day5TokenPricing(
                inputUsdPerMillion = 2.00,
                cachedInputUsdPerMillion = 0.10,
                outputUsdPerMillion = 10.00,
            ),
        ),
        Day5ModelConfig(
            id = "gpt-6-astra",
            displayName = "GPT-6 Astra",
            tier = Day5ModelTier.FLAGSHIP,
            pricing = Day5TokenPricing(
                inputUsdPerMillion = 10.00,
                cachedInputUsdPerMillion = 1.00,
                outputUsdPerMillion = 50.00,
            ),
        ),
    )

    fun findModel(id: String): Day5ModelConfig? = models.firstOrNull { it.id == id }
}

internal enum class Day5ModelTier {
    SMALL,
    MEDIUM,
    FLAGSHIP,
}

internal data class Day5ModelConfig(
    val id: String,
    val displayName: String,
    val tier: Day5ModelTier,
    val pricing: Day5TokenPricing,
)

/** USD per million metered tokens; reasoning tokens are included in output usage. */
internal data class Day5TokenPricing(
    val inputUsdPerMillion: Double,
    val cachedInputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
) {
    fun costUsd(inputTokens: Int, cachedInputTokens: Int, outputTokens: Int): Double {
        require(inputTokens >= 0 && cachedInputTokens >= 0 && outputTokens >= 0)
        require(cachedInputTokens <= inputTokens) { "Cached input tokens cannot exceed total input tokens." }
        val uncachedInputTokens = inputTokens - cachedInputTokens
        return (
            uncachedInputTokens * inputUsdPerMillion +
                cachedInputTokens * cachedInputUsdPerMillion +
                outputTokens * outputUsdPerMillion
            ) / 1_000_000.0
    }
}
