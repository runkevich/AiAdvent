package day4

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import shared.ChatCompletionResult
import shared.ChatCompletionsTransport

/** Day 4 request configuration stays with the Day 4 experiment; HTTP transport is shared. */
internal class Day4ChatCompletionsClient(
    private val model: String = Day4Config.model,
    private val transport: ChatCompletionsTransport = ChatCompletionsTransport(),
) {
    fun modelName(): String = model

    fun requestParameters(prompt: String, temperature: Double): JsonObject {
        require(temperature in 0.0..2.0) { "Temperature должна быть в диапазоне 0.0–2.0." }
        return buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("temperature", temperature)
            put("top_p", Day4Config.topP)
            put("max_completion_tokens", Day4Config.maxCompletionTokens)
        }
    }

    suspend fun complete(prompt: String, temperature: Double): ChatCompletionResult =
        transport.complete(requestParameters(prompt, temperature))
}
