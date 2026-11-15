package day10

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.*

data class Day10Branch(val name: String, val conversationId: String)
data class Day10Checkpoint(val checkpointId: String, val branchA: Day10Branch, val branchB: Day10Branch)

class Day10StateStore(private val root: Path = Path.of("results", "day10")) {
    private val json = Json { prettyPrint = true }

    @Synchronized fun saveCheckpoint(value: Day10Checkpoint) {
        Files.createDirectories(root)
        val file = root.resolve("${validId(value.checkpointId)}.json")
        val data = buildJsonObject {
            put("checkpointId", value.checkpointId)
            put("branchA", branchJson(value.branchA))
            put("branchB", branchJson(value.branchB))
        }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(JsonObject.serializer(), data))
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
    }

    fun loadCheckpoint(id: String): Day10Checkpoint {
        val data = Json.parseToJsonElement(Files.readString(root.resolve("${validId(id)}.json"))).jsonObject
        fun branch(key: String): Day10Branch {
            val value = data[key]!!.jsonObject
            return Day10Branch(value["name"]!!.jsonPrimitive.content, value["conversationId"]!!.jsonPrimitive.content)
        }
        return Day10Checkpoint(id, branch("branchA"), branch("branchB"))
    }

    @Synchronized fun loadFacts(id: String): JsonObject {
        val path = root.resolve("facts-${validId(id)}.json")
        if (!Files.exists(path)) return JsonObject(emptyMap())
        return Json.parseToJsonElement(Files.readString(path)).jsonObject
    }

    @Synchronized fun saveFacts(id: String, facts: JsonObject) {
        Files.createDirectories(root)
        val path = root.resolve("facts-${validId(id)}.json")
        val temp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temp, facts.toString())
        try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun branchJson(value: Day10Branch) = buildJsonObject { put("name", value.name); put("conversationId", value.conversationId) }
    private fun validId(value: String): String {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull()
        require(parsed != null && parsed.toString() == value.lowercase()) { "Некорректный идентификатор." }
        return value
    }
}
