/** Central settings and published standard text-token rates for the Day 3 model. */
internal object Day3Config {
    val model: String = System.getenv("DAY3_OPENAI_MODEL")?.takeIf(String::isNotBlank) ?: "gpt-6-luna"
    // Reasoning can consume output budget before visible text is produced; keep this adjustable for cost control.
    val maxOutputTokens: Int = System.getenv("DAY3_MAX_OUTPUT_TOKENS")?.toIntOrNull()?.coerceIn(16, 128_000) ?: 25_000
    val reasoningEfforts: List<Day3ReasoningEffort> = listOf(Day3ReasoningEffort.LOW, Day3ReasoningEffort.MAX)

    // USD per million tokens. Centralized here so rates can be reviewed/updated in one place.
    val pricing = Day3TokenPricing(
        inputUsdPerMillion = 0.10,
        cachedInputUsdPerMillion = 0.01,
        cacheWriteUsdPerMillion = 0.125,
        outputUsdPerMillion = 0.50,
    )
}

internal data class Day3TokenPricing(
    val inputUsdPerMillion: Double,
    val cachedInputUsdPerMillion: Double,
    val cacheWriteUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
) {
    /** Uses metered usage counts; reasoning tokens are included in outputTokens by the API. */
    fun costUsd(inputTokens: Int, outputTokens: Int, cachedInputTokens: Int = 0, cacheWriteTokens: Int = 0): Double {
        val cached = cachedInputTokens.coerceIn(0, inputTokens)
        val cacheWrites = cacheWriteTokens.coerceIn(0, inputTokens - cached)
        val regularInput = inputTokens - cached - cacheWrites
        return (
            regularInput * inputUsdPerMillion +
                cached * cachedInputUsdPerMillion +
                cacheWrites * cacheWriteUsdPerMillion +
                outputTokens * outputUsdPerMillion
            ) / 1_000_000.0
    }
}
