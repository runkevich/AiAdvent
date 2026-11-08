package day5

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Analytics are derived only from one manager state, never from a directory-wide scan. */
internal object Day5Analytics {
    fun calculate(state: Day5RunState): JsonObject {
        val expectedPerModel = Day5TaskCatalog.comparisonTasks.size
        val terminal = state.status in setOf(
            Day5RunStatus.COMPLETED,
            Day5RunStatus.COMPLETED_WITH_ERRORS,
            Day5RunStatus.FAILED,
        )
        val isComplete = terminal && state.completed >= state.total
        val summaries = Day5Config.models.map { model ->
            summarizeModel(model, state.records.filter { it.string("model_id") == model.id }, expectedPerModel)
        }
        val tasks = Day5TaskCatalog.comparisonTasks.map { task ->
            taskComparison(task, state.records.filter { it.string("task_id") == task.id }, isComplete)
        }
        val luna = summaries.first { it.modelId == "gpt-6-luna" }
        val comparisons = summaries.filter { it.modelId != luna.modelId }.map { model ->
            buildJsonObject {
                put("modelId", model.modelId)
                put("relativeToModelId", luna.modelId)
                put("accuracyDifferencePp", difference(model.accuracyPercent, luna.accuracyPercent))
                put("averageLatencyDifferenceMs", difference(model.averageLatencyMs, luna.averageLatencyMs))
                put("inputTokensDifference", difference(model.inputTokens, luna.inputTokens))
                put("outputTokensDifference", difference(model.outputTokens, luna.outputTokens))
                put("totalTokensDifference", difference(model.totalTokens, luna.totalTokens))
            }
        }

        return buildJsonObject {
            put("runId", state.runId)
            put("runStatus", state.status.name)
            put("completed", state.completed)
            put("total", state.total)
            put("isComplete", isComplete)
            put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
            put("modelSummaries", JsonArray(summaries.map { it.toJson() }))
            put("taskComparisons", JsonArray(tasks))
            put("costComparisons", buildJsonObject {
                put("models", JsonArray(summaries.map { it.costJson() }))
                put("multipliers", buildJsonObject {
                    put("solVsLuna", ratio(summaries.first { it.modelId == "gpt-6.1-sol" }.totalCostUsd, luna.totalCostUsd))
                    put("astraVsLuna", ratio(summaries.first { it.modelId == "gpt-6-astra" }.totalCostUsd, luna.totalCostUsd))
                })
            })
            put("modelComparisons", JsonArray(comparisons))
            put("automaticConclusions", JsonArray(if (isComplete) conclusions(summaries, tasks) else emptyList()))
        }
    }

    private fun summarizeModel(
        model: Day5ModelConfig,
        records: List<JsonObject>,
        expectedRequests: Int,
    ): ModelSummary {
        val results = records.mapNotNull { it["result"] as? JsonObject }
        val correct = results.count { it.string("validationStatus") == "CORRECT" }
        val incorrect = results.count { it.string("validationStatus") == "INCORRECT" }
        val review = results.count {
            it.string("validationStatus") == "NEEDS_REVIEW" && it.string("failureType").isNullOrBlank()
        }
        val apiErrors = results.count { it.string("failureType") == "API_ERROR" }
        val failedRequests = records.count { record ->
            record["error"]?.let { it != JsonNull } == true ||
                (record["result"] as? JsonObject)?.string("failureType")?.let { it != "" && it != "null" } == true
        }
        val latencies = results.mapNotNull { it.long("latencyMs") }.sorted()
        val accuracyDenominator = correct + incorrect
        val accuracy = if (accuracyDenominator == 0) null else correct * 100.0 / accuracyDenominator
        val input = strictSum(records, "inputTokens")
        val cached = strictSum(records, "cachedInputTokens")
        val output = strictSum(records, "outputTokens")
        val reasoning = strictSum(records, "reasoningTokens")
        val total = strictSum(records, "totalTokens")
        val cost = strictSumDouble(records, "estimatedCostUsd")
        val notes = buildList {
            if (accuracy == null) add("Нет оценённых ответов для расчёта accuracy.")
            if (records.size < expectedRequests) add("Получено ${records.size} из $expectedRequests запросов этой модели.")
            if (cost == null) add("Стоимость неизвестна: минимум для одного запроса отсутствуют usage или оценка стоимости.")
            if (input == null || cached == null || output == null || reasoning == null || total == null) {
                add("Токеновые итоги неизвестны: usage отсутствует или неполон.")
            }
        }
        return ModelSummary(
            modelId = model.id,
            displayName = model.displayName,
            tier = model.tier.name,
            expectedRequests = expectedRequests,
            completedRequests = records.size,
            correct = correct,
            incorrect = incorrect,
            needsReview = review,
            apiErrors = apiErrors,
            failedRequests = failedRequests,
            assessedAnswers = accuracyDenominator,
            accuracyPercent = accuracy,
            averageLatencyMs = latencies.takeIf { it.isNotEmpty() }?.average(),
            medianLatencyMs = median(latencies),
            latencySamples = latencies.size,
            inputTokens = input,
            cachedInputTokens = cached,
            outputTokens = output,
            reasoningTokens = reasoning,
            totalTokens = total,
            totalCostUsd = cost,
            averageCostPerRequest = if (cost != null && records.isNotEmpty()) cost / records.size else null,
            costPerCorrectAnswer = if (cost != null && correct > 0) cost / correct else null,
            notes = notes,
        )
    }

    private fun taskComparison(task: Day5Task, records: List<JsonObject>, isComplete: Boolean): JsonObject {
        val cells = Day5Config.models.map { model ->
            val record = records.firstOrNull { it.string("model_id") == model.id }
            val result = record?.get("result") as? JsonObject
            model.id to buildJsonObject {
                put("validationStatus", result?.string("validationStatus")?.let(::JsonPrimitive) ?: JsonNull)
                put("answer", result?.string("answer")?.let(::JsonPrimitive) ?: JsonNull)
                put("error", record?.get("error")?.takeUnless { it == JsonNull } ?: JsonNull)
                put("failureType", result?.string("failureType")?.let(::JsonPrimitive) ?: JsonNull)
                put("latencyMs", result?.numberOrNull("latencyMs") ?: JsonNull)
                put("totalTokens", result?.numberOrNull("totalTokens") ?: JsonNull)
                put("estimatedCostUsd", result?.numberOrNull("estimatedCostUsd") ?: JsonNull)
            }
        }.toMap()
        val lunaStatus = cells["gpt-6-luna"]?.get("validationStatus")?.jsonPrimitive?.contentOrNull
        val solStatus = cells["gpt-6.1-sol"]?.get("validationStatus")?.jsonPrimitive?.contentOrNull
        val astraStatus = cells["gpt-6-astra"]?.get("validationStatus")?.jsonPrimitive?.contentOrNull
        val conclusion = if (!isComplete) null else when {
            lunaStatus == "CORRECT" && solStatus == "CORRECT" && astraStatus == "CORRECT" -> "LUNA_SUFFICIENT"
            lunaStatus == "INCORRECT" && (solStatus == "CORRECT" || astraStatus == "CORRECT") -> "LARGER_MODEL_ADVANTAGE"
            else -> "INCONCLUSIVE"
        }
        return buildJsonObject {
            put("taskId", task.id)
            put("category", task.category)
            put("models", buildJsonObject { cells.forEach { (id, cell) -> put(id, cell) } })
            put("conclusion", conclusion?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun conclusions(summaries: List<ModelSummary>, tasks: List<JsonObject>): List<JsonObject> {
        val conclusions = mutableListOf<JsonObject>()
        val maxCorrect = summaries.maxOfOrNull { it.correct } ?: 0
        if (maxCorrect > 0) summaries.filter { it.correct == maxCorrect && it.assessedAnswers > 0 }.forEach { model ->
            conclusions += note(
                "MOST_CORRECT",
                "${model.displayName}: ${model.correct} ответов CORRECT из ${model.assessedAnswers} оценённых; API-ошибок: ${model.apiErrors}.",
            )
        }
        val fastest = summaries.filter { it.averageLatencyMs != null }.minByOrNull { it.averageLatencyMs!! }
        fastest?.let {
            conclusions += note("FASTEST", "${it.displayName} имеет наименьшую среднюю задержку (${it.latencySamples} измерений).")
        }
        val cheapest = summaries.filter { it.totalCostUsd != null }.minByOrNull { it.totalCostUsd!! }
        cheapest?.let { conclusions += note("LOWEST_COST", "${it.displayName} имеет наименьшую известную суммарную стоимость.") }
        tasks.filter { it.string("conclusion") == "LUNA_SUFFICIENT" }.forEach {
            conclusions += note("LUNA_SUFFICIENT", "На задаче «${it.string("category") ?: it.string("taskId") }» все три модели получили CORRECT.")
        }
        tasks.filter { it.string("conclusion") == "LARGER_MODEL_ADVANTAGE" }.forEach {
            conclusions += note("LARGER_MODEL_ADVANTAGE", "На задаче «${it.string("category") ?: it.string("taskId") }» Luna ошиблась, а более крупная модель ответила правильно.")
        }
        tasks.filter { it.string("conclusion") == "INCONCLUSIVE" }.forEach {
            conclusions += note("INCONCLUSIVE", "Результат задачи «${it.string("category") ?: it.string("taskId") }» не позволяет сделать однозначный вывод.")
        }
        return conclusions
    }

    private fun note(type: String, text: String) = buildJsonObject {
        put("type", type)
        put("text", text)
    }

    private fun strictSum(records: List<JsonObject>, key: String): Long? {
        if (records.isEmpty()) return null
        val values = records.mapNotNull { (it["result"] as? JsonObject)?.long(key) }
        return if (values.size == records.size) values.sum() else null
    }

    private fun strictSumDouble(records: List<JsonObject>, key: String): Double? {
        if (records.isEmpty()) return null
        val values = records.mapNotNull { (it["result"] as? JsonObject)?.double(key) }
        return if (values.size == records.size) values.sum() else null
    }

    private fun median(values: List<Long>): Double? {
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle].toDouble() else (values[middle - 1] + values[middle]) / 2.0
    }

    private fun difference(left: Double?, right: Double?) =
        if (left == null || right == null) JsonNull else JsonPrimitive(left - right)

    private fun difference(left: Long?, right: Long?) =
        if (left == null || right == null) JsonNull else JsonPrimitive(left - right)

    private fun ratio(numerator: Double?, denominator: Double?) =
        if (numerator == null || denominator == null || denominator <= 0.0) JsonNull
        else JsonPrimitive(numerator / denominator)

    private fun JsonObject.string(key: String): String? =
        this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.content?.toLongOrNull()
    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.numberOrNull(key: String) = this[key]?.takeUnless { it == JsonNull } ?: JsonNull

    private data class ModelSummary(
        val modelId: String,
        val displayName: String,
        val tier: String,
        val expectedRequests: Int,
        val completedRequests: Int,
        val correct: Int,
        val incorrect: Int,
        val needsReview: Int,
        val apiErrors: Int,
        val failedRequests: Int,
        val assessedAnswers: Int,
        val accuracyPercent: Double?,
        val averageLatencyMs: Double?,
        val medianLatencyMs: Double?,
        val latencySamples: Int,
        val inputTokens: Long?,
        val cachedInputTokens: Long?,
        val outputTokens: Long?,
        val reasoningTokens: Long?,
        val totalTokens: Long?,
        val totalCostUsd: Double?,
        val averageCostPerRequest: Double?,
        val costPerCorrectAnswer: Double?,
        val notes: List<String>,
    ) {
        fun toJson() = buildJsonObject {
            put("modelId", modelId); put("displayName", displayName); put("tier", tier)
            put("expectedRequests", expectedRequests); put("completedRequests", completedRequests)
            put("correct", correct); put("incorrect", incorrect); put("needsReview", needsReview)
                put("apiErrors", apiErrors); put("failedRequests", failedRequests); put("assessedAnswers", assessedAnswers)
            put("accuracyPercent", accuracyPercent?.let(::JsonPrimitive) ?: JsonNull)
            put("averageLatencyMs", averageLatencyMs?.let(::JsonPrimitive) ?: JsonNull)
            put("medianLatencyMs", medianLatencyMs?.let(::JsonPrimitive) ?: JsonNull); put("latencySamples", latencySamples)
            put("inputTokens", inputTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("cachedInputTokens", cachedInputTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("outputTokens", outputTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("reasoningTokens", reasoningTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("totalTokens", totalTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("totalCostUsd", totalCostUsd?.let(::JsonPrimitive) ?: JsonNull)
            put("averageCostPerRequest", averageCostPerRequest?.let(::JsonPrimitive) ?: JsonNull)
            put("costPerCorrectAnswer", costPerCorrectAnswer?.let(::JsonPrimitive) ?: JsonNull)
            put("notes", JsonArray(notes.map(::JsonPrimitive)))
        }

        fun costJson() = buildJsonObject {
            put("modelId", modelId)
            put("totalCostUsd", totalCostUsd?.let(::JsonPrimitive) ?: JsonNull)
            put("averageCostPerRequest", averageCostPerRequest?.let(::JsonPrimitive) ?: JsonNull)
            put("costPerCorrectAnswer", costPerCorrectAnswer?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
}
