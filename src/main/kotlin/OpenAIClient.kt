import shared.ApiKeyProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OpenAIClient(
    private val model: String = "gpt-4.1",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generateResponse(prompt: String): String {
        val apiKey = ApiKeyProvider.get()

        val client = HttpClient(CIO)
        try {
            val response = client.post("https://api.openai.com/v1/responses") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("model", model)
                        put("input", prompt)
                    }.toString(),
                )
            }
            val responseBody = response.bodyAsText()

            if (response.status.value !in 200..299) {
                val message = runCatching {
                    json.parseToJsonElement(responseBody)
                        .jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                throw OpenAIRequestException(
                    "OpenAI API returned HTTP ${response.status.value}" +
                        (message?.let { ": $it" } ?: "."),
                )
            }

            return extractOutputText(responseBody)
        } catch (exception: ClientRequestException) {
            throw OpenAIRequestException("OpenAI API rejected the request: ${exception.message}", exception)
        } catch (exception: ServerResponseException) {
            throw OpenAIRequestException("OpenAI API encountered a server error: ${exception.message}", exception)
        } finally {
            client.close()
        }
    }

    private fun extractOutputText(responseBody: String): String {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val text = root["output"]?.jsonArray
            ?.flatMap { item -> item.jsonObject["content"]?.jsonArray.orEmpty() }
            ?.filter { content -> content.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
            ?.mapNotNull { content -> content.jsonObject["text"]?.jsonPrimitive?.content }
            ?.joinToString(separator = "\n")
            ?.trim()

        return text?.takeIf(String::isNotEmpty)
            ?: throw OpenAIRequestException("OpenAI returned a successful response without generated text.")
    }
}

class OpenAIRequestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
