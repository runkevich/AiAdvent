package day5

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Day5AnalyticsTest {
    @Test
    fun `accuracy excludes needs review and api errors and computes cost comparisons`() {
        val analytics = Day5Analytics.calculate(fullState(fullRecords()))
        val models = analytics["modelSummaries"]!!.jsonArray.associateBy { it.jsonObject["modelId"]!!.jsonPrimitive.content }
        val luna = models.getValue("gpt-6-luna").jsonObject
        val sol = models.getValue("gpt-6.1-sol").jsonObject
        val astra = models.getValue("gpt-6-astra").jsonObject

        assertEquals(1, luna["correct"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, luna["incorrect"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, luna["needsReview"]!!.jsonPrimitive.content.toInt())
        assertEquals(50.0, luna["accuracyPercent"]!!.jsonPrimitive.content.toDouble())
        assertEquals(1, astra["apiErrors"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, astra["needsReview"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, astra["incorrect"]!!.jsonPrimitive.content.toInt())
        assertNull(astra["totalCostUsd"].takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull)
        val multipliers = analytics["costComparisons"]!!.jsonObject["multipliers"]!!.jsonObject
        assertEquals(10.0, multipliers["solVsLuna"]!!.jsonPrimitive.content.toDouble())
        assertEquals(JsonNull, multipliers["astraVsLuna"])
        assertEquals(0.001, sol["costPerCorrectAnswer"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `cost multipliers use totals from this run`() {
        val records = Day5TaskCatalog.comparisonTasks.flatMap { task ->
            Day5Config.models.map { model ->
                val cost = when (model.id) {
                    "gpt-6-luna" -> 0.0001
                    "gpt-6.1-sol" -> 0.001
                    else -> 0.005
                }
                resultRecord(model.id, task.id, "CORRECT", cost = cost)
            }
        }
        val analytics = Day5Analytics.calculate(fullState(records))
        val multipliers = analytics["costComparisons"]!!.jsonObject["multipliers"]!!.jsonObject

        assertEquals(10.0, multipliers["solVsLuna"]!!.jsonPrimitive.content.toDouble())
        assertEquals(50.0, multipliers["astraVsLuna"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `task conclusions distinguish sufficient larger advantage and inconclusive`() {
        val analytics = Day5Analytics.calculate(fullState(fullRecords()))
        val tasks = analytics["taskComparisons"]!!.jsonArray.associateBy { it.jsonObject["taskId"]!!.jsonPrimitive.content }

        assertEquals("LUNA_SUFFICIENT", tasks.getValue("shopping").jsonObject["conclusion"]!!.jsonPrimitive.content)
        assertEquals("LARGER_MODEL_ADVANTAGE", tasks.getValue("mathematics").jsonObject["conclusion"]!!.jsonPrimitive.content)
        assertEquals("INCONCLUSIVE", tasks.getValue("logic").jsonObject["conclusion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing usage remains null and partial runs expose no final conclusions`() {
        val record = resultRecord("gpt-6-luna", "shopping", "CORRECT", usage = false)
        val analytics = Day5Analytics.calculate(fullState(listOf(record), completed = 1, status = Day5RunStatus.RUNNING))
        val luna = analytics["modelSummaries"]!!.jsonArray.first().jsonObject

        assertFalse(analytics["isComplete"]!!.jsonPrimitive.content.toBoolean())
        assertNull(analytics["automaticConclusions"]!!.jsonArray.firstOrNull())
        assertNull(luna["inputTokens"].takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull)
        assertNull(luna["totalCostUsd"].takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull)
        assertTrue(luna["notes"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `latency median and token totals use recorded usage only`() {
        val records = listOf(
            resultRecord("gpt-6-luna", "shopping", "CORRECT", latency = 100, cost = 0.0001),
            resultRecord("gpt-6-luna", "mathematics", "INCORRECT", latency = 300, cost = 0.0002),
        )
        val analytics = Day5Analytics.calculate(fullState(records, completed = 2, status = Day5RunStatus.RUNNING))
        val luna = analytics["modelSummaries"]!!.jsonArray.first().jsonObject

        assertEquals(200.0, luna["averageLatencyMs"]!!.jsonPrimitive.content.toDouble())
        assertEquals(200.0, luna["medianLatencyMs"]!!.jsonPrimitive.content.toDouble())
        assertEquals(240, luna["totalTokens"]!!.jsonPrimitive.content.toLong())
    }

    private fun fullRecords(): List<JsonObject> = buildList {
        Day5TaskCatalog.comparisonTasks.forEachIndexed { index, task ->
            Day5Config.models.forEach { model ->
                val status = when (model.id) {
                    "gpt-6-luna" -> when (index) { 0 -> "CORRECT"; 1 -> "INCORRECT"; else -> "NEEDS_REVIEW" }
                    "gpt-6.1-sol" -> "CORRECT"
                    else -> if (index == 4) "NEEDS_REVIEW" else if (index == 1) "INCORRECT" else "CORRECT"
                }
                val apiError = model.id == "gpt-6-astra" && index == 4
                add(resultRecord(model.id, task.id, status, cost = if (apiError) null else if (model.id == "gpt-6-luna") 0.0001 else 0.001, failureType = if (apiError) "API_ERROR" else null, usage = !apiError))
            }
        }
    }

    private fun resultRecord(
        modelId: String,
        taskId: String,
        status: String,
        latency: Int = 100,
        cost: Double? = 0.0001,
        failureType: String? = null,
        usage: Boolean = true,
    ) = buildJsonObject {
        put("model_id", modelId)
        put("task_id", taskId)
        put("error", if (failureType == null) JsonNull else kotlinx.serialization.json.JsonPrimitive("fake API error"))
        put("result", buildJsonObject {
            put("validationStatus", status)
            put("failureType", failureType?.let { JsonPrimitive(it) } ?: JsonNull)
            put("answer", "answer")
            put("latencyMs", latency)
            if (usage) put("inputTokens", 100) else put("inputTokens", JsonNull)
            if (usage) put("cachedInputTokens", 0) else put("cachedInputTokens", JsonNull)
            if (usage) put("outputTokens", 20) else put("outputTokens", JsonNull)
            if (usage) put("reasoningTokens", 5) else put("reasoningTokens", JsonNull)
            if (usage) put("totalTokens", 120) else put("totalTokens", JsonNull)
            if (cost == null) put("estimatedCostUsd", JsonNull) else put("estimatedCostUsd", cost)
        })
    }

    private fun fullState(records: List<JsonObject>, completed: Int = 15, status: Day5RunStatus = Day5RunStatus.COMPLETED) = Day5RunState(
        runId = "test-run", status = status, createdAt = Instant.EPOCH.toString(), completedAt = null,
        completed = completed, total = 15, currentModelId = null, currentTaskId = null, error = null,
        records = records, resultFiles = emptyList(), persistenceErrors = 0,
    )
}
