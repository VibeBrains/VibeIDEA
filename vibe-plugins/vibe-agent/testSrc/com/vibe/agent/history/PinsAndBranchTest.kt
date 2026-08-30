// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinsAndBranchTest {
  private fun user(text: String, pinned: Boolean = false) =
    ChatMessageRecord(Role.USER, text, at = "2026-08-30T10:00:00Z", pinned = pinned)

  private fun assistant(text: String) = ChatMessageRecord(Role.ASSISTANT, text, at = "2026-08-30T10:00:01Z")

  private fun service(text: String) = ChatMessageRecord(Role.OTHER, text, at = "2026-08-30T10:00:02Z")

  private fun thread(messages: List<ChatMessageRecord>) =
    ChatThread("t1", "2026-08-30T09:00:00Z", "2026-08-30T09:00:00Z", null, null, messages)

  // --- булавки ---

  @Test
  fun `trimming destroys ordinary messages but keeps pinned ones`() {
    val old = (1..40).map { user("сообщение $it", pinned = it == 3) }
    val result = ChatThread.appendCapped(thread(old), user("новое"), cap = 10, now = "2026-08-30T11:00:00Z")
    assertTrue(result.messages.any { it.text == "сообщение 3" }, "закреплённое обязано пережить обрезку")
    assertFalse(result.messages.any { it.text == "сообщение 4" }, "незакреплённое старое должно быть срезано")
  }

  @Test
  fun `a rescued pin keeps its place in front of the tail`() {
    val old = (1..40).map { user("m$it", pinned = it == 2) }
    val result = ChatThread.appendCapped(thread(old), user("последнее"), cap = 10, now = "n")
    val texts = result.messages.map { it.text }
    assertTrue(texts.indexOf("m2") < texts.indexOf("последнее"))
  }

  @Test
  fun `the trim marker counts only what was actually destroyed`() {
    // Иначе счётчик врал бы: спасённое закреплённое не «удалено».
    val old = (1..40).map { user("m$it", pinned = it <= 5) }
    val result = ChatThread.appendCapped(thread(old), user("новое"), cap = 10, now = "n")
    val marker = result.messages.firstOrNull { it.role == Role.OTHER }?.text.orEmpty()
    val destroyed = Regex("(\\d+)").find(marker)?.groupValues?.get(1)?.toInt() ?: 0
    val survivors = result.messages.count { it.role != Role.OTHER }
    assertEquals(41 - survivors, destroyed)
  }

  @Test
  fun `a pin survives duplication`() {
    val store = HistoryStore { "2026-08-30T12:00:00Z" }
    val created = store.create(null, null)
    store.append(created.id, user("важное", pinned = true), cap = 100)
    val copy = store.duplicate(created.id)!!
    assertTrue(copy.messages.single().pinned)
  }

  @Test
  fun `a pin survives a round trip through json`() {
    val json = ChatTranscriptCodec.toJson(thread(listOf(user("важное", pinned = true), assistant("ответ"))))
    val restored = ChatTranscriptCodec.fromJson(json)!!
    assertTrue(restored.messages[0].pinned)
    assertFalse(restored.messages[1].pinned)
  }

  // --- ветка разговора ---

  @Test
  fun `a branch keeps everything up to and including the chosen message`() {
    val messages = listOf(user("раз"), assistant("ответ раз"), user("два"), assistant("ответ два"))
    assertEquals(listOf("раз", "ответ раз", "два"), ChatThread.branch(messages, 2).map { it.text })
  }

  @Test
  fun `the cut point moves back over service lines`() {
    // Ветка, кончающаяся служебной строкой, даёт модели разговор, который нечем продолжить.
    val messages = listOf(user("раз"), assistant("ответ"), service("ход отменён"))
    assertEquals(listOf("раз", "ответ"), ChatThread.branch(messages, 2).map { it.text })
  }

  @Test
  fun `branching an empty thread yields nothing`() {
    assertTrue(ChatThread.branch(emptyList(), 0).isEmpty())
    assertTrue(ChatThread.branch(listOf(service("только служебное")), 0).isEmpty())
  }

  @Test
  fun `the original thread is untouched by branching`() {
    val store = HistoryStore { "2026-08-30T12:00:00Z" }
    val source = store.create(null, null)
    store.append(source.id, user("раз"), cap = 100)
    store.append(source.id, assistant("ответ"), cap = 100)
    store.append(source.id, user("два"), cap = 100)
    val branch = store.branch(source.id, 1)!!
    assertEquals(3, store.get(source.id)!!.messages.size)
    assertEquals(2, branch.messages.size)
    assertTrue(branch.id != source.id)
  }

  @Test
  fun `branching an unknown thread returns null`() {
    val store = HistoryStore { "n" }
    assertNull(store.branch("нет такого", 0))
    assertNull(store.setPinned("нет такого", 0, true))
  }

  @Test
  fun `pinning an index outside the thread changes nothing`() {
    val store = HistoryStore { "n" }
    val created = store.create(null, null)
    store.append(created.id, user("одно"), cap = 100)
    assertNull(store.setPinned(created.id, 5, true))
    assertFalse(store.get(created.id)!!.messages.single().pinned)
  }
}
