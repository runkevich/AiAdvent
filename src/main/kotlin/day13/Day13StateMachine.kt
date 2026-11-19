package day13

import java.time.Instant
import java.util.UUID

data class Day13AssistantReply(val message: String, val state: Day13TaskState, val transitionApplied: Boolean)

/** The only entry point for stage changes. No route or assistant prompt may set a stage directly. */
class Day13StateMachine(private val store: Day13TaskStore = Day13TaskStore()) {
    fun create(title: String, context: String): Day13TaskState {
        require(title.isNotBlank()) { "Введите название задачи." }
        val state = Day13TaskState(
            id = UUID.randomUUID().toString(), title = title.trim(), context = context.trim(), stage = TaskStage.PLANNING,
            currentStep = "Подготовить и согласовать план", expectedAction = TaskAction.APPROVE_PLAN,
            events = listOf(event("created", "Задача создана: сначала требуется утверждение плана.")),
        )
        store.save(state); return state
    }
    fun load(id: String) = store.load(id)
    fun transition(id: String, action: TaskAction): Day13TaskState {
        val current = store.load(id)
        check(!current.paused) { "Задача на паузе. Сначала продолжите её; этап и шаг сохранены." }
        check(current.expectedAction == action) { "Переход «${action.label()}» запрещён на этапе ${current.stage.label()}. Ожидается: ${current.expectedAction?.label() ?: "ничего"}." }
        val transitioned = when (action) {
            TaskAction.APPROVE_PLAN -> current.copy(stage = TaskStage.EXECUTION, currentStep = "Реализовать согласованный план", expectedAction = TaskAction.COMPLETE_IMPLEMENTATION)
            TaskAction.COMPLETE_IMPLEMENTATION -> current.copy(stage = TaskStage.VALIDATION, currentStep = "Проверить результат и зафиксировать валидацию", expectedAction = TaskAction.PASS_VALIDATION)
            TaskAction.PASS_VALIDATION -> current.copy(stage = TaskStage.DONE, currentStep = "Задача завершена", expectedAction = null)
        }
        val next = transitioned.withEvent(event("transition", "Применён переход: ${action.label()} → ${transitioned.stage.label()}."))
        store.save(next); return next
    }
    fun pause(id: String): Day13TaskState {
        val current = store.load(id); check(current.stage != TaskStage.DONE) { "Завершённую задачу нельзя поставить на паузу." }; check(!current.paused) { "Задача уже на паузе." }
        return current.copy(paused = true).withEvent(event("paused", "Пауза на этапе ${current.stage.label()}; текущий шаг сохранён.")).also(store::save)
    }
    fun resume(id: String): Day13TaskState {
        val current = store.load(id); check(current.paused) { "Задача не была поставлена на паузу." }
        return current.copy(paused = false).withEvent(event("resumed", "Продолжение с шага: ${current.currentStep}.")).also(store::save)
    }
    fun assistantRequest(id: String, text: String): Day13AssistantReply {
        val state = store.load(id)
        val request = text.lowercase()
        val wantsSkip = (state.stage == TaskStage.PLANNING && listOf("реализа", "выполн", "код").any(request::contains)) || (state.stage != TaskStage.DONE && listOf("заверш", "финал", "готов").any(request::contains))
        return if (wantsSkip) Day13AssistantReply("Не могу изменить этап по сообщению. Сейчас: ${state.stage.label()} — ${state.currentStep}. Разрешённое действие: ${state.expectedAction?.label() ?: "нет"}. Состояние не изменено.", state, false)
        else Day13AssistantReply("Сейчас: ${state.stage.label()} — ${state.currentStep}. Ожидаемое действие: ${state.expectedAction?.label() ?: "нет"}.${if (state.paused) " Задача на паузе." else ""}", state, false)
    }
    private fun Day13TaskState.withEvent(event: Day13Event) = copy(events = events + event)
    private fun event(type: String, message: String) = Day13Event(Instant.now().toString(), type, message)
}

private fun TaskStage.label() = when (this) { TaskStage.PLANNING -> "планирование"; TaskStage.EXECUTION -> "реализация"; TaskStage.VALIDATION -> "валидация"; TaskStage.DONE -> "завершено" }
private fun TaskAction.label() = when (this) { TaskAction.APPROVE_PLAN -> "утвердить план"; TaskAction.COMPLETE_IMPLEMENTATION -> "подтвердить реализацию"; TaskAction.PASS_VALIDATION -> "подтвердить валидацию" }
fun TaskStage.uiLabel() = label()
fun TaskAction.uiLabel() = label()
