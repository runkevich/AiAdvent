package day4

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object Day4Analytics {
    fun summarize(results: List<JsonObject>): JsonObject {
        val byTemperature = Day4Config.temperatures.map { temperature ->
            val group = results.filter { it.double("temperature") == temperature }
            buildJsonObject {
                put("temperature", temperature)
                put("factual", factual(group.filter { it.string("task_id") == Day4TaskCatalog.factual.id }))
                put("creative", creative(group.filter { it.string("task_id") == Day4TaskCatalog.creative.id }))
                put("average_latency_ms", average(group.mapNotNull { if (apiFailed(it)) null else it.long("latency_ms")?.toDouble() }))
                put("average_tokens", average(group.mapNotNull { if (apiFailed(it)) null else it.long("total_tokens")?.toDouble() }))
                put("total_cost", sum(group.mapNotNull { if (apiFailed(it)) null else it.double("estimated_cost") }))
            }
        }
        val table = byTemperature.map { row ->
            val factual = row["factual"] as JsonObject
            val creative = row["creative"] as JsonObject
            buildJsonObject {
                put("temperature", row["temperature"]!!)
                put("factual_accuracy", factual["accuracy_percent"] ?: JsonNull)
                put("creative_valid_responses", creative["valid_responses"] ?: JsonNull)
                put("creative_unique_names", creative["unique_names"] ?: JsonNull)
                put("average_latency_ms", row["average_latency_ms"] ?: JsonNull)
                put("average_tokens", row["average_tokens"] ?: JsonNull)
                put("total_cost", row["total_cost"] ?: JsonNull)
            }
        }
        return buildJsonObject {
            put("available_results", results.count { !apiFailed(it) })
            put("api_error_count", results.count(::apiFailed))
            put("by_temperature", JsonArray(byTemperature))
            put("comparison", JsonArray(table))
        }
    }

    private fun factual(results: List<JsonObject>) = buildJsonObject {
        val usable = results.filterNot(::apiFailed).filter { it.string("validation_status") in setOf("CORRECT", "INCORRECT") }
        val correct = usable.count { it.string("validation_status") == "CORRECT" }
        put("available_results", usable.size)
        put("expected_results", results.size)
        put("correct_count", correct)
        put("accuracy_percent", if (usable.isEmpty()) JsonNull else JsonPrimitive(correct * 100.0 / usable.size))
        put("unique_formulations", usable.mapNotNull { it.string("response_text")?.normalize() }.distinct().size)
        put("average_latency_ms", average(results.filterNot(::apiFailed).mapNotNull { it.long("latency_ms")?.toDouble() }))
        put("average_tokens", average(results.filterNot(::apiFailed).mapNotNull { it.long("total_tokens")?.toDouble() }))
        put("average_cost", average(results.filterNot(::apiFailed).mapNotNull { it.double("estimated_cost") }))
    }

    private fun creative(results: List<JsonObject>) = buildJsonObject {
        val usable = results.filterNot(::apiFailed)
        val namesByResult = usable.map { result -> extractNames(result.string("response_text").orEmpty()) }
        val allNames = namesByResult.flatten()
        val unique = allNames.map { it.normalize() }.distinct()
        val internalRepeats = usable.sumOf { result ->
            (result["validation_duplicate_names"] as? JsonArray)?.size ?: 0
        }
        val acrossRunRepeats = (allNames.size - unique.size - internalRepeats).coerceAtLeast(0)
        put("available_results", usable.size)
        put("expected_results", results.size)
        put("valid_responses", usable.count { it.string("validation_status") == "PASS" })
        put("responses_with_exactly_10", usable.count { it.long("validation_item_count") == 10L })
        put("unique_names", unique.size)
        put("names_extracted", allNames.size)
        put("repeats_within_answers", internalRepeats)
        put("repeats_between_runs", acrossRunRepeats)
        put("average_names_per_response", if (namesByResult.isEmpty()) JsonNull else JsonPrimitive(namesByResult.map { it.size }.average()))
        put("average_latency_ms", average(results.filterNot(::apiFailed).mapNotNull { it.long("latency_ms")?.toDouble() }))
        put("average_tokens", average(results.filterNot(::apiFailed).mapNotNull { it.long("total_tokens")?.toDouble() }))
        put("average_cost", average(results.filterNot(::apiFailed).mapNotNull { it.double("estimated_cost") }))
    }

    private fun extractNames(response: String): List<String> = response.lineSequence()
        .mapNotNull { Regex("^\\s*\\d+[.)]\\s+(.+?)\\s*$").matchEntire(it)?.groupValues?.get(1)?.trim() }
        .filter(String::isNotEmpty)
        .toList()

    private fun apiFailed(result: JsonObject): Boolean = !result.string("error").isNullOrBlank() ||
        (result.long("http_status")?.let { it !in 200L..299L } == true)

    private fun average(values: List<Double>) = if (values.isEmpty()) JsonNull else JsonPrimitive(values.average())
    private fun sum(values: List<Double>) = if (values.isEmpty()) JsonNull else JsonPrimitive(values.sum())
    private fun String.normalize() = lowercase().filter(Char::isLetterOrDigit)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
}
