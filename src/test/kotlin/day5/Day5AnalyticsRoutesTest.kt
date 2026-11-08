package day5

import day3.Day3ValidationStatus
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Day5AnalyticsRoutesTest {
    @Test
    fun `history and analytics stay scoped to each run and unknown run returns 404`() = testApplication {
        val root = Files.createTempDirectory("day5-analytics-routes")
        val calls = AtomicInteger()
        val service = Day5ExperimentService(Day5ResponsesGateway { _, _ -> error("Single test route must not call the gateway.") })
        val runner = Day5ExperimentRunner { model, task ->
            val batch = calls.getAndIncrement() / Day5RunManager.TOTAL
            analyticsResult(model, task, if (batch == 0) Day3ValidationStatus.CORRECT else Day3ValidationStatus.INCORRECT)
        }
        val manager = Day5RunManager(runner, Day5ResultStore(root), Mutex())
        application { routing { day5Routes(service, Day5ResultStore(root), Mutex(), manager) } }

        val first = Json.parseToJsonElement(client.post("/api/day5/experiment").bodyAsText()).jsonObject
        val firstId = first["runId"]!!.jsonPrimitive.content
        awaitFinished(manager, firstId)
        val second = Json.parseToJsonElement(client.post("/api/day5/experiment").bodyAsText()).jsonObject
        val secondId = second["runId"]!!.jsonPrimitive.content
        awaitFinished(manager, secondId)

        val historyResponse = client.get("/api/day5/runs")
        val history = Json.parseToJsonElement(historyResponse.bodyAsText()).jsonArray
        val firstAnalyticsResponse = client.get("/api/day5/runs/$firstId/analytics")
        val secondAnalyticsResponse = client.get("/api/day5/runs/$secondId/analytics")
        val firstAnalytics = Json.parseToJsonElement(firstAnalyticsResponse.bodyAsText()).jsonObject
        val secondAnalytics = Json.parseToJsonElement(secondAnalyticsResponse.bodyAsText()).jsonObject
        val firstLuna = firstAnalytics["modelSummaries"]!!.jsonArray.first().jsonObject
        val secondLuna = secondAnalytics["modelSummaries"]!!.jsonArray.first().jsonObject
        val missing = client.get("/api/day5/runs/missing-run/analytics")

        assertEquals(HttpStatusCode.OK, historyResponse.status)
        assertEquals(2, history.size)
        assertTrue(history.any { it.jsonObject["runId"]?.jsonPrimitive?.content == firstId })
        assertTrue(history.any { it.jsonObject["runId"]?.jsonPrimitive?.content == secondId })
        assertEquals(firstId, firstAnalytics["runId"]!!.jsonPrimitive.content)
        assertEquals(secondId, secondAnalytics["runId"]!!.jsonPrimitive.content)
        assertEquals(5, firstLuna["correct"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, secondLuna["correct"]!!.jsonPrimitive.content.toInt())
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    private suspend fun awaitFinished(manager: Day5RunManager, runId: String) {
        repeat(500) {
            if (manager.get(runId)?.status in setOf(Day5RunStatus.COMPLETED, Day5RunStatus.COMPLETED_WITH_ERRORS, Day5RunStatus.FAILED) && manager.active()?.runId != runId) return
            delay(10)
        }
        error("Fake Day 5 run did not finish")
    }

    private fun analyticsResult(modelId: String, taskId: String, status: Day3ValidationStatus) = Day5ExperimentResult(
        timestamp = Instant.now().toString(), requestedModelId = modelId, actualModelId = modelId,
        taskId = taskId, prompt = "fake", answer = "fake", explanation = "fake", responseStatus = "completed",
        failureType = null, error = null, latencyMs = 10, inputTokens = 5, cachedInputTokens = 0,
        outputTokens = 2, reasoningTokens = 0, totalTokens = 7, estimatedCostUsd = 0.00001,
        validationStatus = status, validationDetails = "fake",
    )
}
