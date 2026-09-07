// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.decisions

/**
 * Принятое решение как файл в репозитории проекта.
 *
 * Зачем вообще: код отвечает на вопрос «как сделано» и молчит о том, **что было отвергнуто и
 * почему**. Через месяц эта половина стирается у всех, и агент — раньше остальных: он честно
 * предложит вариант, который уже пробовали и выбросили, и сделает это за ваши токены. Записанное
 * решение — единственное, что отличает «мы это обсуждали» от «мы это помним».
 *
 * Файлами в репозитории, а не базой в чужом формате: решение живёт в той же истории, что и код,
 * попадает в код-ревью и откатывается вместе с веткой. База данных рядом с git — это второй
 * источник правды, который расходится с первым.
 *
 * Чистая: поля внутрь, текст файла наружу, и обратно.
 */
object DecisionRecord {
  /** Где решения лежат по умолчанию — рядом с базой знаний, той же дисциплиной. */
  const val FOLDER = "docs/decisions"
  const val INDEX = "README.md"

  data class Decision(
    /** Порядковый номер: он в имени файла и им же решение называют в разговоре («решение 7»). */
    val number: Int,
    /** Вопрос, на который отвечали. Заголовок файла — именно он, а не выбранный вариант. */
    val question: String,
    val chosen: String,
    /** Что рассмотрели и не взяли. Пусто — законно: не у каждого решения была альтернатива. */
    val rejected: String,
    /** Почему. Без этого запись бесполезна: «решили так» не спасает от повторного обсуждения. */
    val why: String,
    /** Дата в ISO — приходит снаружи, иначе тест зависит от часов. */
    val date: String,
    /** Ссылки на код, записи знаний, задачи. */
    val links: List<String> = emptyList(),
    /**
     * Номер решения, которое это заменяет.
     *
     * Журнал не переписывают: решение отменяют новым решением со ссылкой на старое — так устроен
     * любой честный журнал, и так мы записали правило в спеку. Без поля это правило оставалось
     * пожеланием: заменённое решение выглядело действующим, и агент честно предлагал отменённое.
     */
    val supersedes: Int? = null,
  )

  /** Почему решение нельзя записать — кодом; фразу собирает интерфейс. */
  enum class Refusal { NO_QUESTION, NO_CHOICE, NO_REASON }

  fun validate(decision: Decision): Refusal? = when {
    decision.question.isBlank() -> Refusal.NO_QUESTION
    decision.chosen.isBlank() -> Refusal.NO_CHOICE
    // Причина обязательна намеренно: запись без «почему» не отвечает на единственный вопрос,
    // ради которого её потом открывают.
    decision.why.isBlank() -> Refusal.NO_REASON
    else -> null
  }

  /**
   * Имя файла: `0007-nomer-i-tema.md`.
   *
   * Номер спереди — чтобы папка сортировалась по времени принятия, а не по алфавиту темы: читают
   * такие папки с конца.
   */
  fun fileName(decision: Decision): String =
    "%04d-%s.md".format(decision.number, slug(decision.question))

  /**
   * Slug из заголовка — общими правилами (`util.Slug`).
   *
   * Транслитерация жила здесь, а входящее звало её отсюда: пакет документов зависел от пакета
   * решений ради одной функции. Ревизия 07.09.2026 вынесла её в общее место.
   */
  fun slug(text: String, maxLength: Int = com.vibe.agent.util.Slug.MAX_LENGTH): String =
    com.vibe.agent.util.Slug.of(text, maxLength, fallback = "decision")

  /** Пометка в заголовке индекса заменённого решения: его видно, но оно больше не действует. */
  const val SUPERSEDED_MARK = "[заменено]"

  /** Текст файла решения. */
  fun render(decision: Decision): String = buildString {
    appendLine("# ${decision.number}. ${decision.question}")
    appendLine()
    appendLine("**Дата:** ${decision.date}")
    decision.supersedes?.let {
      appendLine()
      appendLine("**Заменяет решение:** $it")
    }
    appendLine()
    appendLine("## Решение")
    appendLine()
    appendLine(decision.chosen.trim())
    if (decision.rejected.isNotBlank()) {
      appendLine()
      appendLine("## Отвергнуто")
      appendLine()
      appendLine(decision.rejected.trim())
    }
    appendLine()
    appendLine("## Почему")
    appendLine()
    appendLine(decision.why.trim())
    if (decision.links.isNotEmpty()) {
      appendLine()
      appendLine("## Ссылки")
      appendLine()
      decision.links.filter { it.isNotBlank() }.forEach { appendLine("- $it") }
    }
  }

  /**
   * Строка индекса — по тому же правилу, что и база знаний: **записи без строки в индексе не
   * существует**, её никто не найдёт. Формат строки такой, что её читает и наш библиотекарь, и
   * гейт связности документации.
   */
  fun indexLine(decision: Decision): String =
    "- [${decision.number}. ${decision.question}](${fileName(decision)}) — ${firstSentence(decision.why)}"

  /** Индекс после добавления решения: заголовок создаётся, если файла ещё нет. */
  fun appendToIndex(existing: String?, decision: Decision, header: String): String {
    val body = existing?.trimEnd().orEmpty().ifEmpty { "# $header" }
    return body + "\n" + indexLine(decision) + "\n"
  }

  /**
   * Индекс, где строка заменённого решения помечена.
   *
   * Пометка ставится в индексе, а не в файле старого решения: файл — свидетельство того, что и
   * почему решили тогда, и переписывать его задним числом значит терять историю. Индекс же читают
   * и человек, и библиотекарь, и им нужен ответ «действует ли это сейчас».
   */
  fun markSuperseded(indexText: String, supersededNumber: Int, byNumber: Int): String =
    indexText.lineSequence().joinToString("\n") { line ->
      val prefix = "- [$supersededNumber."
      if (line.startsWith(prefix) && SUPERSEDED_MARK !in line) "$line $SUPERSEDED_MARK $byNumber" else line
    }

  /** Действует ли решение по строке индекса. */
  fun isActive(indexLine: String): Boolean = SUPERSEDED_MARK !in indexLine

  /** Следующий номер по уже существующим именам файлов. */
  fun nextNumber(fileNames: List<String>): Int =
    (fileNames.mapNotNull { it.substringBefore('-').trim().toIntOrNull() }.maxOrNull() ?: 0) + 1

  private fun firstSentence(text: String): String {
    val clean = text.trim().replace(Regex("\\s+"), " ")
    val end = clean.indexOfFirst { it == '.' || it == ';' }
    return if (end > 0) clean.take(end) else clean.take(160)
  }

}
