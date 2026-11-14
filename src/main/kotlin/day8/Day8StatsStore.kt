package day8

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.serialization.json.*

class Day8StatsStore(private val root: Path = Path.of("results", "day8")) {
    @Synchronized
    fun load(conversationId: String): JsonArray {
        val path = pathFor(conversationId)
        if (!Files.isRegularFile(path)) return JsonArray(emptyList())
        return Json.parseToJsonElement(Files.readString(path)).jsonObject["turns"]?.jsonArray ?: JsonArray(emptyList())
    }

    @Synchronized
    fun append(conversationId: String, stats: Day8TurnStats) {
        Files.createDirectories(root)
        val turns = load(conversationId).toMutableList().apply { add(stats.toJson()) }
        val json = Json { prettyPrint = true }
        val record = buildJsonObject {
            put("conversationId", conversationId)
            put("updatedAt", java.time.Instant.now().toString())
            put("turns", JsonArray(turns))
        }
        val path = pathFor(conversationId)
        val temp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(JsonObject.serializer(), record), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun pathFor(conversationId: String): Path {
        val parsed = runCatching { UUID.fromString(conversationId) }.getOrNull()
        require(parsed != null && parsed.toString() == conversationId.lowercase()) { "Некорректный идентификатор диалога." }
        return root.resolve("$conversationId.json")
    }
}
