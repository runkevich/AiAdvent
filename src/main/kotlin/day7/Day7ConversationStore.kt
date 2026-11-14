package day7

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

data class Day7TranscriptEntry(val type: String, val text: String, val toolName: String? = null, val details: String? = null)

/** Persists the full Responses API input sequence, including tool calls and tool results. */
class Day7ConversationStore(
    private val root: Path = Path.of("results", "day7"),
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun loadItems(conversationId: String): JsonArray {
        val path = pathFor(conversationId)
        if (!Files.isRegularFile(path)) return JsonArray(emptyList())
        val record = Json.parseToJsonElement(Files.readString(path)).jsonObject
        return record["items"]?.jsonArray ?: JsonArray(emptyList())
    }

    fun saveItems(conversationId: String, items: JsonArray) {
        Files.createDirectories(root)
        val path = pathFor(conversationId)
        val record = buildJsonObject {
            put("conversationId", conversationId)
            put("updatedAt", Instant.now().toString())
            put("items", items)
        }
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(JsonObject.serializer(), record), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun transcript(items: JsonArray): List<Day7TranscriptEntry> {
        val toolNames = mutableMapOf<String, String>()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "function_call" -> {
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    toolNames[callId] = name
                    Day7TranscriptEntry("tool_call", "Вызов инструмента: $name", name, item["arguments"]?.jsonPrimitive?.contentOrNull)
                }
                "function_call_output" -> {
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    Day7TranscriptEntry("tool_result", "Результат: ${item["output"]?.jsonPrimitive?.contentOrNull.orEmpty()}", toolNames[callId])
                }
                "message" -> {
                    val role = item["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val parts = item["content"]?.jsonArray.orEmpty().mapNotNull { part ->
                        val content = part.jsonObject
                        val type = content["type"]?.jsonPrimitive?.contentOrNull
                        if (type in setOf("input_text", "output_text", "text")) content["text"]?.jsonPrimitive?.contentOrNull else null
                    }
                    parts.joinToString("\n").takeIf(String::isNotBlank)?.let { Day7TranscriptEntry(role, it) }
                }
                else -> null
            }
        }
    }

    private fun pathFor(conversationId: String): Path {
        val parsed = runCatching { UUID.fromString(conversationId) }.getOrNull()
        require(parsed != null && parsed.toString() == conversationId.lowercase()) { "Некорректный идентификатор диалога." }
        return root.resolve("$conversationId.json")
    }
}
