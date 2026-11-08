package day2

import shared.ChatCompletionResult
import shared.ChatCompletionsTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal class ChatCompletionsClient(
    private val model: String = System.getenv("OPENAI_MODEL")?.takeIf(String::isNotBlank) ?: "gpt-4.1",
) {
    private val transport = ChatCompletionsTransport()

    fun modelName(): String = model

    fun requestParameters(prompt: String, variant: Day2Variant): JsonObject = createRequest(prompt, variant)

    suspend fun complete(prompt: String, variant: Day2Variant): ChatCompletionResult =
        transport.complete(requestParameters(prompt, variant))

    private fun createRequest(prompt: String, variant: Day2Variant): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            if (variant != Day2Variant.BASELINE) {
                add(JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(variant.instructions))))
            }
            val userPrompt = if (variant == Day2Variant.STOP_SEQUENCE) {
                "$prompt\n\nПосле ответа выведи маркер <<<END_OF_RESPONSE>>>."
            } else {
                prompt
            }
            add(JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(userPrompt))))
        }
        if (variant.usesTokenLimit) put("max_completion_tokens", OUTPUT_TOKEN_LIMIT)
        if (variant == Day2Variant.STOP_SEQUENCE) put("stop", STOP_SEQUENCE)
        if (variant == Day2Variant.JSON_SCHEMA) {
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "dinner_recipes")
                    put("strict", true)
                    put("schema", Day2Variant.recipeSchema)
                }
            }
        }
    }

    companion object {
        const val OUTPUT_TOKEN_LIMIT = 1200
        const val STOP_SEQUENCE = "<<<END_OF_RESPONSE>>>"
    }
}
