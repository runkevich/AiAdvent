package day11

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.*

enum class MemoryLayer { SHORT_TERM, WORKING, LONG_TERM }

data class Day11MemoryState(
    val shortTerm: JsonArray = JsonArray(emptyList()),
    val working: JsonObject = JsonObject(emptyMap()),
    val longTerm: JsonObject = JsonObject(emptyMap()),
)

/** Each layer has its own file, so a clear or write can never overwrite another layer by accident. */
class Day11MemoryStore(private val root: Path = Path.of("results", "day11", "memory")) {
    private val json = Json { prettyPrint = true }

    @Synchronized fun load(id: String): Day11MemoryState {
        validId(id)
        return Day11MemoryState(
            shortTerm = loadArray(id, "short"),
            working = loadObject(id, "working"),
            longTerm = loadObject(id, "long"),
        )
    }

    @Synchronized fun save(id: String, state: Day11MemoryState) {
        Files.createDirectories(root)
        validId(id)
        write(id, "short", state.shortTerm)
        write(id, "working", state.working)
        write(id, "long", state.longTerm)
    }

    private fun loadArray(id: String, layer: String): JsonArray {
        val path = pathFor(id, layer)
        return if (Files.isRegularFile(path)) Json.parseToJsonElement(Files.readString(path)).jsonObject["value"]?.jsonArray ?: JsonArray(emptyList()) else JsonArray(emptyList())
    }
    private fun loadObject(id: String, layer: String): JsonObject {
        val path = pathFor(id, layer)
        return if (Files.isRegularFile(path)) Json.parseToJsonElement(Files.readString(path)).jsonObject["value"]?.jsonObject ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
    }
    private fun write(id: String, layer: String, value: JsonElement) {
        val path = pathFor(id, layer)
        val content = buildJsonObject { put("sessionId", id); put("layer", layer); put("value", value) }
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(JsonObject.serializer(), content))
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun pathFor(id: String, layer: String): Path {
        validId(id)
        return root.resolve("$id.$layer.json")
    }
    private fun validId(id: String) {
        val parsed = runCatching { UUID.fromString(id) }.getOrNull()
        require(parsed != null && parsed.toString() == id.lowercase()) { "Некорректный идентификатор сессии." }
    }
}
