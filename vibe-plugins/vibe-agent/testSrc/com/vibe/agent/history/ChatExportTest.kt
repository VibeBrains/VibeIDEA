// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разговор файлом.
 *
 * Главное здесь — круг: то, что выгружено, обязано вернуться тем же. Markdown проверяется на то,
 * что он содержит сам разговор, а не только заголовки: экспорт, теряющий текст, замечают через
 * неделю, когда файл уже отправлен.
 */
class ChatExportTest {
  private val thread = ChatThread(
    id = "t1",
    createdAt = "2026-09-18T10:00:00Z",
    lastModified = "2026-09-18T10:05:00Z",
    workspaceId = "/tmp/project",
    workspaceLabel = "project",
    messages = listOf(
      ChatMessageRecord(Role.USER, "Почини сборку", at = "2026-09-18T10:00:00Z"),
      ChatMessageRecord(Role.ASSISTANT, "Готово, дело было в зависимости", at = "2026-09-18T10:01:00Z",
                        reasoning = "смотрю лог сборки"),
    ),
  )

  @Test
  fun `markdown carries the conversation itself`() {
    val text = ChatExport.toMarkdown(thread)
    assertTrue(text.contains("Почини сборку"), text)
    assertTrue(text.contains("Готово, дело было в зависимости"), text)
    assertTrue(text.contains("смотрю лог сборки"), text)
  }

  @Test
  fun `json goes out and comes back the same`() {
    val restored = assertNotNull(ChatExport.fromJson(ChatExport.toJson(thread)))
    assertEquals(thread.messages.size, restored.messages.size)
    assertEquals(thread.messages.map { it.text }, restored.messages.map { it.text })
    assertEquals(thread.title, restored.title)
  }

  @Test
  fun `someone else's file is refused, not half-read`() {
    assertNull(ChatExport.fromJson("{\"это\":\"не наш файл\"}"))
    assertNull(ChatExport.fromJson("не json вовсе"))
  }

  @Test
  fun `the file is named after the conversation`() {
    assertTrue(ChatExport.fileName(thread, "md").endsWith(".md"))
    assertTrue(ChatExport.fileName(thread, "md").length > 3)
  }
}
