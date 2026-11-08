package day5

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.FileAlreadyExistsException
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class Day5ResultStore(
    private val resultRoot: Path = Path.of("results", "day5"),
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }

    fun createRun(model: Day5ModelConfig, task: Day5Task): Day5RunFiles {
        Files.createDirectories(resultRoot)
        var createdRunId: String? = null
        repeat(5) {
            if (createdRunId == null) {
                val candidate = UUID.randomUUID().toString()
                try {
                    Files.createDirectory(resultRoot.resolve(candidate))
                    createdRunId = candidate
                } catch (_: FileAlreadyExistsException) {
                    // Generate another ID if a directory with this UUID already exists.
                }
            }
        }
        val runId = requireNotNull(createdRunId) { "Не удалось создать уникальную папку запуска Day 5." }
        val directory = resultRoot.resolve(runId)
        return try {
            val config = buildJsonObject {
                put("run_id", runId)
                put("created_at", java.time.Instant.now().toString())
                put("api", Day5Config.api)
                put("model_id", model.id)
                put("model_name", model.displayName)
                put("model_tier", model.tier.name)
                put("reasoning_effort", Day5Config.reasoningEffort)
                put("max_output_tokens", Day5Config.maxOutputTokens)
                put("task_id", task.id)
                put("pricing_usd_per_million_tokens", buildJsonObject {
                    put("input", model.pricing.inputUsdPerMillion)
                    put("cached_input", model.pricing.cachedInputUsdPerMillion)
                    put("output", model.pricing.outputUsdPerMillion)
                })
                put("models", JsonArray(Day5Config.models.map { configuredModel ->
                    buildJsonObject {
                        put("id", configuredModel.id)
                        put("display_name", configuredModel.displayName)
                        put("tier", configuredModel.tier.name)
                        put("pricing_usd_per_million_tokens", buildJsonObject {
                            put("input", configuredModel.pricing.inputUsdPerMillion)
                            put("cached_input", configuredModel.pricing.cachedInputUsdPerMillion)
                            put("output", configuredModel.pricing.outputUsdPerMillion)
                        })
                    }
                }))
            }
            writeNew(directory.resolve("config.json"), config)
            Day5RunFiles(runId, directory)
        } catch (exception: Exception) {
            runCatching { Files.deleteIfExists(directory.resolve("config.json")) }
            runCatching { Files.deleteIfExists(directory) }
            throw exception
        }
    }

    fun saveResult(files: Day5RunFiles, result: Day5ExperimentResult): String {
        writeNew(files.directory.resolve("result.json"), json.encodeToJsonElement(Day5ExperimentResult.serializer(), result).jsonObject)
        return "results/day5/${files.runId}/result.json"
    }

    fun createFullRun(runId: String, config: JsonObject): Day5RunFiles {
        Files.createDirectories(resultRoot)
        val directory = resultRoot.resolve(runId)
        Files.createDirectory(directory)
        Files.createDirectories(directory.resolve("runs"))
        writeNew(directory.resolve("config.json"), config)
        return Day5RunFiles(runId, directory)
    }

    fun saveFullRunRecord(files: Day5RunFiles, index: Int, record: JsonObject): String {
        val filename = "runs/%02d.json".format(index)
        writeNew(files.directory.resolve(filename), record)
        return "results/day5/${files.runId}/$filename"
    }

    fun saveFullRunState(files: Day5RunFiles, state: JsonObject, summary: JsonObject) {
        writeReplace(files.directory.resolve("state.json"), state)
        writeReplace(files.directory.resolve("summary.json"), summary)
    }

    fun loadFullRuns(): List<Pair<Day5RunFiles, JsonObject>> {
        if (!Files.isDirectory(resultRoot)) return emptyList()
        return Files.list(resultRoot).use { paths ->
            paths.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve("state.json")) }
                .map { directory ->
                    val state = Json.parseToJsonElement(Files.readString(directory.resolve("state.json"))).jsonObject
                    Day5RunFiles(directory.fileName.toString(), directory) to state
                }
                .toList()
        }
    }

    private fun writeNew(path: Path, value: JsonObject) {
        Files.writeString(path, json.encodeToString(JsonObject.serializer(), value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun writeReplace(path: Path, value: JsonObject) {
        val temp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(JsonObject.serializer(), value), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        try {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal data class Day5RunFiles(val runId: String, val directory: Path)
