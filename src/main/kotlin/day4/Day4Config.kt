package day4

/** Fixed generation settings and centrally maintained standard token prices. */
internal object Day4Config {
    const val model: String = "gpt-4.1"
    val temperatures: List<Double> = listOf(0.0, 0.7, 1.2)
    const val topP: Double = 1.0
    const val maxCompletionTokens: Int = 600

    // USD per million tokens; cached input is charged at the published cached-input rate.
    val pricing = Day4TokenPricing(
        model = model,
        inputUsdPerMillion = 2.00,
        cachedInputUsdPerMillion = 0.50,
        outputUsdPerMillion = 8.00,
    )
}

internal data class Day4TokenPricing(
    val model: String,
    val inputUsdPerMillion: Double,
    val cachedInputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
) {
    fun costUsd(inputTokens: Int, outputTokens: Int, cachedInputTokens: Int? = null): Double {
        val cached = (cachedInputTokens ?: 0).coerceIn(0, inputTokens)
        val regularInput = inputTokens - cached
        return (
            regularInput * inputUsdPerMillion +
                cached * cachedInputUsdPerMillion +
                outputTokens * outputUsdPerMillion
            ) / 1_000_000.0
    }
}
