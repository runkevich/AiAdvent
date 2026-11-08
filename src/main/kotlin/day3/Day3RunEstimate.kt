package day3

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class Day3RunEstimate(
    private val resultDirectory: Path = Path.of("results", "day3"),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun estimate(): JsonObject {
        val samples = mutableMapOf<String, MutableList<Double>>()
        runCatching {
            if (Files.isDirectory(resultDirectory)) {
                Files.list(resultDirectory).use { paths ->
                    paths.filter { it.fileName.toString().startsWith("result-") && it.fileName.toString().endsWith(".json") }
                        .forEach { path ->
                            runCatching {
                                val result = json.parseToJsonElement(Files.readString(path)).jsonObject
                                if (result["error"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                                    val method = result["method"]?.jsonPrimitive?.contentOrNull
                                    val effort = result["reasoningEffort"]?.jsonPrimitive?.contentOrNull
                                    val cost = result["estimatedCostUsd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                                    if (method != null && effort != null && cost != null) {
                                        samples.getOrPut("$method|$effort") { mutableListOf() }.add(cost)
                                    }
                                }
                            }
                        }
                }
            }
        }

        val expectedOutcomesPerBucket = Day3TaskCatalog.all.size
        val buckets = Day3Method.entries.flatMap { method ->
            Day3Config.reasoningEfforts.map { effort ->
                val key = "${method.name}|${effort.name}"
                val costs = samples[key].orEmpty()
                buildJsonObject {
                    put("method", method.name)
                    put("reasoning_effort", effort.apiValue)
                    put("pilot_samples", costs.size)
                    put("projected_outcomes", expectedOutcomesPerBucket)
                    put("average_pilot_cost_usd", costs.takeIf { it.isNotEmpty() }?.average()?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }
        val missingBuckets = buckets.filter { (it["pilot_samples"] as? JsonPrimitive)?.content?.toIntOrNull() == 0 }
        val estimateUsd = if (missingBuckets.isEmpty()) {
            buckets.sumOf { bucket ->
                val avg = bucket["average_pilot_cost_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                val count = bucket["projected_outcomes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                avg * count
            }
        } else {
            null
        }
        val pricing = Day3Config.pricing
        return buildJsonObject {
            put("model", Day3Config.model)
            put("expected_api_requests", Day3Method.entries.sumOf { method ->
                Day3TaskCatalog.all.size * Day3Config.reasoningEfforts.size * method.apiCalls
            })
            put("expected_method_results", Day3TaskCatalog.all.size * Day3Method.entries.size * Day3Config.reasoningEfforts.size)
            put("cost_estimate_usd", estimateUsd?.let(::JsonPrimitive) ?: JsonNull)
            put("estimate_reliable", missingBuckets.isEmpty())
            put("estimate_explanation", if (missingBuckets.isEmpty()) {
                "Пилотная оценка по средним фактическим затратам каждой пары method/reasoning effort. Фактическая сумма зависит от ответов модели."
            } else {
                "Недостаточно успешных пилотных результатов для всех методов и уровней reasoning; стоимость заранее надёжно оценить нельзя."
            })
            put("missing_estimate_buckets", JsonArray(missingBuckets.map { bucket ->
                JsonPrimitive("${bucket["method"]?.jsonPrimitive?.contentOrNull}/${bucket["reasoning_effort"]?.jsonPrimitive?.contentOrNull}")
            }))
            put("pilot_samples_by_method_effort", JsonArray(buckets))
            put("pricing_usd_per_million", buildJsonObject {
                put("input", pricing.inputUsdPerMillion)
                put("cached_input", pricing.cachedInputUsdPerMillion)
                put("cache_write", pricing.cacheWriteUsdPerMillion)
                put("output", pricing.outputUsdPerMillion)
            })
        }
    }
}
