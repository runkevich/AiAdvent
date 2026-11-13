package day6

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

internal fun Route.day6Routes(agent: Day6Agent = Day6Agent()) {
    post("/api/day6/chat") {
        val request = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val prompt = request?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
        if (prompt.isNullOrBlank() || prompt.length > 4_000) {
            call.respondText("""{"error":"Введите сообщение длиной до 4000 символов."}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val result = runCatching { agent.respond(prompt) }
        result.fold(
            onSuccess = { value ->
                val body = buildJsonObject {
                    put("answer", value.answer)
                    put("toolCalls", JsonArray(value.toolCalls.map { tool -> buildJsonObject {
                        put("name", tool.name); put("arguments", tool.arguments); put("result", tool.result)
                    } }))
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            },
            onFailure = { error ->
                val message = error.message?.take(500) ?: "Не удалось выполнить запрос агента."
                call.respondText(buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, HttpStatusCode.BadGateway)
            },
        )
    }
}
