// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Имена инструментов протокола: уникальны, префиксованы и не спорят с чужими.
 *
 * Урок чужой ошибки, а не своей (07.09.2026, разбор коммитов Hermes Agent): реестр инструментов
 * там молча перезаписывал одноимённый инструмент — MCP-сервер MiniMax мог затенить встроенный
 * `web_search`, — а когда перезапись заменили на «пропустить оба», исчезать стал родной инструмент
 * сервера, столкнувшийся со сгенерированной утилитой того же имени
 * ([0cfc1f8](https://github.com/NousResearch/hermes-agent/commit/0cfc1f88),
 * [d6f18cd](https://github.com/NousResearch/hermes-agent/commit/d6f18cd7)).
 *
 * Мы — сервер, а не клиент, поэтому смешивать чужие тулсеты нам не приходится. Но именно наши имена
 * едут в чужой реестр, где живут инструменты других серверов: имя без префикса или совпавшее с
 * соседним — это не наша ошибка на нашей стороне, а тихо пропавший инструмент на чужой.
 */
class McpToolNamesTest {
  private val names = McpProtocol.TOOLS.map { it.name }

  @Test
  fun `имена уникальны`() {
    val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    assertTrue(duplicates.isEmpty(), "одно имя объявлено дважды: $duplicates")
  }

  @Test
  fun `каждое имя несёт наш префикс`() {
    val foreign = names.filterNot { it.startsWith("vibe_") }
    assertTrue(foreign.isEmpty(), "имя без префикса займёт место в чужом реестре: $foreign")
  }

  @Test
  fun `имена написаны так, как их принимают клиенты`() {
    // Каталог читает чужой агент; пробел, точка или верхний регистр в имени — способ узнать о
    // несовместимости от пользователя, а не от теста.
    val wrong = names.filterNot { Regex("^[a-z][a-z0-9_]{2,63}$").matches(it) }
    assertTrue(wrong.isEmpty(), "имя не проходит по общему правилу [a-z0-9_]: $wrong")
  }

  @Test
  fun `порядок каталога детерминирован и совпадает с объявленным`() {
    // Ревизия протокола просит стабильного перечисления: перестановка ломает клиентский кэш
    // каталога, ничего при этом не меняя.
    assertEquals(names, McpProtocol.TOOLS.map { it.name }, "каталог обязан отдаваться одинаково")
    assertEquals(names.size, names.toSet().size)
  }

  @Test
  fun `у каждого инструмента есть заголовок и описание`() {
    val silent = McpProtocol.TOOLS.filter { it.title.isBlank() || it.description.isBlank() }
    assertTrue(silent.isEmpty(), "инструмент без описания чужая модель не выберет: ${silent.map { it.name }}")
  }
}
