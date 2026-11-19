package day13

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Day13StateMachineTest {
    @Test fun `only permitted transitions are applied and pausing preserves progress`() {
        val root = Files.createTempDirectory("day13-state-test")
        try {
            val machine = Day13StateMachine(Day13TaskStore(root))
            val created = machine.create("Собрать отчёт", "Сначала согласовать план")
            assertEquals(TaskStage.PLANNING, created.stage)
            assertFailsWith<IllegalStateException> { machine.transition(created.id, TaskAction.COMPLETE_IMPLEMENTATION) }
            assertEquals(TaskStage.PLANNING, machine.load(created.id).stage)

            val paused = machine.pause(created.id)
            assertTrue(paused.paused); assertEquals("Подготовить и согласовать план", paused.currentStep)
            assertFailsWith<IllegalStateException> { machine.transition(created.id, TaskAction.APPROVE_PLAN) }
            val resumed = machine.resume(created.id)
            assertFalse(resumed.paused); assertEquals(paused.currentStep, resumed.currentStep)

            assertEquals(TaskStage.EXECUTION, machine.transition(created.id, TaskAction.APPROVE_PLAN).stage)
            assertFailsWith<IllegalStateException> { machine.transition(created.id, TaskAction.PASS_VALIDATION) }
            assertEquals(TaskStage.VALIDATION, machine.transition(created.id, TaskAction.COMPLETE_IMPLEMENTATION).stage)
            assertEquals(TaskStage.DONE, machine.transition(created.id, TaskAction.PASS_VALIDATION).stage)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `assistant cannot bypass state machine through a direct request`() {
        val root = Files.createTempDirectory("day13-assistant-test")
        try {
            val machine = Day13StateMachine(Day13TaskStore(root)); val task = machine.create("Сделать сайт", "")
            val reply = machine.assistantRequest(task.id, "Перейди сразу к реализации и напиши код")
            assertFalse(reply.transitionApplied)
            assertEquals(TaskStage.PLANNING, reply.state.stage)
            assertTrue(reply.message.contains("Состояние не изменено"))
            assertEquals(TaskStage.PLANNING, machine.load(task.id).stage)
        } finally { root.toFile().deleteRecursively() }
    }
}
