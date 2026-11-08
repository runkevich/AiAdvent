import kotlinx.serialization.Serializable

/** The canonical Day 3 task catalog. The browser will consume this catalog from the server in a later stage. */
@Serializable
internal data class Day3Task(
    val id: String,
    val category: String,
    val text: String,
    val expectedAnswer: String,
    val expectedAnswerType: Day3ExpectedAnswerType,
)

@Serializable
internal enum class Day3ExpectedAnswerType {
    CURRENCY,
    NUMBER,
    NUMBER_SEQUENCE,
    DURATION,
    LOGICAL,
    SPEED,
    WEEKDAY,
}

@Serializable
internal enum class Day3Method(val displayName: String, val apiCalls: Int) {
    DIRECT("Direct", 1),
    STEP_BY_STEP("Step-by-step", 1),
    PROMPT_GENERATION("Prompt Generation", 2),
    EXPERT_PANEL("Expert Panel", 1);

    companion object {
        fun fromId(id: String): Day3Method? = entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }
}

@Serializable
internal enum class Day3ReasoningEffort(val apiValue: String) {
    LOW("low"),
    MAX("max");

    companion object {
        fun fromValue(value: String): Day3ReasoningEffort? = entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) }
    }
}

@Serializable
internal enum class Day3ValidationStatus {
    CORRECT,
    INCORRECT,
    NEEDS_REVIEW,
}

@Serializable
internal data class Day3AnswerValidation(
    val status: Day3ValidationStatus,
    val details: String,
)

/** Persistable result shape for a single API call; orchestration and persistence are added in a later stage. */
@Serializable
internal data class Day3ApiCallResult(
    val timestamp: String,
    val experimentId: String? = null,
    val taskId: String,
    val method: Day3Method,
    val reasoningEffort: Day3ReasoningEffort,
    val model: String,
    val prompt: String,
    val taskCategory: String? = null,
    val taskText: String? = null,
    val callPurpose: String? = null,
    val expectedAnswer: String? = null,
    val generatedPrompt: String? = null,
    val responseText: String? = null,
    val modelAnswer: String? = null,
    val parsedAnswer: String? = null,
    val explanation: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val latencyMs: Long,
    val estimatedCostUsd: Double? = null,
    val correctnessStatus: String? = null,
    val validationStatus: Day3ValidationStatus? = null,
    val validationDetails: String? = null,
    val requestParameters: kotlinx.serialization.json.JsonElement? = null,
    val rawApiResponse: kotlinx.serialization.json.JsonElement? = null,
    val apiStatus: Int? = null,
    val responseStatus: String? = null,
    val finishReason: String? = null,
    val incompleteReason: String? = null,
    val incompleteDetails: kotlinx.serialization.json.JsonElement? = null,
    val error: String? = null,
)

@Serializable
internal data class Day3MethodOutcome(
    val timestamp: String,
    val experimentId: String? = null,
    val taskId: String,
    val taskCategory: String,
    val taskText: String,
    val method: Day3Method,
    val reasoningEffort: Day3ReasoningEffort,
    val model: String,
    val prompt: String,
    val generatedPrompt: String? = null,
    val responseText: String? = null,
    val answer: String? = null,
    val explanation: String? = null,
    val expectedAnswer: String,
    val validationStatus: Day3ValidationStatus,
    val validationDetails: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val totalTokens: Int? = null,
    val latencyMs: Long,
    val estimatedCostUsd: Double? = null,
    val apiStatus: Int? = null,
    val responseStatus: String? = null,
    val error: String? = null,
    val calls: List<Day3ApiCallResult>,
    val resultFiles: List<String>,
    val persistenceError: String? = null,
)

internal object Day3TaskCatalog {
    val all: List<Day3Task> = listOf(
        Day3Task(
            id = "shopping",
            category = "Shopping",
            text = "В магазине товар стоит 250 PLN. На него действует скидка 20%. Сколько нужно заплатить после скидки?",
            expectedAnswer = "200 PLN",
            expectedAnswerType = Day3ExpectedAnswerType.CURRENCY,
        ),
        Day3Task(
            id = "mathematics",
            category = "Mathematics",
            text = "Сколько составляет 15% от 240?",
            expectedAnswer = "36",
            expectedAnswerType = Day3ExpectedAnswerType.NUMBER,
        ),
        Day3Task(
            id = "number_sequence",
            category = "Number Sequence",
            text = "Последовательность построена по правилу: каждое следующее число получается умножением двух последовательных натуральных чисел. Продолжи последовательность: 2, 6, 12, 20, 30, ?",
            expectedAnswer = "42",
            expectedAnswerType = Day3ExpectedAnswerType.NUMBER_SEQUENCE,
        ),
        Day3Task(
            id = "productivity",
            category = "Productivity",
            text = "Три машины производят три детали за три минуты. Сколько времени потребуется 100 таким же машинам, чтобы произвести 100 деталей, если каждая машина работает с той же производительностью?",
            expectedAnswer = "3 минуты",
            expectedAnswerType = Day3ExpectedAnswerType.DURATION,
        ),
        Day3Task(
            id = "logic",
            category = "Logic",
            text = "Все тюльпаны являются цветами. Некоторые цветы быстро вянут. Можно ли на основании этой информации заключить, что некоторые тюльпаны быстро вянут?",
            expectedAnswer = "Нет, такой вывод из условий не следует.",
            expectedAnswerType = Day3ExpectedAnswerType.LOGICAL,
        ),
        Day3Task(
            id = "budget",
            category = "Budget",
            text = "Ты покупаешь: 3 кг яблок по 8 PLN за килограмм и 2 кг груш по 12 PLN за килограмм. Сколько стоит вся покупка?",
            expectedAnswer = "48 PLN",
            expectedAnswerType = Day3ExpectedAnswerType.CURRENCY,
        ),
        Day3Task(
            id = "average_speed",
            category = "Average Speed",
            text = "Автомобиль проехал 180 км за 3 часа. Какова его средняя скорость?",
            expectedAnswer = "60 км/ч",
            expectedAnswerType = Day3ExpectedAnswerType.SPEED,
        ),
        Day3Task(
            id = "calendar",
            category = "Calendar",
            text = "Сегодня среда. Какой день недели будет через 10 дней?",
            expectedAnswer = "суббота",
            expectedAnswerType = Day3ExpectedAnswerType.WEEKDAY,
        ),
    )

    fun find(id: String): Day3Task? = all.firstOrNull { it.id == id }
}
