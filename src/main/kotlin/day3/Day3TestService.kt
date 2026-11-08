package day3

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.encodeToString
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

internal class Day3TestService(
    private val client: Day3ResponsesClient = Day3ResponsesClient(),
    private val resultDirectory: Path = Path.of("results", "day3"),
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }

    fun publicConfig(): JsonObject = buildJsonObject {
        put("model", client.modelName())
        put("tasks", JsonArray(Day3TaskCatalog.all.map { task ->
            buildJsonObject {
                put("id", task.id)
                put("category", task.category)
                put("text", task.text)
            }
        }))
        put("methods", JsonArray(Day3Method.entries.map { JsonPrimitive(it.name.lowercase()) }))
        put("reasoning_efforts", JsonArray(Day3Config.reasoningEfforts.map { JsonPrimitive(it.apiValue) }))
    }

    suspend fun runTest(
        taskId: String,
        effort: Day3ReasoningEffort,
        method: Day3Method,
        experimentId: String? = null,
        onApiCallCompleted: suspend (Day3ApiCallResult) -> Unit = {},
    ): JsonObject {
        val task = Day3TaskCatalog.find(taskId) ?: return errorResponse("Выбрана неизвестная задача.")
        val startedAt = System.nanoTime()
        val calls = mutableListOf<Day3ApiCallResult>()
        val files = mutableListOf<String>()
        val persistenceErrors = mutableListOf<String>()
        var generatedPrompt: String? = null
        var finalCall: Day3ApiCallResult? = null
        var finalAnswer: ParsedAnswer? = null
        var finalError: String? = null

        suspend fun call(prompt: String, purpose: String, structuredOutput: Boolean): Day3ApiCallResult {
            val apiCallStarted = Instant.now()
            val request = client.requestParameters(prompt, effort, structuredOutput)
            val response = client.complete(prompt, effort, structuredOutput)
            val parsed = if (structuredOutput) parseStructuredAnswer(response.responseText) else ParsedAnswer()
            val incompleteError = if (response.responseStatus == "incomplete") {
                "Ответ неполный${response.incompleteReason?.let { ": $it" } ?: "."}"
            } else {
                null
            }
            val callError = response.error ?: parsed.error ?: incompleteError
            val callValidation = if (!structuredOutput) {
                null
            } else if (parsed.answer != null && callError == null) {
                Day3AnswerValidator.validate(task, parsed.answer)
            } else {
                Day3AnswerValidation(Day3ValidationStatus.NEEDS_REVIEW, callError ?: "Ответ требует ручной проверки.")
            }
            val record = Day3ApiCallResult(
                timestamp = apiCallStarted.toString(),
                experimentId = experimentId,
                taskId = task.id,
                method = method,
                reasoningEffort = effort,
                model = client.modelName(),
                prompt = prompt,
                taskCategory = task.category,
                taskText = task.text,
                callPurpose = purpose,
                expectedAnswer = task.expectedAnswer,
                generatedPrompt = if (purpose == "prompt_generation") response.responseText else null,
                responseText = response.responseText,
                modelAnswer = parsed.answer,
                parsedAnswer = parsed.answer,
                explanation = parsed.explanation,
                inputTokens = response.inputTokens,
                outputTokens = response.outputTokens,
                totalTokens = response.totalTokens,
                reasoningTokens = response.reasoningTokens,
                latencyMs = response.latencyMs,
                estimatedCostUsd = response.estimatedCostUsd,
                correctnessStatus = callValidation?.status?.name,
                validationStatus = callValidation?.status,
                validationDetails = callValidation?.details,
                requestParameters = request,
                rawApiResponse = response.rawApiResponse,
                apiStatus = response.apiStatus,
                responseStatus = response.responseStatus,
                finishReason = response.finishReason,
                incompleteReason = response.incompleteReason,
                incompleteDetails = response.incompleteDetails,
                error = callError,
            )
            calls += record
            val saved = persistCall(record)
            saved.fileName?.let(files::add)
            saved.error?.let(persistenceErrors::add)
            onApiCallCompleted(record)
            return record
        }

        when (method) {
            Day3Method.DIRECT -> {
                finalCall = call(task.text, "solution", structuredOutput = true)
            }
            Day3Method.STEP_BY_STEP -> {
                val prompt = """${task.text}

                    |Реши задачу последовательно и проверь вычисления и условия. В поле explanation кратко изложи только основные проверяемые шаги, затем выведи итоговый ответ в поле answer. Не раскрывай скрытые внутренние рассуждения.""".trimMargin()
                finalCall = call(prompt, "solution", structuredOutput = true)
            }
            Day3Method.PROMPT_GENERATION -> {
                val promptRequest = """Составь эффективный, ясный промпт, который поможет решить следующую задачу.
                    |Не решай задачу и не объясняй ход рассуждений. Верни только готовый промпт.
                    |
                    |Задача:
                    |${task.text}""".trimMargin()
                val generationCall = call(promptRequest, "prompt_generation", structuredOutput = false)
                generatedPrompt = generationCall.responseText?.trim()?.takeIf(String::isNotEmpty)
                if (generationCall.error != null || generationCall.responseStatus == "incomplete") {
                    finalError = "Не удалось создать промпт: ${generationCall.error}"
                } else if (generatedPrompt == null) {
                    finalError = "Модель не вернула сгенерированный промпт; второй запрос не выполнялся."
                } else {
                    val solutionPrompt = """Используй следующий промпт для решения исходной задачи.
                        |
                        |Сгенерированный промпт:
                        |$generatedPrompt
                        |
                        |Исходная задача:
                        |${task.text}
                        |
                        |Верни итоговый ответ и краткое объяснение. Не включай скрытые внутренние рассуждения.""".trimMargin()
                    finalCall = call(solutionPrompt, "solution", structuredOutput = true)
                }
            }
            Day3Method.EXPERT_PANEL -> {
                val prompt = """Реши задачу, используя три краткие виртуальные роли:
                    |1. Аналитик — выдели ключевые условия.
                    |2. Практик — предложи решение.
                    |3. Критик — проверь результат и возможные ошибки.
                    |Затем сформируй единый ответ. В explanation дай только короткий итог каждой роли и общий вывод; не раскрывай скрытые внутренние рассуждения.
                    |
                    |Задача:
                    |${task.text}""".trimMargin()
                finalCall = call(prompt, "solution", structuredOutput = true)
            }
        }

        val apiCallErrors = calls.mapNotNull { it.error }
        val parsed = finalCall?.let { ParsedAnswer(it.modelAnswer, it.explanation, it.error) }
        finalAnswer = parsed
        if (finalError == null && finalCall != null) {
            finalError = finalCall.error ?: parsed?.error
        }
        if (finalError == null && finalCall == null) finalError = apiCallErrors.firstOrNull()

        val validation = finalError?.let { Day3AnswerValidation(Day3ValidationStatus.NEEDS_REVIEW, it) }
            ?: Day3AnswerValidator.validate(task, finalAnswer?.answer)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val outcome = Day3MethodOutcome(
            timestamp = Instant.now().toString(),
            experimentId = experimentId,
            taskId = task.id,
            taskCategory = task.category,
            taskText = task.text,
            method = method,
            reasoningEffort = effort,
            model = client.modelName(),
            prompt = task.text,
            generatedPrompt = generatedPrompt,
            responseText = finalCall?.responseText,
            answer = finalAnswer?.answer,
            explanation = finalAnswer?.explanation,
            expectedAnswer = task.expectedAnswer,
            validationStatus = validation.status,
            validationDetails = validation.details,
            inputTokens = sumMetrics(calls.map { it.inputTokens }),
            outputTokens = sumMetrics(calls.map { it.outputTokens }),
            reasoningTokens = sumMetrics(calls.map { it.reasoningTokens }),
            totalTokens = sumMetrics(calls.map { it.totalTokens }),
            latencyMs = elapsedMs,
            estimatedCostUsd = calls.mapNotNull { it.estimatedCostUsd }.takeIf { it.isNotEmpty() }?.sum(),
            apiStatus = finalCall?.apiStatus ?: calls.lastOrNull()?.apiStatus,
            responseStatus = finalCall?.responseStatus ?: calls.lastOrNull()?.responseStatus,
            error = finalError ?: persistenceErrors.firstOrNull(),
            calls = calls,
            resultFiles = files.toList(),
            persistenceError = persistenceErrors.firstOrNull(),
        )
        val aggregate = json.encodeToJsonElement(Day3MethodOutcome.serializer(), outcome).jsonObject
        val aggregateSaved = persistOutcome(outcome)
        val resultObject = if (aggregateSaved.fileName == null) aggregate else JsonObject(
            aggregate + ("aggregateResultFile" to JsonPrimitive(aggregateSaved.fileName)),
        )
        val allPersistenceErrors = listOfNotNull(outcome.persistenceError, aggregateSaved.error)
        return if (allPersistenceErrors.isEmpty()) resultObject else JsonObject(
            resultObject + ("persistenceError" to JsonPrimitive(allPersistenceErrors.joinToString(" "))),
        )
    }

    private fun parseStructuredAnswer(responseText: String?): ParsedAnswer {
        if (responseText.isNullOrBlank()) return ParsedAnswer(error = "В ответе нет JSON-текста с полями answer и explanation.")
        val answerObject = runCatching { json.parseToJsonElement(responseText).jsonObject }
            .getOrElse { return ParsedAnswer(error = "Не удалось разобрать структурированный JSON ответа: ${it.message ?: "неверный JSON"}.") }
        val answer = answerObject["answer"]?.let { runCatching { it.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }
        val explanation = answerObject["explanation"]?.let { runCatching { it.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }
        if (answer.isNullOrBlank() || explanation.isNullOrBlank() || answerObject.keys != setOf("answer", "explanation")) {
            return ParsedAnswer(error = "JSON ответа должен содержать только непустые строки answer и explanation.")
        }
        return ParsedAnswer(answer = answer, explanation = explanation)
    }

    private fun sumMetrics(values: List<Int?>): Int? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    private fun persistCall(record: Day3ApiCallResult): SaveOutcome = persist("call-${record.callPurpose ?: "request"}", record.timestamp) {
        json.encodeToString(Day3ApiCallResult.serializer(), record)
    }

    private fun persistOutcome(outcome: Day3MethodOutcome): SaveOutcome = persist("result-${outcome.method.name.lowercase()}", outcome.timestamp) {
        json.encodeToString(Day3MethodOutcome.serializer(), outcome)
    }

    private fun persist(prefix: String, timestamp: String, content: () -> String): SaveOutcome = runCatching {
        Files.createDirectories(resultDirectory)
        val time = FILE_TIMESTAMP.format(Instant.parse(timestamp).atZone(ZoneOffset.UTC))
        val fileName = "$prefix-$time-${UUID.randomUUID()}.json"
        val path = resultDirectory.resolve(fileName)
        Files.writeString(path, content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        SaveOutcome(path.toString(), null)
    }.getOrElse { SaveOutcome(null, "Не удалось сохранить результат в results/day3.") }

    private fun errorResponse(message: String): JsonObject = buildJsonObject { put("error", message) }

    private data class ParsedAnswer(val answer: String? = null, val explanation: String? = null, val error: String? = null)
    private data class SaveOutcome(val fileName: String?, val error: String?)

    companion object {
        private val FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
    }
}
