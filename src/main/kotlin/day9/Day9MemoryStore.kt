package day9

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

data class Day9MemoryState(
    val summary: String = "",
    val summarizedTurns: Int = 0,
    val summaryApiCalls: Int = 0,
    val updatedAt: String? = null,
)

class Day9MemoryStore(private val root: Path = Path.of("results", "day9", "memory")) {
    private val json = Json { prettyPrint = true }

    @Synchronized
    fun load(conversationId: String): Day9MemoryState {
        val path = pathFor(conversationId)
        if (!Files.isRegularFile(path)) return Day9MemoryState()
        val value = Json.parseToJsonElement(Files.readString(path)).jsonObject
        return Day9MemoryState(
            summary = value["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            summarizedTurns = value["summarizedTurns"]?.jsonPrimitive?.intOrNull ?: 0,
            summaryApiCalls = value["summaryApiCalls"]?.jsonPrimitive?.intOrNull ?: 0,
            updatedAt = value["updatedAt"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Synchronized
    fun save(conversationId: String, state: Day9MemoryState): Day9MemoryState {
        Files.createDirectories(root)
        val updated = state.copy(updatedAt = Instant.now().toString())
        val value = buildJsonObject {
            put("conversationId", conversationId)
            put("summary", updated.summary)
            put("summarizedTurns", updated.summarizedTurns)
            put("summaryApiCalls", updated.summaryApiCalls)
            put("updatedAt", updated.updatedAt!!)
        }
        val path = pathFor(conversationId)
        val temp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(JsonObject.serializer(), value), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        return updated
    }

    private fun pathFor(conversationId: String): Path {
        val parsed = runCatching { UUID.fromString(conversationId) }.getOrNull()
        require(parsed != null && parsed.toString() == conversationId.lowercase()) { "Некорректный идентификатор диалога." }
        return root.resolve("$conversationId.json")
    }
}
