// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.help

/**
 * Поиск ПО ТЕКСТУ нашей собственной документации, вшитой в сборку ([HelpBundle]).
 *
 * Зачем: агент не умел пользоваться тем, что в продукте уже есть. На просьбу собрать
 * `.vibe/servers.json` он формат не знал (тот живёт в типах, которые он сам не откроет), а на
 * вопрос про настройки IDE отвечал списком вопросов к человеку — «как сосед, который просит
 * показать, где калитка» (слова брата владельца, 18.09.2026). Документация, до которой агент не
 * дотягивается, документацией не является: это то же правило, что «фича с форматом обязана иметь
 * спеку», только с другой стороны.
 *
 * Намеренно НЕ вызов модели и НЕ поход в сеть: лексический, детерминированный, работает офлайн и
 * описывает ту версию, которая установлена, а не то, как выглядит `main` сегодня.
 *
 * Почему не хватило того, что было: набор ездил в сборке с самого начала, но искал по ИМЕНАМ и
 * заголовкам файлов ([HelpBundle.find]) и открывался только человеку командой `/help`. Инструмента
 * у агента не было вовсе, а поиск по именам не находит «как включить Angular», потому что мануала
 * с таким именем нет — ответ лежит абзацем внутри другого.
 *
 * Чистый слой: нарезка и ранжирование. Чтение файлов набора живёт в [HelpBundle], поэтому правило
 * проверяется без classpath и без IDE.
 */
object HelpSearch {
  /** Раздел: заголовок и проза под ним до следующего заголовка любого уровня. */
  data class Section(
    /** Путь, как он опубликован: `manuals/serversSpec.md` — то, что показывается в ссылке. */
    val file: String,
    /** Текст заголовка без решёток; пусто — преамбула до первого заголовка. */
    val heading: String,
    val body: String,
    /** Строка заголовка, считая с единицы: по ней человек проверяет утверждение агента. */
    val line: Int,
  )

  data class Hit(val section: Section, val score: Int)

  /**
   * Разделы файла.
   *
   * Раздел — авторская единица смысла, и резать иначе значит выдавать модели половину таблицы
   * полей. Строка заголовка запоминается здесь же: посчитать её потом уже не из чего.
   */
  fun splitIntoSections(file: String, text: String): List<Section> {
    val sections = ArrayList<Section>()
    var heading = ""
    var start = 1
    val body = StringBuilder()
    text.lineSequence().forEachIndexed { index, line ->
      if (HEADING.matches(line)) {
        if (heading.isNotEmpty() || body.isNotBlank()) {
          sections += Section(file, heading, body.toString().trim(), start)
        }
        heading = line.trimStart('#', ' ').trim()
        start = index + 1
        body.setLength(0)
      }
      else {
        body.appendLine(line)
      }
    }
    if (heading.isNotEmpty() || body.isNotBlank()) sections += Section(file, heading, body.toString().trim(), start)
    return sections
  }

  /**
   * Разделы по запросу, от самого подходящего.
   *
   * Совпадение в ЗАГОЛОВКЕ весит больше, чем в прозе: заголовок — это то, как автор назвал тему, и
   * на запрос «servers.json» спека этого файла обязана обойти случайное упоминание в чужой главе.
   */
  fun search(sections: List<Section>, query: String, limit: Int = DEFAULT_LIMIT): List<Hit> {
    val terms = terms(query)
    if (terms.isEmpty()) return emptyList()
    return sections.asSequence()
      .map { section -> Hit(section, score(section, terms)) }
      .filter { it.score > 0 }
      .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.section.file }.thenBy { it.section.line })
      .take(limit.coerceIn(1, MAX_LIMIT))
      .toList()
  }

  private fun score(section: Section, terms: List<String>): Int {
    val heading = section.heading.lowercase()
    val body = section.body.lowercase()
    var score = 0
    var matched = 0
    for (term in terms) {
      val inHeading = count(heading, term)
      val inBody = count(body, term)
      if (inHeading + inBody > 0) matched++
      score += inHeading * HEADING_WEIGHT + minOf(inBody, BODY_CAP)
    }
    // Раздел, покрывший ВЕСЬ запрос, обходит тот, где совпало одно слово из трёх: иначе на
    // «формат providers.json» первым идёт глава, где часто встречается слово «формат».
    if (matched == terms.size && terms.size > 1) score += ALL_TERMS_BONUS
    return score
  }

  private fun count(text: String, term: String): Int {
    var from = 0
    var found = 0
    while (true) {
      val at = text.indexOf(term, from)
      if (at < 0) return found
      found++
      from = at + term.length
    }
  }

  /** Слова запроса: короткие и служебные выброшены, регистр не важен. */
  fun terms(query: String): List<String> =
    query.lowercase().split(SPLIT).map { it.trim() }.filter { it.length >= MIN_TERM && it !in STOP }.distinct()

  /**
   * Ответ модели: файл, заголовок, строка и сам раздел.
   *
   * Пустой результат называется словами и числом просмотренных файлов: «здесь про это не написано»
   * и «инструмент сломался» — разные ответы, и ни один из них не значит «выдумай».
   */
  fun format(hits: List<Hit>, query: String, filesSearched: Int, maxChars: Int = SECTION_CHARS): String {
    if (hits.isEmpty()) {
      return "в документации VibeIDEA ничего не нашлось по запросу «" + query + "» (просмотрено файлов: " +
             filesSearched + "). Это ответ, а не отказ: спросите человека, но не выдумывайте."
    }
    return hits.joinToString("\n\n") { hit ->
      val head = hit.section.file + (if (hit.section.heading.isEmpty()) "" else " — " + hit.section.heading) +
                 " (строка " + hit.section.line + ")"
      head + "\n" + clip(hit.section.body, maxChars)
    }
  }

  private fun clip(text: String, limit: Int): String =
    if (text.length <= limit) text else text.take(limit) + "\n… раздел обрезан, целиком — в файле"

  private val HEADING = Regex("^#{1,6}\\s+.*")
  private val SPLIT = Regex("[^\\p{L}\\p{N}_.\\-/]+")

  /** Служебные слова, по которым совпадает всё подряд. */
  private val STOP = setOf("как", "что", "для", "это", "или", "the", "and", "for", "with", "про", "где")

  private const val MIN_TERM = 3
  private const val HEADING_WEIGHT = 8

  /** Потолок на слово в прозе: страница, повторившая термин сорок раз, не обязана быть ответом. */
  private const val BODY_CAP = 5
  private const val ALL_TERMS_BONUS = 10
  private const val DEFAULT_LIMIT = 5
  private const val MAX_LIMIT = 20

  /** Раздел отдаётся целиком, пока он такого размера: обрезанная таблица полей бесполезна. */
  private const val SECTION_CHARS = 4_000
}
