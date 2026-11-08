import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class Day2Variant(
    val id: String,
    val title: String,
    val instructions: String = "",
    val usesTokenLimit: Boolean = false,
) {
    BASELINE("baseline", "Baseline"),
    FORMAT_INSTRUCTIONS("format_instructions", "Format instructions + token limit", DINNER_FORMAT_INSTRUCTIONS, true),
    STOP_SEQUENCE("stop_sequence", "Format instructions + token limit + stop sequence", "$DINNER_FORMAT_INSTRUCTIONS\nДобавь маркер <<<END_OF_RESPONSE>>> в самом конце ответа.", true),
    JSON_SCHEMA("json_schema", "Strict JSON Schema", "Составь ровно три варианта ужина на основе продуктов из запроса. Верни только JSON, соответствующий заданной схеме."),
    ;

    companion object {
        fun fromId(id: String): Day2Variant? = entries.firstOrNull { it.id == id }

        val recipeSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("required", JsonArray(listOf(JsonPrimitive("recipes"))))
            put("properties", buildJsonObject {
                put("recipes", buildJsonObject {
                    put("type", "array")
                    put("minItems", 3)
                    put("maxItems", 3)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", false)
                        put("required", JsonArray(listOf("name", "ingredients", "instructions", "preparation_time").map(::JsonPrimitive)))
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("ingredients", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("instructions", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("preparation_time", buildJsonObject { put("type", "integer") })
                        })
                    })
                })
            })
        }
    }
}

internal data class ValidationResult(val status: String, val message: String?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("status", status)
        put("message", message?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal class Day2ExperimentService(
    private val client: ChatCompletionsClient = ChatCompletionsClient(),
    private val resultDirectory: Path = Path.of("results", "day2"),
) {
    private val outputJson = Json { prettyPrint = true }

    suspend fun runTest(
        prompt: String,
        variantId: String,
        runType: String = "test",
        runNumber: Int? = null,
    ): JsonObject {
        val variant = Day2Variant.fromId(variantId)
            ?: return errorResult(prompt, variantId, "Неизвестный вариант эксперимента.")
        val timestamp = Instant.now()
        val start = System.nanoTime()
        val requestParameters = client.requestParameters(prompt, variant)

        val apiResult = try {
            client.complete(prompt, variant)
        } catch (exception: Exception) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            return persist(buildResult(
                timestamp = timestamp,
                variant = variant,
                runType = runType,
                runNumber = runNumber,
                prompt = prompt,
                requestParameters = requestParameters,
                responseText = null,
                rawApiResponse = JsonNull,
                latencyMs = latencyMs,
                promptTokens = null,
                completionTokens = null,
                totalTokens = null,
                finishReason = null,
                validation = if (variant == Day2Variant.JSON_SCHEMA) {
                    ValidationResult("not_run", "Проверка не выполнена: ответ от OpenAI не получен.")
                } else {
                    ValidationResult("not_applicable", null)
                },
                error = safeError(exception.message),
            ))
        }
        val latencyMs = (System.nanoTime() - start) / 1_000_000
        val validation = when {
            variant != Day2Variant.JSON_SCHEMA -> ValidationResult("not_applicable", null)
            apiResult.responseText == null -> ValidationResult("not_run", "Проверка не выполнена: ответ от OpenAI не получен.")
            else -> validateRecipes(apiResult.responseText)
        }
        return persist(buildResult(
            timestamp = timestamp,
            variant = variant,
            runType = runType,
            runNumber = runNumber,
            prompt = prompt,
            requestParameters = requestParameters,
            responseText = apiResult.responseText,
            rawApiResponse = apiResult.rawApiResponse,
            latencyMs = latencyMs,
            promptTokens = apiResult.promptTokens,
            completionTokens = apiResult.completionTokens,
            totalTokens = apiResult.totalTokens,
            finishReason = apiResult.finishReason,
            validation = validation,
            error = apiResult.error,
        ))
    }

    fun recordFailure(prompt: String, variantId: String, runType: String, runNumber: Int, message: String?): JsonObject {
        val variant = Day2Variant.fromId(variantId) ?: return errorResult(prompt, variantId, "Неизвестный вариант эксперимента.")
        val timestamp = Instant.now()
        val requestParameters = runCatching { client.requestParameters(prompt, variant) }
            .getOrElse { JsonObject(emptyMap()) }
        return persist(buildResult(
            timestamp = timestamp,
            variant = variant,
            runType = runType,
            runNumber = runNumber,
            prompt = prompt,
            requestParameters = requestParameters,
            responseText = null,
            rawApiResponse = JsonNull,
            latencyMs = 0,
            promptTokens = null,
            completionTokens = null,
            totalTokens = null,
            finishReason = null,
            validation = if (variant == Day2Variant.JSON_SCHEMA) {
                ValidationResult("not_run", "Проверка не выполнена: ответ от OpenAI не получен.")
            } else {
                ValidationResult("not_applicable", null)
            },
            error = safeError(message),
        ))
    }

    private fun buildResult(
        timestamp: Instant,
        variant: Day2Variant,
        runType: String,
        runNumber: Int?,
        prompt: String,
        requestParameters: JsonObject,
        responseText: String?,
        rawApiResponse: JsonElement,
        latencyMs: Long,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
        finishReason: String?,
        validation: ValidationResult,
        error: String?,
    ): JsonObject = buildJsonObject {
        put("timestamp", timestamp.toString())
        put("experiment_variant", variant.id)
        put("experiment_variant_name", variant.title)
        put("run_type", runType)
        put("run_number", runNumber?.let(::JsonPrimitive) ?: JsonNull)
        put("model", client.modelName())
        put("prompt", prompt)
        put("request_parameters", requestParameters)
        put("response_text", responseText?.let(::JsonPrimitive) ?: JsonNull)
        put("raw_api_response", rawApiResponse)
        put("latency_ms", latencyMs)
        put("token_usage", buildJsonObject {
            put("prompt_tokens", promptTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("completion_tokens", completionTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("total_tokens", totalTokens?.let(::JsonPrimitive) ?: JsonNull)
        })
        put("finish_reason", finishReason?.let(::JsonPrimitive) ?: JsonNull)
        put("validation_result", validation.toJson())
        put("error", error?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun errorResult(prompt: String, variantId: String, message: String): JsonObject = buildJsonObject {
        put("timestamp", Instant.now().toString())
        put("experiment_variant", variantId)
        put("experiment_variant_name", "Неизвестный вариант")
        put("model", client.modelName())
        put("prompt", prompt)
        put("request_parameters", JsonObject(emptyMap()))
        put("response_text", JsonNull)
        put("raw_api_response", JsonNull)
        put("latency_ms", 0)
        put("token_usage", buildJsonObject {
            put("prompt_tokens", JsonNull)
            put("completion_tokens", JsonNull)
            put("total_tokens", JsonNull)
        })
        put("finish_reason", JsonNull)
        put("validation_result", ValidationResult("not_applicable", null).toJson())
        put("error", message)
    }

    private fun persist(result: JsonObject): JsonObject = try {
        Files.createDirectories(resultDirectory)
        val timestamp = Instant.parse(result.getValue("timestamp").jsonPrimitive.content)
        val fileStamp = FILE_STAMP.format(timestamp)
        val file = resultDirectory.resolve("test-$fileStamp-${System.nanoTime()}.json")
        Files.writeString(file, outputJson.encodeToString(JsonObject.serializer(), result))
        JsonObject(result + ("result_file" to JsonPrimitive(file.toString())))
    } catch (exception: Exception) {
        JsonObject(result + ("persistence_error" to JsonPrimitive("Не удалось сохранить результат: ${exception.message ?: "ошибка записи файла"}")))
    }

    private fun validateRecipes(text: String): ValidationResult {
        return try {
            val root = Json.parseToJsonElement(text).jsonObject
            if (root.keys != setOf("recipes")) {
                return ValidationResult("fail", "В корневом объекте должно быть только поле recipes.")
            }
            val recipes = root["recipes"] as? JsonArray
                ?: return ValidationResult("fail", "Поле recipes должно быть массивом.")
            if (recipes.size != 3) {
                return ValidationResult("fail", "Ожидалось ровно 3 рецепта, получено ${recipes.size}.")
            }

            val expectedFields = setOf("name", "ingredients", "instructions", "preparation_time")
            recipes.forEachIndexed { index, recipeElement ->
                val recipe = recipeElement as? JsonObject
                    ?: return ValidationResult("fail", "Рецепт ${index + 1} должен быть объектом.")
                if (recipe.keys != expectedFields) {
                    val missing = expectedFields - recipe.keys
                    val extra = recipe.keys - expectedFields
                    val detail = buildList {
                        if (missing.isNotEmpty()) add("отсутствуют: ${missing.joinToString()}")
                        if (extra.isNotEmpty()) add("лишние: ${extra.joinToString()}")
                    }.joinToString("; ")
                    return ValidationResult("fail", "Рецепт ${index + 1}: $detail.")
                }
                val name = recipe["name"] as? JsonPrimitive
                if (name == null || !name.isString) {
                    return ValidationResult("fail", "Поле name в рецепте ${index + 1} должно быть строкой.")
                }
                for (field in listOf("ingredients", "instructions")) {
                    val values = recipe[field] as? JsonArray
                        ?: return ValidationResult("fail", "Поле $field в рецепте ${index + 1} должно быть массивом строк.")
                    if (values.any { it !is JsonPrimitive || !it.jsonPrimitive.isString }) {
                        return ValidationResult("fail", "Поле $field в рецепте ${index + 1} должно содержать только строки.")
                    }
                }
                val time = recipe["preparation_time"] as? JsonPrimitive
                if (time == null || time.isString || time.intOrNull == null) {
                    return ValidationResult("fail", "Поле preparation_time в рецепте ${index + 1} должно быть целым числом.")
                }
            }
            ValidationResult("pass", "JSON корректен: три рецепта, обязательные поля и типы проверены; дополнительных полей нет.")
        } catch (_: Exception) {
            ValidationResult("fail", "Ответ не является корректным JSON.")
        }
    }

    private fun safeError(message: String?): String = (message ?: "Не удалось выполнить запрос к OpenAI.")
        .replace(Regex("(?i)\\bsk-[^\\s,;]+"), "[REDACTED]")

    companion object {
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
    }
}

private val DINNER_FORMAT_INSTRUCTIONS = """
Верни ровно три рецепта. Для каждого укажи название блюда, ингредиенты, пошаговую инструкцию и время приготовления в минутах.
Используй понятную одинаковую структуру с нумерованными рецептами и шагами. Отвечай по-русски.
""".trimIndent()
