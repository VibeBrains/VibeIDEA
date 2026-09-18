// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ChatMessage
import com.vibe.agent.providers.ToolSpec

/**
 * Чем занят контекст запроса — по частям, а не одним числом.
 *
 * Одно число («34 % окна») говорит, что дело плохо, и не говорит, что с этим делать. Разбивка
 * отвечает на следующий вопрос сразу: место съели схемы инструментов, история разговора или
 * вложенные файлы, — и каждая из трёх причин лечится по-разному (у VibeIDE это popup «Контекст и
 * токены», владелец попросил такой же, 18.09.2026).
 *
 * Оценка, а не измерение, и так и подписана: настоящее число приходит от провайдера ПОСЛЕ запроса,
 * а человек смотрит на счётчик ДО. Считаем тем же [ContextBudget.estimateTokens], которым меряем
 * окно, — второй способ счёта разошёлся бы с первым и спорил бы сам с собой.
 *
 * Чистая: сообщения и схемы внутрь, строки наружу.
 */
object ContextBreakdown {
  data class Part(val title: String, val tokens: Long)

  data class Snapshot(val parts: List<Part>, val total: Long) {
    val isEmpty: Boolean get() = total == 0L
  }

  /** Пусто до первого запроса: «пока нет данных» честнее нарисованного нуля. */
  val NONE = Snapshot(emptyList(), 0L)

  /**
   * Разбивка одного запроса.
   *
   * Системные сообщения считаются отдельно от разговора: это то, что уходит КАЖДЫЙ раз и не
   * зависит от длины беседы, — то есть первое, что стоит резать, когда его стало слишком много.
   */
  fun of(wire: List<ChatMessage>, tools: List<ToolSpec>): Snapshot {
    val system = wire.filter { it.role == "system" }.sumOf { ContextBudget.estimateTokens(it.text) }
    val dialogue = wire.filter { it.role != "system" }.sumOf { ContextBudget.estimateTokens(it.text) }
    val toolSchemas = tools.sumOf { ContextBudget.estimateTokens(it.name + " " + it.description + " " + it.schema) }
    // Картинки считаются штуками, а не знаками: у вендоров свой тариф на изображение, и выдавать
    // наш пересчёт за токены значило бы врать в единицах.
    val images = wire.sumOf { it.images.size }
    val parts = ArrayList<Part>()
    if (system > 0) parts += Part(t("context.part.system"), system)
    if (toolSchemas > 0) parts += Part(t("context.part.tools"), toolSchemas)
    if (dialogue > 0) parts += Part(t("context.part.dialogue"), dialogue)
    if (images > 0) parts += Part(t("context.part.images", "count" to images), 0L)
    return Snapshot(parts, system + dialogue + toolSchemas)
  }
}
