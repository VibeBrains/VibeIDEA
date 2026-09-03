// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataSourcesTest {
  @Test
  fun `подключения читаются, вид определяется по адресу`() {
    val parsed = DataSources.parse(
      """
      { "dataSources": [
        { "id": "main", "name": "Прод", "url": "jdbc:postgresql://db:5432/app", "user": "app" },
        { "id": "local", "url": "jdbc:sqlite:./dev.db" }
      ] }
      """.trimIndent()
    )
    assertEquals(listOf("main", "local"), parsed.sources.map { it.id })
    assertEquals(DataSources.Kind.POSTGRES, parsed.sources[0].kind)
    assertEquals(DataSources.Kind.SQLITE, parsed.sources[1].kind)
    // Имя необязательно: без него подключение зовётся своим идентификатором.
    assertEquals("local", parsed.sources[1].name)
    assertTrue(parsed.problems.isEmpty())
  }

  @Test
  fun `битая запись пропускается, остальные подключения работают`() {
    val parsed = DataSources.parse(
      """[ {"id": "a", "url": "jdbc:h2:mem:t"}, {"url": "jdbc:h2:mem:x"}, {"id": "a", "url": "jdbc:h2:mem:y"} ]"""
    )
    assertEquals(listOf("a"), parsed.sources.map { it.id })
    assertEquals(listOf(DataSources.Trouble.NO_ID, DataSources.Trouble.DUPLICATE_ID), parsed.problems.map { it.trouble })
  }

  @Test
  fun `пароль в файле — это находка, а не поле`() {
    // Файл живёт в git; пароль в нём уже утёк, и молчать об этом нельзя.
    val parsed = DataSources.parse("""[ {"id": "a", "url": "jdbc:h2:mem:t", "password": "секрет"} ]""")
    assertEquals(listOf(DataSources.Trouble.PASSWORD_IN_FILE), parsed.problems.map { it.trouble })
    assertEquals(1, parsed.sources.size, "подключение при этом остаётся рабочим")
  }

  @Test
  fun `пароль в адресе не показывается и не попадает в логи`() {
    assertEquals("jdbc:postgresql://user:***@db/app",
                 DataSources.maskUrl("jdbc:postgresql://user:s3cret@db/app"))
    assertEquals("jdbc:mysql://db/app?user=a&password=***&ssl=true",
                 DataSources.maskUrl("jdbc:mysql://db/app?user=a&password=hunter2&ssl=true"))
  }

  @Test
  fun `пустой и битый файл не роняют список`() {
    assertEquals(emptyList(), DataSources.parse(null).sources)
    assertEquals(listOf(DataSources.Trouble.NOT_AN_OBJECT), DataSources.parse("{ это не json").problems.map { it.trouble })
  }
}
