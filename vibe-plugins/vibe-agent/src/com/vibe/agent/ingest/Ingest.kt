// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ingest

/**
 * Внешний документ, положенный в корпус проекта.
 *
 * Сейчас чужой документ можно только прицепить к сообщению: он живёт одну сессию и исчезает вместе
 * с ней. Через неделю тот же PDF прикрепляют снова, и агент читает его снова — за те же токены и с
 * тем же результатом. Корпус отличается от вложения ровно тем, что документ в нём **остаётся**: его
 * находит библиотекарь, он попадает в код-ревью и в историю git.
 *
 * Чистая: текст и происхождение внутрь, markdown наружу.
 */
object Ingest {
  /** Куда кладём по умолчанию — рядом с базой знаний, в отдельную папку «пришло снаружи». */
  const val FOLDER = "docs/inbox"
  const val INDEX = "README.md"

  data class Source(
    val title: String,
    /** Откуда пришло: путь файла или адрес. Без этого через месяц документ — текст ниоткуда. */
    val origin: String,
    val text: String,
    /** Дата приходит снаружи: иначе тест зависит от часов. */
    val date: String,
    /** Сколько страниц было в исходнике, если это известно (PDF). */
    val pages: Int? = null,
    /** Сколько символов отрезано пределом — молчаливое обрезание хуже названного. */
    val droppedChars: Int = 0,
  )

  enum class Refusal { NO_TEXT, NO_TITLE }

  fun validate(source: Source): Refusal? = when {
    source.title.isBlank() -> Refusal.NO_TITLE
    // Пустой текст — это скан без текстового слоя: положить его значит завести документ, который
    // существует и молчит, а агент будет отвечать по нему уверенно и мимо.
    source.text.isBlank() -> Refusal.NO_TEXT
    else -> null
  }

  fun fileName(source: Source): String =
    com.vibe.agent.decisions.DecisionRecord.slug(source.title) + ".md"

  /**
   * Документ с шапкой: заголовок, откуда и когда.
   *
   * Шапка — не украшение: документ без происхождения через месяц неотличим от нашего собственного
   * текста, и его правят как свой.
   */
  fun render(source: Source): String = buildString {
    appendLine("# ${source.title.trim()}")
    appendLine()
    appendLine("> Пришло снаружи: `${source.origin}`")
    appendLine("> Добавлено: ${source.date}")
    if (source.pages != null) appendLine("> Страниц в исходнике: ${source.pages}")
    if (source.droppedChars > 0) appendLine("> Отрезано символов пределом: ${source.droppedChars}")
    appendLine()
    appendLine(source.text.trim())
  }

  /** Строка индекса: без неё документа не существует — его никто не найдёт. */
  fun indexLine(source: Source): String =
    "- [${source.title.trim()}](${fileName(source)}) — ${source.origin}, ${source.date}"

  fun appendToIndex(existing: String?, source: Source, header: String): String {
    val body = existing?.trimEnd().orEmpty().ifEmpty { "# $header" }
    return body + "\n" + indexLine(source) + "\n"
  }

  /** Заголовок из имени файла, когда его больше взять неоткуда. */
  fun titleFromFileName(name: String): String =
    name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifEmpty { name }
}
