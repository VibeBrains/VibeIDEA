// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.patrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Дежурная проверка: дешёвая команда решает, звать ли модель, а не наоборот. */
class PatrolTest {
  private val file = """
    {
      "version": 1,
      "patrols": [
        { "id": "todo", "label": "Незакрытые TODO", "probe": "! grep -rq TODO src",
          "everyMinutes": 30, "prompt": "Разбери свежие TODO", "maxPerDay": 3 },
        { "id": "ci", "probe": "gh run list --json conclusion | grep -q failure" },
        { "id": "выключен", "probe": "true", "active": false },
        { "id": "нет-пробы" },
        { "probe": "true" },
        { "id": "todo", "probe": "true" },
        { "id": "мгновенно", "probe": "true", "everyMinutes": 0 }
      ]
    }
  """.trimIndent()

  @Test
  fun `битые записи пропускаются с причиной, остальные работают`() {
    val parsed = Patrol.parse(file)
    assertEquals(listOf("todo", "ci", "выключен"), parsed.entries.map { it.id })
    assertEquals(
      listOf(Patrol.Trouble.NO_PROBE, Patrol.Trouble.NO_ID, Patrol.Trouble.DUPLICATE_ID, Patrol.Trouble.BAD_INTERVAL),
      parsed.problems.map { it.trouble },
    )
  }

  @Test
  fun `умолчания разумны, а не пусты`() {
    val ci = Patrol.parse(file).entries.first { it.id == "ci" }
    assertEquals(Patrol.DEFAULT_MINUTES, ci.everyMinutes)
    assertEquals(Patrol.DEFAULT_MAX_PER_DAY, ci.maxPerDay)
    assertTrue(ci.active, "запись без active активна — как и везде в наших форматах")
    assertEquals("ci", ci.name())
    assertEquals("Незакрытые TODO", Patrol.parse(file).entries.first().name())
  }

  @Test
  fun `интервал вне рамок отклоняется, а не приводится к границе`() {
    // «Каждые ноль минут» человек написал не случайно; подменить это своим значением — сделать
    // не то, о чём просили.
    assertTrue(Patrol.parse(file).entries.none { it.id == "мгновенно" })
  }

  @Test
  fun `выключенная проверка не запускается никогда`() {
    val off = Patrol.parse(file).entries.first { it.id == "выключен" }
    assertFalse(Patrol.due(off, lastRunMs = null, nowMs = 1))
  }

  @Test
  fun `расписание считается от прошлого запуска`() {
    val todo = Patrol.parse(file).entries.first()
    assertTrue(Patrol.due(todo, lastRunMs = null, nowMs = 0), "первый раз — сразу")
    val half = 15 * 60_000L
    assertFalse(Patrol.due(todo, lastRunMs = 0, nowMs = half))
    assertTrue(Patrol.due(todo, lastRunMs = 0, nowMs = 30 * 60_000L))
  }

  @Test
  fun `ноль значит «делать нечего», а сломанная проба поводом не считается`() {
    assertFalse(Patrol.foundWork(0))
    assertTrue(Patrol.foundWork(1))
    // Иначе сломанная команда дёргала бы человека каждые пятнадцать минут.
    assertFalse(Patrol.foundWork(null))
  }

  @Test
  fun `суточный лимит беспокойств соблюдается`() {
    val todo = Patrol.parse(file).entries.first()
    assertEquals(3, todo.maxPerDay)
    assertTrue(Patrol.withinDailyCap(todo, firedToday = 2))
    assertFalse(Patrol.withinDailyCap(todo, firedToday = 3))
  }

  @Test
  fun `нет файла — нет дежурства и нет жалоб`() {
    assertTrue(Patrol.parse(null).entries.isEmpty())
    assertTrue(Patrol.parse(null).problems.isEmpty())
    assertEquals(Patrol.Trouble.NOT_AN_OBJECT, Patrol.parse("не json").problems.single().trouble)
  }

  @Test
  fun `выключенная запись не проверяется вообще`() {
    // Она не запустится — жаловаться на её содержимое некому. Здесь у неё разом нет пробы и
    // запрещённый интервал: сегодня это два повода, и оба должны молчать.
    val parsed = Patrol.parse(
      """{ "patrols": [ { "id": "чужая", "everyMinutes": 0, "active": false },
                        { "id": "своя", "probe": "true" } ] }""")
    assertEquals(listOf("своя"), parsed.entries.map { it.id })
    assertTrue(parsed.problems.isEmpty(), "выключенная запись поводов не даёт: ${parsed.problems}")
  }
}
