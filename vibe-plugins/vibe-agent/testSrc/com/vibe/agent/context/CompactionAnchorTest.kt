// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import com.vibe.agent.history.ChatMessageRecord
import com.vibe.agent.history.Role
import com.vibe.agent.i18n.VibeI18n.t
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * After the fold the model must see the task and the decisions as they were written, not a retelling:
 * a summary that drops one line of the task sends the turn in the wrong direction at full price.
 */
class CompactionAnchorTest {
  private fun record(role: Role, text: String, wire: String? = null, interim: Boolean = false) =
    ChatMessageRecord(role, text, at = "2026-11-20T10:00:00Z", wireText = wire, interim = interim)

  @Test
  fun `the task is the first user message as written, not its inlined context`() {
    val transcript = listOf(
      record(Role.USER, "Сделай отчёт", wire = "Сделай отчёт\n\n<context>огромный файл</context>"),
      record(Role.ASSISTANT, "Хорошо"),
      record(Role.USER, "Ещё добавь график"),
    )
    assertEquals("Сделай отчёт", CompactionAnchor.taskOf(transcript))
  }

  @Test
  fun `interim words are not the task`() {
    val transcript = listOf(
      record(Role.ASSISTANT, "думаю", interim = true),
      record(Role.USER, "Почини сборку"),
    )
    assertEquals("Почини сборку", CompactionAnchor.taskOf(transcript))
  }

  @Test
  fun `the anchor carries both sources whole`() {
    val text = CompactionAnchor.text("Сделай отчёт", "# Решения\n- 1. Формат — markdown")
    assertTrue(t("context.anchor.task") in text!!)
    assertTrue("Сделай отчёт" in text)
    assertTrue(t("context.anchor.decisions") in text)
    assertTrue("# Решения" in text)
  }

  @Test
  fun `nothing to anchor to means no message`() {
    assertNull(CompactionAnchor.text(null, null))
    assertNull(CompactionAnchor.text("  ", "\n"))
  }

  @Test
  fun `what the fold kept verbatim is not repeated`() {
    val text = CompactionAnchor.text("Сделай отчёт", "# Решения", alreadyKept = listOf("Сделай отчёт"))
    assertTrue("Сделай отчёт" !in text!!)
    assertTrue("# Решения" in text)
  }
}
