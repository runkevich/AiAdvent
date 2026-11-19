package day12

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.*

enum class ResponseLength(val label: String) { CONCISE("кратко"), DETAILED("подробно") }
enum class CodePreference(val label: String) { WITH_CODE("с кодом"), WITHOUT_CODE("без кода") }
enum class TonePreference(val label: String) { FORMAL("формально, на «вы»"), INFORMAL("неформально, на «ты»") }
enum class Day12Preset { CONCISE_CODE_INFORMAL, DETAILED_NO_CODE_FORMAL, CONCISE_NO_CODE_FORMAL }

data class Day12Profile(
    val id: String,
    val name: String,
    val length: ResponseLength,
    val code: CodePreference,
    val tone: TonePreference,
    val sources: JsonObject,
)

class Day12ProfileStore(private val root: Path = Path.of("results", "day12", "profiles")) {
    private val json = Json { prettyPrint = true }

    @Synchronized fun create(name: String, preset: Day12Preset): Day12Profile {
        val id = UUID.randomUUID().toString()
        val values = Day12Profiles.forPreset(preset)
        val profile = Day12Profile(id, name.trim().ifBlank { values.defaultName }, values.length, values.code, values.tone, sources("вручную", "вручную", "вручную"))
        save(profile)
        return profile
    }

    @Synchronized fun load(id: String): Day12Profile {
        val path = pathFor(id)
        require(Files.isRegularFile(path)) { "Профиль не найден." }
        val value = Json.parseToJsonElement(Files.readString(path)).jsonObject
        return Day12Profile(
            id = id,
            name = value["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            length = enum(value, "length", ResponseLength.CONCISE),
            code = enum(value, "code", CodePreference.WITH_CODE),
            tone = enum(value, "tone", TonePreference.INFORMAL),
            sources = value["sources"]?.jsonObject ?: sources("вручную", "вручную", "вручную"),
        )
    }

    @Synchronized fun save(profile: Day12Profile) {
        Files.createDirectories(root)
        val value = buildJsonObject {
            put("id", profile.id); put("name", profile.name); put("length", profile.length.name); put("code", profile.code.name); put("tone", profile.tone.name); put("sources", profile.sources)
        }
        val path = pathFor(profile.id); val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(JsonObject.serializer(), value))
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private inline fun <reified T : Enum<T>> enum(value: JsonObject, key: String, fallback: T): T = value[key]?.jsonPrimitive?.contentOrNull?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
    private fun pathFor(id: String): Path { require(runCatching { UUID.fromString(id) }.getOrNull()?.toString() == id.lowercase()) { "Некорректный id профиля." }; return root.resolve("$id.json") }
}

private fun sources(length: String, code: String, tone: String) = buildJsonObject { put("length", length); put("code", code); put("tone", tone) }

object Day12Profiles {
    data class Values(val defaultName: String, val length: ResponseLength, val code: CodePreference, val tone: TonePreference)
    fun forPreset(preset: Day12Preset) = when (preset) {
        Day12Preset.CONCISE_CODE_INFORMAL -> Values("Ира · быстро и с кодом", ResponseLength.CONCISE, CodePreference.WITH_CODE, TonePreference.INFORMAL)
        Day12Preset.DETAILED_NO_CODE_FORMAL -> Values("Анна · подробно и формально", ResponseLength.DETAILED, CodePreference.WITHOUT_CODE, TonePreference.FORMAL)
        Day12Preset.CONCISE_NO_CODE_FORMAL -> Values("Олег · кратко и формально", ResponseLength.CONCISE, CodePreference.WITHOUT_CODE, TonePreference.FORMAL)
    }
}
