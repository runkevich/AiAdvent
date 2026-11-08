package day3

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object Day3Analytics {
    fun summarize(results: List<JsonObject>): JsonObject {
        val comparison = Day3Method.entries.map { method ->
            val low = stats(results.filter { it.string("method") == method.name && it.string("reasoningEffort") == "LOW" })
            val max = stats(results.filter { it.string("method") == method.name && it.string("reasoningEffort") == "MAX" })
            buildJsonObject {
                put("method", method.name)
                put("method_name", method.displayName)
                put("low", low)
                put("max", max)
            }
        }
        val lowAll = stats(results.filter { it.string("reasoningEffort") == "LOW" })
        val maxAll = stats(results.filter { it.string("reasoningEffort") == "MAX" })
        val totalCallErrors = results.sumOf { result ->
            (result["calls"] as? JsonArray).orEmpty().count { (it as? JsonObject).hasText("error") }
        }
        val statuses = results.groupingBy { it.string("validationStatus") ?: "NEEDS_REVIEW" }.eachCount()
        val bestMethods = comparison.mapNotNull { row ->
            val method = row.string("method_name") ?: return@mapNotNull null
            val correct = (row["low"] as? JsonObject).int("correct_count") + (row["max"] as? JsonObject).int("correct_count")
            method to correct
        }
        val bestScore = bestMethods.maxOfOrNull { it.second }
        val best = bestScore?.let { score -> bestMethods.filter { it.second == score }.map { it.first } }.orEmpty()
        val taskDifferences = validationDifferences(results)

        return buildJsonObject {
            put("expected_method_results", 64)
            put("completed_method_results", results.size)
            put("correct_count", statuses["CORRECT"] ?: 0)
            put("incorrect_count", statuses["INCORRECT"] ?: 0)
            put("needs_review_count", statuses["NEEDS_REVIEW"] ?: 0)
            put("api_error_count", totalCallErrors)
            put("total_estimated_cost_usd", sum(results.mapNotNull { it.double("estimatedCostUsd") }))
            put("average_latency_ms", average(results.mapNotNull { it.double("latencyMs") }))
            put("average_input_tokens", average(results.mapNotNull { it.double("inputTokens") }))
            put("average_output_tokens", average(results.mapNotNull { it.double("outputTokens") }))
            put("average_reasoning_tokens", average(results.mapNotNull { it.double("reasoningTokens") }))
            put("average_total_tokens", average(results.mapNotNull { it.double("totalTokens") }))
            put("best_method_names", JsonArray(best.map(::JsonPrimitive)))
            put("by_reasoning_effort", buildJsonObject {
                put("low", lowAll)
                put("max", maxAll)
            })
            put("comparison", JsonArray(comparison))
            put("observations", buildJsonObject {
                put("best_methods", JsonArray(best.map(::JsonPrimitive)))
                put("low_correct_count", lowAll.int("correct_count"))
                put("max_correct_count", maxAll.int("correct_count"))
                put("accuracy_change", maxAll.int("correct_count") - lowAll.int("correct_count"))
                put("low_average_latency_ms", lowAll["average_latency_ms"] ?: JsonNull)
                put("max_average_latency_ms", maxAll["average_latency_ms"] ?: JsonNull)
                put("latency_change_percent", percentChange(lowAll.double("average_latency_ms"), maxAll.double("average_latency_ms")))
                put("low_average_total_tokens", lowAll["average_total_tokens"] ?: JsonNull)
                put("max_average_total_tokens", maxAll["average_total_tokens"] ?: JsonNull)
                put("token_change_percent", percentChange(lowAll.double("average_total_tokens"), maxAll.double("average_total_tokens")))
                put("low_average_reasoning_tokens", lowAll["average_reasoning_tokens"] ?: JsonNull)
                put("max_average_reasoning_tokens", maxAll["average_reasoning_tokens"] ?: JsonNull)
                put("reasoning_token_change_percent", percentChange(lowAll.double("average_reasoning_tokens"), maxAll.double("average_reasoning_tokens")))
                put("tasks_with_validation_difference", JsonArray(taskDifferences))
            })
        }
    }

    private fun stats(results: List<JsonObject>): JsonObject {
        val statusCounts = results.groupingBy { it.string("validationStatus") ?: "NEEDS_REVIEW" }.eachCount()
        return buildJsonObject {
            put("result_count", results.size)
            put("correct_count", statusCounts["CORRECT"] ?: 0)
            put("incorrect_count", statusCounts["INCORRECT"] ?: 0)
            put("needs_review_count", statusCounts["NEEDS_REVIEW"] ?: 0)
            put("average_latency_ms", average(results.mapNotNull { it.double("latencyMs") }))
            put("average_input_tokens", average(results.mapNotNull { it.double("inputTokens") }))
            put("average_output_tokens", average(results.mapNotNull { it.double("outputTokens") }))
            put("average_reasoning_tokens", average(results.mapNotNull { it.double("reasoningTokens") }))
            put("average_total_tokens", average(results.mapNotNull { it.double("totalTokens") }))
            put("estimated_cost_usd", sum(results.mapNotNull { it.double("estimatedCostUsd") }))
            put("api_error_count", results.sumOf { result ->
                (result["calls"] as? JsonArray).orEmpty().count { (it as? JsonObject).hasText("error") }
            })
        }
    }

    private fun validationDifferences(results: List<JsonObject>): List<JsonObject> {
        val keys = results.mapNotNull { result ->
            val taskId = result.string("taskId") ?: return@mapNotNull null
            val method = result.string("method") ?: return@mapNotNull null
            "$taskId|$method"
        }.distinct()
        return keys.mapNotNull { key ->
            val (taskId, method) = key.split('|', limit = 2)
            val low = results.firstOrNull { it.string("taskId") == taskId && it.string("method") == method && it.string("reasoningEffort") == "LOW" }
            val max = results.firstOrNull { it.string("taskId") == taskId && it.string("method") == method && it.string("reasoningEffort") == "MAX" }
            val lowStatus = low?.string("validationStatus")
            val maxStatus = max?.string("validationStatus")
            if (lowStatus == null || maxStatus == null || lowStatus == maxStatus) null else buildJsonObject {
                put("task_id", taskId)
                put("method", method)
                put("low", lowStatus)
                put("max", maxStatus)
            }
        }
    }

    private fun percentChange(low: Double?, max: Double?): JsonElement =
        if (low == null || max == null || low == 0.0) JsonNull else JsonPrimitive(round((max - low) / low * 100.0))

    private fun average(values: List<Double>): JsonElement =
        if (values.isEmpty()) JsonNull else JsonPrimitive(round(values.average()))

    private fun sum(values: List<Double>): JsonElement =
        if (values.isEmpty()) JsonNull else JsonPrimitive(round(values.sum()))

    private fun round(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.int(key: String): Int = this?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    private fun JsonObject?.double(key: String): Double? = this?.get(key)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    private fun JsonObject?.hasText(key: String): Boolean = !this.string(key).isNullOrBlank()
}
