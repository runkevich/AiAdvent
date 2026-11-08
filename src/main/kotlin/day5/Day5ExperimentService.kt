package day5

import day3.Day3AnswerValidator
import day3.Day3AnswerValidation
import day3.Day3ReasoningEffort
import day3.Day3ResponsesClient
import day3.Day3ResponsesResult
import day3.Day3ValidationStatus
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable

/** Injectable boundary keeps service behavior testable without network access. */
internal fun interface Day5ResponsesGateway {
    suspend fun complete(modelId: String, prompt: String): Day3ResponsesResult
}

internal fun interface Day5ExperimentRunner {
    suspend fun runTest(modelId: String, taskId: String): Day5ExperimentResult
}

internal class Day5ExperimentService(
    private val gateway: Day5ResponsesGateway = Day5ResponsesGateway { modelId, prompt ->
        Day3ResponsesClient().complete(
            prompt = prompt,
            effort = Day3ReasoningEffort.LOW,
            structuredOutput = true,
            modelIdOverride = modelId,
            maxOutputTokensOverride = Day5Config.maxOutputTokens,
        )
    },
) : Day5ExperimentRunner {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun runTest(modelId: String, taskId: String): Day5ExperimentResult {
        val model = Day5Config.findModel(modelId)
            ?: return rejected(modelId, taskId, "Неизвестная модель: $modelId.")
        val task = Day5TaskCatalog.find(taskId)
            ?: return rejected(modelId, taskId, "Неизвестная задача: $taskId.")

        val prompt = promptFor(task)
        val requestTimestamp = Instant.now().toString()
        val response = try {
            gateway.complete(model.id, prompt)
        } catch (exception: Exception) {
            return result(
                timestamp = requestTimestamp,
                modelId = model.id,
                task = task,
                prompt = prompt,
                responseStatus = null,
                error = safeError(exception.message ?: "Не удалось выполнить запрос к OpenAI."),
                failureType = Day5FailureType.API_ERROR,
                latencyMs = null,
                inputTokens = null,
                cachedInputTokens = null,
                outputTokens = null,
                reasoningTokens = null,
                totalTokens = null,
                estimatedCostUsd = null,
            )
        }

        val cost = if (response.inputTokens != null && response.outputTokens != null) {
            model.pricing.costUsd(
                inputTokens = response.inputTokens,
                // The API reports cached_tokens as a breakdown of input_tokens. An omitted
                // breakdown means no cached input was metered; retain null in the result.
                cachedInputTokens = response.cachedInputTokens ?: 0,
                outputTokens = response.outputTokens,
            )
        } else {
            null
        }
        val parsed = parseAnswer(response.responseText)
        val failureType: Day5FailureType?
        val error: String?
        val validation = when {
            response.apiStatus != null && response.apiStatus !in 200..299 -> {
                failureType = Day5FailureType.API_ERROR
                error = response.error ?: "OpenAI API вернул HTTP ${response.apiStatus}."
                null
            }
            response.responseStatus == "incomplete" -> {
                failureType = Day5FailureType.INCOMPLETE_RESPONSE
                error = response.error ?: "Ответ OpenAI неполный."
                null
            }
            response.apiStatus == null && response.error != null -> {
                failureType = Day5FailureType.API_ERROR
                error = response.error
                null
            }
            response.error != null -> {
                failureType = Day5FailureType.PARSE_ERROR
                error = response.error
                null
            }
            parsed.error != null -> {
                failureType = Day5FailureType.PARSE_ERROR
                error = parsed.error
                null
            }
            else -> {
                failureType = null
                error = null
                task.validationSource?.let { Day3AnswerValidator.validate(it, parsed.answer) }
                    ?: Day3AnswerValidation(
                        Day3ValidationStatus.NEEDS_REVIEW,
                        "Для творческого ответа нет однозначного эталона; требуется ручная оценка.",
                    )
            }
        }

        return Day5ExperimentResult(
            timestamp = requestTimestamp,
            requestedModelId = model.id,
            actualModelId = response.actualModelId,
            taskId = task.id,
            prompt = prompt,
            answer = parsed.answer,
            explanation = parsed.explanation,
            responseStatus = response.responseStatus,
            failureType = failureType,
            error = error,
            latencyMs = response.latencyMs,
            inputTokens = response.inputTokens,
            cachedInputTokens = response.cachedInputTokens,
            outputTokens = response.outputTokens,
            reasoningTokens = response.reasoningTokens,
            totalTokens = response.totalTokens,
            estimatedCostUsd = cost,
            validationStatus = validation?.status ?: Day3ValidationStatus.NEEDS_REVIEW,
            validationDetails = validation?.details ?: error ?: "Ответ требует ручной проверки.",
        )
    }

    private fun promptFor(task: Day5Task): String = """${task.text}

        |Верни JSON с двумя непустыми строковыми полями: "answer" и "explanation".
        |В answer укажи итоговый ответ, а в explanation — краткое объяснение.
        |Не раскрывай скрытые внутренние рассуждения.""".trimMargin()

    private fun rejected(modelId: String, taskId: String, message: String) = Day5ExperimentResult(
        timestamp = Instant.now().toString(),
        requestedModelId = modelId,
        actualModelId = null,
        taskId = taskId,
        prompt = null,
        answer = null,
        explanation = null,
        responseStatus = null,
        failureType = Day5FailureType.INVALID_SELECTION,
        error = message,
        latencyMs = null,
        inputTokens = null,
        cachedInputTokens = null,
        outputTokens = null,
        reasoningTokens = null,
        totalTokens = null,
        estimatedCostUsd = null,
        validationStatus = Day3ValidationStatus.NEEDS_REVIEW,
        validationDetails = message,
    )

    private fun result(
        timestamp: String,
        modelId: String,
        task: Day5Task,
        prompt: String,
        responseStatus: String?,
        error: String?,
        failureType: Day5FailureType,
        latencyMs: Long?,
        inputTokens: Int?,
        cachedInputTokens: Int?,
        outputTokens: Int?,
        reasoningTokens: Int?,
        totalTokens: Int?,
        estimatedCostUsd: Double?,
    ) = Day5ExperimentResult(
        timestamp = timestamp,
        requestedModelId = modelId,
        actualModelId = null,
        taskId = task.id,
        prompt = prompt,
        answer = null,
        explanation = null,
        responseStatus = responseStatus,
        failureType = failureType,
        error = error,
        latencyMs = latencyMs,
        inputTokens = inputTokens,
        cachedInputTokens = cachedInputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        totalTokens = totalTokens,
        estimatedCostUsd = estimatedCostUsd,
        validationStatus = Day3ValidationStatus.NEEDS_REVIEW,
        validationDetails = error ?: "Ответ требует ручной проверки.",
    )

    private fun parseAnswer(text: String?): ParsedAnswer {
        if (text.isNullOrBlank()) return ParsedAnswer(error = "В ответе нет JSON-текста с полями answer и explanation.")
        val answerObject = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { return ParsedAnswer(error = "Не удалось разобрать JSON ответа модели: ${it.message ?: "неверный JSON"}.") }
        val answer = answerObject.string("answer")?.trim()
        val explanation = answerObject.string("explanation")?.trim()
        if (answer.isNullOrBlank() || explanation.isNullOrBlank() || answerObject.keys != setOf("answer", "explanation")) {
            return ParsedAnswer(error = "JSON должен содержать только непустые строковые поля answer и explanation.")
        }
        return ParsedAnswer(answer, explanation)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun safeError(message: String) = message.replace(Regex("(?i)\\bsk-[^\\s,;\\\"']+"), "[REDACTED]")

    private data class ParsedAnswer(val answer: String? = null, val explanation: String? = null, val error: String? = null)
}

@Serializable
internal enum class Day5FailureType {
    INVALID_SELECTION,
    API_ERROR,
    INCOMPLETE_RESPONSE,
    PARSE_ERROR,
}

@Serializable
internal data class Day5ExperimentResult(
    val timestamp: String,
    val requestedModelId: String,
    val actualModelId: String?,
    val taskId: String,
    val prompt: String?,
    val answer: String?,
    val explanation: String?,
    val responseStatus: String?,
    val failureType: Day5FailureType?,
    val error: String?,
    val latencyMs: Long?,
    val inputTokens: Int?,
    val cachedInputTokens: Int?,
    val outputTokens: Int?,
    val reasoningTokens: Int?,
    val totalTokens: Int?,
    val estimatedCostUsd: Double?,
    val validationStatus: Day3ValidationStatus,
    val validationDetails: String,
)
