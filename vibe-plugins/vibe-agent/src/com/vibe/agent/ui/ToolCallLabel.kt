// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.vibe.agent.mcp.McpProtocol

/**
 * Подпись вызова инструмента в ленте: человеческое имя и то, к чему вызов относится.
 *
 * Лента показывала техническое имя — `vibe_decisions_search`, `vibe_symbol_usages`, — и разговор
 * выглядел машинным логом, в котором не видно, что происходит (владелец сравнил с VibeIDE, где
 * стоит «Нашёл в документации», 18.09.2026). Заголовки уже объявлены в каталоге инструментов для
 * внешнего агента, поэтому берутся оттуда, а не пишутся второй раз.
 *
 * К заголовку добавляется предмет вызова — путь, запрос, имя, — потому что «Прочитать файл» без
 * имени файла отвечает на вопрос наполовину. Берётся первое известное поле: у наших инструментов
 * оно всегда одно из этих.
 *
 * Чужой инструмент (сервер памяти) остаётся под своим именем: придумывать ему русское название
 * значит однажды переименовать то, что человек ищет в его собственной документации.
 *
 * Чистая: строка внутрь, строка наружу.
 */
object ToolCallLabel {
  /** Поля, в которых у инструментов лежит предмет вызова, в порядке осмысленности. */
  private val SUBJECT_KEYS = listOf("path", "query", "name", "command", "from", "task")

  private const val SUBJECT_CHARS = 60

  fun of(tool: String, arguments: String?): String {
    val title = McpProtocol.titleOf(tool) ?: tool
    val subject = subjectOf(arguments) ?: return title
    return "$title — $subject"
  }

  /**
   * Предмет вызова из его аргументов, без разбора JSON в объект.
   *
   * Разбор по тексту: аргументы приезжают строкой, местами оборванной (модель шлёт их кусками), и
   * падать на подписи из-за неполного JSON значило бы терять подпись ровно там, где она нужна.
   */
  fun subjectOf(arguments: String?): String? {
    val text = arguments?.takeIf { it.isNotBlank() } ?: return null
    for (key in SUBJECT_KEYS) {
      val at = text.indexOf("\"$key\"")
      if (at < 0) continue
      val colon = text.indexOf(':', at + key.length + 2)
      if (colon < 0) continue
      val start = text.indexOf('"', colon + 1)
      if (start < 0) continue
      val end = text.indexOf('"', start + 1)
      if (end < 0) continue
      val value = text.substring(start + 1, end).trim()
      if (value.isNotEmpty()) return shorten(value)
    }
    return null
  }

  /** Длинный аргумент обрезается с НАЧАЛА: у пути важен хвост, у запроса — начало. */
  private fun shorten(value: String): String = when {
    value.length <= SUBJECT_CHARS -> value
    value.contains('/') -> "…" + value.takeLast(SUBJECT_CHARS)
    else -> value.take(SUBJECT_CHARS) + "…"
  }
}
