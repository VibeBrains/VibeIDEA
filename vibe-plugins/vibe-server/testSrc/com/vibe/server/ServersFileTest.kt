// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Разбор `.vibe/servers.json`: что попадает в стек и о чём IDE говорит вслух. */
class ServersFileTest {
  private fun load(json: String): Pair<List<ServerEntry>, List<String>> {
    val base = Files.createTempDirectory("vibe-servers-test")
    Files.createDirectories(base.resolve(".vibe"))
    Files.writeString(base.resolve(".vibe").resolve("servers.json"), json)
    val warnings = ArrayList<String>()
    return ServersFile.load(base.toString()) { warnings.add(it) } to warnings
  }

  @Test
  fun `выключенная запись не проверяется и молчит`() {
    // Общий набор сидов пишут несколько продуктов: выключенная запись бывает чужой и неполной.
    // Она не запустится — жаловаться на её содержимое некому. Здесь у первой нет обязательной
    // команды (сегодня это исключение прямо в конструкторе), у второй нет даже идентификатора.
    val (entries, warnings) = load(
      """{ "servers": [ { "id": "чужой", "active": false },
                        { "active": false },
                        { "id": "мой", "command": "npm run dev" } ] }"""
    )
    assertEquals(listOf("мой"), entries.map { it.id })
    assertTrue(warnings.isEmpty(), "выключенные записи поводов не дают: $warnings")
  }

  @Test
  fun `включённая запись без команды по-прежнему называется вслух`() {
    // Обратная сторона правила: молчание тут было бы дефектом — человек ждёт работающий сервис.
    val (entries, warnings) = load("""{ "servers": [ { "id": "без-команды" } ] }""")
    assertTrue(entries.isEmpty())
    assertEquals(1, warnings.size, "о включённой битой записи говорим: $warnings")
  }
}
