package day5

import day3.Day3ValidationStatus
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Day5RunManagerTest {
    @Test
    fun `full run executes fifteen combinations sequentially and persists each result`() = runBlocking {
        val root = Files.createTempDirectory("day5-full-success")
        val activeCalls = AtomicInteger()
        val maxActive = AtomicInteger()
        val sequence = mutableListOf<Pair<String, String>>()
        val runner = Day5ExperimentRunner { model, task ->
            val active = activeCalls.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, active) }
            synchronized(sequence) { sequence += model to task }
            delay(8)
            activeCalls.decrementAndGet()
            fakeResult(model, task)
        }
        val manager = Day5RunManager(runner, Day5ResultStore(root), Mutex())

        val started = assertNotNull(manager.start().state)
        val finished = awaitFinished(manager, started.runId)
        val expected = Day5TaskCatalog.comparisonTasks.flatMap { task -> Day5Config.models.map { it.id to task.id } }

        assertEquals(Day5RunStatus.COMPLETED, finished.status)
        assertEquals(15, finished.completed)
        assertEquals(expected, sequence)
        assertEquals(1, maxActive.get())
        assertEquals(15, Files.list(root.resolve(started.runId).resolve("runs")).use { it.count().toInt() })
        assertTrue(Files.isRegularFile(root.resolve(started.runId).resolve("config.json")))
    }

    @Test
    fun `a failed combination is recorded and remaining combinations continue`() = runBlocking {
        val root = Files.createTempDirectory("day5-full-errors")
        val calls = AtomicInteger()
        val manager = Day5RunManager(Day5ExperimentRunner { model, task ->
            if (calls.incrementAndGet() == 4) error("synthetic failure")
            fakeResult(model, task)
        }, Day5ResultStore(root), Mutex())

        val started = assertNotNull(manager.start().state)
        val finished = awaitFinished(manager, started.runId)

        assertEquals(Day5RunStatus.COMPLETED_WITH_ERRORS, finished.status)
        assertEquals(15, finished.completed)
        assertEquals(15, finished.records.size)
        assertTrue(finished.records.any { it["error"]?.toString()?.contains("synthetic failure") == true })
        assertTrue(Files.isRegularFile(root.resolve(started.runId).resolve("runs/04.json")))
        assertTrue(Files.isRegularFile(root.resolve(started.runId).resolve("runs/15.json")))
    }

    @Test
    fun `second full run is rejected and progress advances`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val root = Files.createTempDirectory("day5-full-lock")
        val mutex = Mutex()
        val manager = Day5RunManager(Day5ExperimentRunner { model, task ->
            entered.complete(Unit)
            release.await()
            fakeResult(model, task)
        }, Day5ResultStore(root), mutex)

        val started = assertNotNull(manager.start().state)
        entered.await()
        assertNull(manager.start().state)
        assertEquals(0, manager.get(started.runId)!!.completed)
        release.complete(Unit)
        val finished = awaitFinished(manager, started.runId)
        assertEquals(15, finished.completed)
        assertEquals(100, finished.progressPercent)
        assertEquals(Day5RunStatus.COMPLETED, finished.status)
    }

    @Test
    fun `new manager reads completed saved state without rerunning requests`() = runBlocking {
        val root = Files.createTempDirectory("day5-full-recovery")
        val calls = AtomicInteger()
        val runner = Day5ExperimentRunner { model, task -> calls.incrementAndGet(); fakeResult(model, task) }
        val first = Day5RunManager(runner, Day5ResultStore(root), Mutex())
        val started = assertNotNull(first.start().state)
        val finished = awaitFinished(first, started.runId)

        val restored = Day5RunManager(runner, Day5ResultStore(root), Mutex())
        val loaded = assertNotNull(restored.get(started.runId))
        assertEquals(Day5RunStatus.COMPLETED, loaded.status)
        assertEquals(15, loaded.records.size)
        assertEquals(15, calls.get())
        assertNull(restored.active())
        assertFalse(Files.readString(root.resolve(started.runId).resolve("config.json")).contains("Authorization"))
    }

    private suspend fun awaitFinished(manager: Day5RunManager, runId: String): Day5RunState {
        repeat(500) {
            val state = manager.get(runId)!!
            if (state.status in setOf(Day5RunStatus.COMPLETED, Day5RunStatus.COMPLETED_WITH_ERRORS, Day5RunStatus.FAILED)) return state
            delay(10)
        }
        error("Day 5 full run did not finish in time")
    }

    private fun fakeResult(model: String, task: String) = Day5ExperimentResult(
        timestamp = Instant.now().toString(), requestedModelId = model, actualModelId = model,
        taskId = task, prompt = "fake prompt", answer = "fake answer", explanation = "fake explanation",
        responseStatus = "completed", failureType = null, error = null, latencyMs = 1,
        inputTokens = 2, cachedInputTokens = 0, outputTokens = 3, reasoningTokens = 1,
        totalTokens = 5, estimatedCostUsd = 0.000001, validationStatus = Day3ValidationStatus.CORRECT,
        validationDetails = "fake validation",
    )
}
