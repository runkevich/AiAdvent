package day6

import shared.ApiKeyProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class Day6AgentResult(val answer: String, val toolCalls: List<Day6ToolCall>)
data class Day6ToolCall(val name: String, val arguments: String, val result: String)

/** A small agent that runs model-requested local functions until a final answer is returned. */
class Day6Agent(
    private val model: String = "gpt-4.1",
    private val request: suspend (JsonObject) -> JsonObject = ::requestOpenAi,
    private val maxToolRounds: Int = 8,
) {
    suspend fun respond(prompt: String): Day6AgentResult {
        require(prompt.isNotBlank()) { "Введите сообщение." }
        var response = request(buildRequest(prompt))
        val calls = mutableListOf<Day6ToolCall>()

        repeat(maxToolRounds + 1) { round ->
            val output = response["output"]?.jsonArray.orEmpty()
            val functionCalls = output.mapNotNull { item ->
                item.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "function_call" }
            }
            if (functionCalls.isEmpty()) return Day6AgentResult(extractText(output), calls)
            if (round == maxToolRounds) error("Агент достиг лимита вызовов инструментов ($maxToolRounds), но ответ не завершён.")

            val previousId = response["id"]?.jsonPrimitive?.contentOrNull
                ?: error("API не вернул id ответа для продолжения цикла инструментов.")
            val outputs = functionCalls.map { call ->
                val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val arguments = call["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = runCatching { Day6Tools.execute(name, arguments) }
                    .getOrElse { "Ошибка инструмента: ${it.message ?: "не удалось выполнить"}" }
                calls += Day6ToolCall(name, arguments, result)
                buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", call["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("output", result)
                }
            }
            response = request(buildContinueRequest(previousId, outputs))
        }
        error("Агент не смог завершить ответ.")
    }

    private fun buildRequest(prompt: String) = buildJsonObject {
        put("model", model)
        put("instructions", "Ты полезный русскоязычный помощник. Используй инструменты, когда они помогают ответить. Дождись результата инструмента и дай пользователю краткий итог.")
        put("input", prompt)
        put("tools", Day6Tools.schemas)
        put("tool_choice", "auto")
    }

    private fun buildContinueRequest(previousId: String, outputs: List<JsonObject>) = buildJsonObject {
        put("model", model)
        put("instructions", "Ты полезный русскоязычный помощник. Используй инструменты, когда они помогают ответить. Дождись результата инструмента и дай пользователю краткий итог.")
        put("previous_response_id", previousId)
        put("input", JsonArray(outputs.map { it as JsonElement }))
        put("tools", Day6Tools.schemas)
        put("tool_choice", "auto")
    }

    private fun extractText(output: List<JsonElement>): String = output
        .flatMap { item -> item.jsonObject["content"]?.jsonArray.orEmpty() }
        .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
        .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        .joinToString("\n")
        .ifBlank { "Модель не вернула текстовый ответ." }
}

private suspend fun requestOpenAi(body: JsonObject): JsonObject {
    val apiKey = ApiKeyProvider.get()
    val client = HttpClient(CIO)
    try {
        val response = client.post("https://api.openai.com/v1/responses") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val responseText = response.bodyAsText().replace(apiKey, "[REDACTED]")
        val parsed = runCatching { Json.parseToJsonElement(responseText).jsonObject }.getOrNull()
        if (response.status.value !in 200..299) {
            val message = parsed?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            throw IllegalStateException("OpenAI API вернул HTTP ${response.status.value}" + (message?.let { ": $it" } ?: "."))
        }
        return parsed ?: error("Не удалось разобрать ответ OpenAI.")
    } finally {
        client.close()
    }
}

private object Day6Tools {
    val schemas = buildJsonArray {
        add(function("current_time", "Возвращает текущее локальное время.", buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}); put("required", buildJsonArray {}); put("additionalProperties", false) }))
        add(function("calculator", "Выполняет простую арифметическую операцию над двумя числами.", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("operation", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("add"); add("subtract"); add("multiply"); add("divide") }) })
                put("a", buildJsonObject { put("type", "number") })
                put("b", buildJsonObject { put("type", "number") })
            })
            put("required", buildJsonArray { add("operation"); add("a"); add("b") })
            put("additionalProperties", false)
        }))
        add(function("search_tasks", "Ищет задачу в небольшом встроенном списке задач.", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
            put("required", buildJsonArray { add("query") })
            put("additionalProperties", false)
        }))
    }

    fun execute(name: String, arguments: String): String {
        val args = Json.parseToJsonElement(arguments).jsonObject
        return when (name) {
            "current_time" -> ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
            "calculator" -> {
                val a = args.getValue("a").jsonPrimitive.double
                val b = args.getValue("b").jsonPrimitive.double
                val result = when (args.getValue("operation").jsonPrimitive.content) {
                    "add" -> a + b
                    "subtract" -> a - b
                    "multiply" -> a * b
                    "divide" -> { require(b != 0.0) { "Деление на ноль невозможно." }; a / b }
                    else -> error("Неизвестная операция.")
                }
                result.toString()
            }
            "search_tasks" -> {
                val query = args.getValue("query").jsonPrimitive.content
                val tasks = listOf("День 6: первый агент с инструментами", "День 7: сохранение контекста", "День 8: подсчёт токенов", "День 9: сжатие истории", "День 10: сравнение стратегий")
                tasks.filter { it.contains(query, ignoreCase = true) || query.split(" ").any { word -> word.length > 2 && it.contains(word, true) } }
                    .takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "Подходящие задачи не найдены."
            }
            else -> error("Неизвестный инструмент: $name")
        }
    }

    private fun function(name: String, description: String, parameters: JsonObject) = buildJsonObject {
        put("type", "function")
        put("name", name)
        put("description", description)
        put("parameters", parameters)
        put("strict", true)
    }
}
