package day3

import java.util.Locale

internal object Day3AnswerValidator {
    fun validate(task: Day3Task, modelAnswer: String?): Day3AnswerValidation {
        val answer = modelAnswer?.trim().orEmpty()
        if (answer.isEmpty()) return review("Ответ не удалось разобрать; нужна ручная проверка.")

        return when (task.expectedAnswerType) {
            Day3ExpectedAnswerType.CURRENCY -> validateNumeric(task, answer, requireCurrency = true)
            Day3ExpectedAnswerType.NUMBER -> validateNumeric(task, answer)
            Day3ExpectedAnswerType.NUMBER_SEQUENCE -> validateNumeric(task, answer)
            Day3ExpectedAnswerType.DURATION -> validateNumeric(task, answer, requireDuration = true)
            Day3ExpectedAnswerType.SPEED -> validateNumeric(task, answer, requireSpeed = true)
            Day3ExpectedAnswerType.LOGICAL -> validateLogic(answer)
            Day3ExpectedAnswerType.WEEKDAY -> validateWeekday(answer)
        }
    }

    private fun validateNumeric(
        task: Day3Task,
        answer: String,
        requireCurrency: Boolean = false,
        requireDuration: Boolean = false,
        requireSpeed: Boolean = false,
    ): Day3AnswerValidation {
        val expected = numberTokens(task.expectedAnswer).firstOrNull()
            ?: return review("Для эталонного ответа не удалось распознать число.")
        val actual = numberTokens(answer).firstOrNull()
            ?: return review("В ответе не удалось однозначно найти число.")
        if (kotlin.math.abs(expected - actual) > 1e-9) {
            return incorrect("Ожидалось ${task.expectedAnswer}; в ответе указано $actual.")
        }

        val unitPresent = when {
            requireCurrency -> CURRENCY_UNIT.containsMatchIn(answer)
            requireDuration -> DURATION_UNIT.containsMatchIn(answer)
            requireSpeed -> SPEED_UNIT.containsMatchIn(answer)
            else -> true
        }
        if (!unitPresent) return review("Числовое значение совпадает, но единица измерения не указана или не распознана.")
        return correct("Числовой ответ совпадает с эталоном${if (requireCurrency || requireDuration || requireSpeed) " и единица измерения распознана" else ""}.")
    }

    private fun validateLogic(answer: String): Day3AnswerValidation {
        val text = answer.lowercase(Locale.ROOT)
        val negative = listOf("нет", "нельзя", "невозможно", "не следует", "не вытекает", "недостаточно данных").any(text::contains)
        val positive = POSITIVE_YES.containsMatchIn(text) ||
            listOf("можно заключить", "следует, что", "вытекает, что").any(text::contains)
        return when {
            negative && !positive -> correct("Ответ правильно указывает, что вывод о тюльпанах не следует из условий.")
            positive && !negative -> incorrect("Из приведённых условий нельзя заключить, что некоторые тюльпаны быстро вянут.")
            else -> review("Логический вывод сформулирован неоднозначно.")
        }
    }

    private fun validateWeekday(answer: String): Day3AnswerValidation {
        val text = answer.lowercase(Locale.ROOT)
        val matched = WEEKDAY_PATTERNS.entries.filter { (_, pattern) -> pattern.containsMatchIn(text) }.map { it.key }.distinct()
        return when {
            matched.size > 1 -> review("В ответе упомянуто несколько дней недели.")
            matched.isEmpty() -> review("Не удалось определить день недели в ответе.")
            matched.single() == "saturday" -> correct("Указана суббота.")
            else -> incorrect("Ожидалась суббота, но указан другой день недели.")
        }
    }

    private fun numberTokens(text: String): List<Double> = NUMBER.findAll(text)
        .mapNotNull { match -> match.value.replace(',', '.').toDoubleOrNull() }
        .toList()

    private fun correct(message: String) = Day3AnswerValidation(Day3ValidationStatus.CORRECT, message)
    private fun incorrect(message: String) = Day3AnswerValidation(Day3ValidationStatus.INCORRECT, message)
    private fun review(message: String) = Day3AnswerValidation(Day3ValidationStatus.NEEDS_REVIEW, message)

    private val NUMBER = Regex("(?<![\\p{L}\\d])-?\\d+(?:[.,]\\d+)?")
    private val CURRENCY_UNIT = Regex("(?iu)(\\bpln\\b|zł|\\bzl\\b|злот)")
    private val DURATION_UNIT = Regex("(?iu)(\\bmin(?:ute|utes)?\\b|минут\\w*|мин\\b)")
    private val SPEED_UNIT = Regex("(?iu)(km\\s*/\\s*h|kmh|km\\s+per\\s+hour|км\\s*/\\s*ч|км\\s+в\\s+час|километр\\w*\\s+в\\s+час)")
    private val WEEKDAY_PATTERNS = mapOf(
        "monday" to Regex("(?iu)понедельник[а-яё]*|\\bmonday\\b"),
        "tuesday" to Regex("(?iu)вторник[а-яё]*|\\btuesday\\b"),
        "wednesday" to Regex("(?iu)сред[а-яё]*|\\bwednesday\\b"),
        "thursday" to Regex("(?iu)четверг[а-яё]*|\\bthursday\\b"),
        "friday" to Regex("(?iu)пятниц[а-яё]*|\\bfriday\\b"),
        "saturday" to Regex("(?iu)суббот[а-яё]*|\\bsaturday\\b"),
        "sunday" to Regex("(?iu)воскресень[а-яё]*|\\bsunday\\b"),
    )
    private val POSITIVE_YES = Regex("(?iu)(^|[^а-яё])да([^а-яё]|$)")
}
