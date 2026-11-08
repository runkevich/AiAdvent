package day5

import day3.Day3ReasoningEffort
import day3.Day3ResponsesClient
import day3.Day3ResponsesResult
import day3.Day3ValidationStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Day5ExperimentServiceTest {
    @Test
    fun `calculates cost with cached and uncached input`() {
        val price = Day5TokenPricing(2.0, 0.1, 10.0)

        val cost = price.costUsd(inputTokens = 1_000_000, cachedInputTokens = 400_000, outputTokens = 100_000)

        assertEquals(2.24, cost, 1e-9)
    }

    @Test
    fun `reasoning tokens are already covered by output cost`() = runBlocking {
        val result = serviceWith(response(
            responseText = answerJson("200 PLN", "250 PLN less 20 percent is 200 PLN."),
            inputTokens = 100,
            outputTokens = 10,
            reasoningTokens = 7,
        )).runTest("gpt-6-luna", "shopping")

        val expected = (100 * 0.10 + 10 * 0.50) / 1_000_000.0
        assertEquals(7, result.reasoningTokens)
        assertEquals(expected, result.estimatedCostUsd!!, 1e-12)
    }

    @Test
    fun `keeps cached input usage and applies cached rate`() = runBlocking {
        val result = serviceWith(response(
            responseText = answerJson("200 PLN", "250 PLN less 20 percent is 200 PLN."),
            inputTokens = 100,
            cachedInputTokens = 40,
            outputTokens = 10,
        )).runTest("gpt-6-luna", "shopping")

        val expected = (60 * 0.10 + 40 * 0.01 + 10 * 0.50) / 1_000_000.0
        assertEquals(40, result.cachedInputTokens)
        assertEquals(expected, result.estimatedCostUsd!!, 1e-12)
    }

    @Test
    fun `does not invent token usage or cost when usage is missing`() = runBlocking {
        val result = serviceWith(response(
            responseText = answerJson("200 PLN", "250 PLN less 20 percent is 200 PLN."),
        )).runTest("gpt-6-luna", "shopping")

        assertNull(result.inputTokens)
        assertNull(result.cachedInputTokens)
        assertNull(result.outputTokens)
        assertNull(result.totalTokens)
        assertNull(result.estimatedCostUsd)
    }

    @Test
    fun `unknown model and task are rejected without calling gateway`() = runBlocking {
        var calls = 0
        val service = Day5ExperimentService(Day5ResponsesGateway { _, _ ->
            calls++
            error("Gateway must not be called for invalid selection")
        })

        val unknownModel = service.runTest("not-a-model", "shopping")
        val unknownTask = service.runTest("gpt-6-luna", "not-a-task")

        assertEquals(Day5FailureType.INVALID_SELECTION, unknownModel.failureType)
        assertEquals(Day3ValidationStatus.NEEDS_REVIEW, unknownModel.validationStatus)
        assertEquals(Day5FailureType.INVALID_SELECTION, unknownTask.failureType)
        assertEquals(Day3ValidationStatus.NEEDS_REVIEW, unknownTask.validationStatus)
        assertEquals(0, calls)
    }

    @Test
    fun `ambiguous model answer remains needs review`() = runBlocking {
        val result = serviceWith(response(
            responseText = answerJson("It may or may not follow.", "The facts do not determine this."),
            inputTokens = 30,
            outputTokens = 20,
        )).runTest("gpt-6-luna", "logic")

        assertEquals(Day3ValidationStatus.NEEDS_REVIEW, result.validationStatus)
        assertNull(result.failureType)
        assertNull(result.error)
        assertNotNull(result.validationDetails)
    }

    @Test
    fun `creative coffee shop task is available and requires manual review`() = runBlocking {
        val creativeTask = Day5TaskCatalog.find("coffee_shop_name")
        val result = serviceWith(response(
            responseText = answerJson("Тёплое зерно", "Название передаёт уют кофейни."),
            inputTokens = 35,
            outputTokens = 18,
        )).runTest("gpt-6-luna", "coffee_shop_name")

        assertNotNull(creativeTask)
        assertEquals("Creativity · Coffee shop name", creativeTask.category)
        assertEquals(Day3ValidationStatus.NEEDS_REVIEW, result.validationStatus)
        assertNull(result.failureType)
        assertTrue(result.validationDetails.contains("ручная оценка"))
    }

    @Test
    fun `keeps API incomplete parse and incorrect outcomes distinct`() = runBlocking {
        val apiError = serviceWith(response(
            responseText = null,
            apiStatus = 401,
            error = "Unauthorized",
        )).runTest("gpt-6-luna", "shopping")
        val incomplete = serviceWith(response(
            responseText = null,
            responseStatus = "incomplete",
            error = "Output limit reached",
        )).runTest("gpt-6-luna", "shopping")
        val parseError = serviceWith(response(
            responseText = "not json",
        )).runTest("gpt-6-luna", "shopping")
        val incorrect = serviceWith(response(
            responseText = answerJson("250 PLN", "No discount applied."),
        )).runTest("gpt-6-luna", "shopping")

        assertEquals(Day5FailureType.API_ERROR, apiError.failureType)
        assertEquals(Day5FailureType.INCOMPLETE_RESPONSE, incomplete.failureType)
        assertEquals(Day5FailureType.PARSE_ERROR, parseError.failureType)
        assertEquals(Day3ValidationStatus.INCORRECT, incorrect.validationStatus)
        assertNull(incorrect.failureType)
    }

    @Test
    fun `request overrides model and uses shared limits`() {
        val parameters = Day3ResponsesClient().requestParameters(
            prompt = "same prompt",
            effort = Day3ReasoningEffort.LOW,
            structuredOutput = true,
            modelIdOverride = "gpt-6-astra",
            maxOutputTokensOverride = Day5Config.maxOutputTokens,
        )

        assertEquals("gpt-6-astra", parameters["model"]?.toString()?.trim('"'))
        assertEquals("1024", parameters["max_output_tokens"]?.toString())
        assertEquals("low", parameters["reasoning"]?.jsonObject?.get("effort")?.toString()?.trim('"'))
        assertFalse(parameters["text"]?.toString().isNullOrBlank())
    }

    private fun serviceWith(result: Day3ResponsesResult) = Day5ExperimentService(
        Day5ResponsesGateway { _, _ -> result },
    )

    private fun response(
        responseText: String?,
        inputTokens: Int? = null,
        cachedInputTokens: Int? = null,
        outputTokens: Int? = null,
        reasoningTokens: Int? = null,
        totalTokens: Int? = null,
        responseStatus: String? = "completed",
        apiStatus: Int? = 200,
        error: String? = null,
    ) = Day3ResponsesResult(
        responseText = responseText,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        reasoningTokens = reasoningTokens,
        latencyMs = 123,
        estimatedCostUsd = null,
        apiStatus = apiStatus,
        responseStatus = responseStatus,
        finishReason = null,
        incompleteReason = null,
        incompleteDetails = null,
        rawApiResponse = JsonNull,
        error = error,
        cachedInputTokens = cachedInputTokens,
        actualModelId = "gpt-6-luna-2026-10-15",
    )

    private fun answerJson(answer: String, explanation: String) =
        """{"answer":"$answer","explanation":"$explanation"}"""
}
