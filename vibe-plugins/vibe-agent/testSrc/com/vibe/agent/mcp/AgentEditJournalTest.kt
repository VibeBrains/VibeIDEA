// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Журнал правок агента: на нём держатся кнопки «принять» и «отклонить».
 *
 * Проверяется то, что стоит денег при ошибке: откат возвращает БАЙТЫ, и промах здесь стирает
 * чужую работу. Поэтому отдельным тестом — отказ откатывать файл, изменённый после агента.
 */
class AgentEditJournalTest {
  private lateinit var file: File
  private val journal = AgentEditJournal()

  @BeforeTest
  fun before() {
    file = File.createTempFile("vibe-journal-", ".txt")
  }

  @AfterTest
  fun after() {
    file.delete()
  }

  private fun journal() = journal

  @Test
  fun `rejecting restores the file as it was`() {
    file.writeText("было")
    journal().record(file.path, "было", "стало")
    file.writeText("стало")
    assertEquals(AgentEditJournal.Revert.Done, journal().reject(file.path))
    assertEquals("было", file.readText())
    assertTrue(journal().isEmpty())
  }

  @Test
  fun `a file created by the agent is removed on reject`() {
    file.writeText("новый")
    journal().record(file.path, null, "новый")
    assertEquals(AgentEditJournal.Revert.Done, journal().reject(file.path))
    assertFalse(file.exists())
  }

  @Test
  fun `a file changed after the agent is not reverted`() {
    file.writeText("было")
    journal().record(file.path, "было", "стало")
    file.writeText("моя правка поверх")
    assertEquals(AgentEditJournal.Revert.Drifted, journal().reject(file.path))
    assertEquals("моя правка поверх", file.readText())
  }

  @Test
  fun `the first snapshot wins over later touches`() {
    // Второе изменение того же файла — продолжение той же работы: откатывать надо к состоянию
    // ДО неё, а не к промежуточному шагу самого агента.
    journal().record(file.path, "исходное", "первое")
    journal().record(file.path, "первое", "второе")
    assertEquals("исходное", journal().all().single().before)
    assertEquals("второе", journal().all().single().after)
  }

  @Test
  fun `accepting leaves the file alone and clears the list`() {
    file.writeText("стало")
    journal().record(file.path, "было", "стало")
    journal().accept(file.path)
    assertEquals("стало", file.readText())
    assertTrue(journal().isEmpty())
  }
}
