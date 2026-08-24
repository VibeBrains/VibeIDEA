// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTranscriptTest {
  private companion object {
    const val T0 = "2026-08-24T10:00:00Z"
    const val T1 = "2026-08-24T10:01:00Z"
    const val T2 = "2026-08-24T10:02:00Z"
  }

  private fun msg(role: Role, text: String, images: List<StoredImage> = emptyList(), at: String = T1, wireText: String? = null) =
    ChatMessageRecord(role = role, text = text, images = images, at = at, wireText = wireText)

  @Test
  fun `codec round-trip keeps images, state targetId and untagged workspace`() {
    val original = ChatThread(
      id = "t-1",
      createdAt = T0,
      lastModified = T1,
      workspaceId = null,
      workspaceLabel = null,
      messages = listOf(
        msg(Role.USER, "привет", images = listOf(StoredImage("shot.png", "image/png", "QUJD")), wireText = "привет [контекст]"),
        msg(Role.ASSISTANT, "ответ"),
        msg(Role.OTHER, "служебное"),
      ),
      state = ThreadState(targetId = "llm:openrouter"),
    )

    val restored = ChatTranscriptCodec.fromJson(ChatTranscriptCodec.toJson(original))!!

    assertEquals(original.id, restored.id)
    assertEquals(original.createdAt, restored.createdAt)
    assertEquals(original.lastModified, restored.lastModified)
    assertNull(restored.workspaceId)
    assertNull(restored.workspaceLabel)
    assertEquals("llm:openrouter", restored.state.targetId)
    assertEquals(3, restored.messages.size)
    assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.OTHER), restored.messages.map { it.role })
    assertEquals(listOf("привет", "ответ", "служебное"), restored.messages.map { it.text })
    assertEquals(listOf(T1, T1, T1), restored.messages.map { it.at })
    assertEquals("привет [контекст]", restored.messages[0].wireText)
    assertNull(restored.messages[1].wireText)
    val image = restored.messages[0].images.single()
    assertEquals("shot.png", image.name)
    assertEquals("image/png", image.mimeType)
    assertEquals("QUJD", image.base64)
    assertTrue(restored.messages[1].images.isEmpty())
  }

  @Test
  fun `codec round-trip keeps workspace stamp`() {
    val original = ChatThread(
      id = "t-2",
      createdAt = T0,
      lastModified = T0,
      workspaceId = "/work/vibe",
      workspaceLabel = "vibe",
      messages = emptyList(),
    )
    val restored = ChatTranscriptCodec.fromJson(ChatTranscriptCodec.toJson(original))!!
    assertEquals("/work/vibe", restored.workspaceId)
    assertEquals("vibe", restored.workspaceLabel)
    assertNull(restored.state.targetId)
  }

  @Test
  fun `broken entries are skipped, valid ones survive`() {
    val array = Json.parseToJsonElement(
      """[{"id":"ok","createdAt":"$T0","lastModified":"$T0","messages":[]}, 42, "junk", {"noId":true}]"""
    ).jsonArray
    val threads = array.mapNotNull { ChatTranscriptCodec.fromJson(it) }
    assertEquals(1, threads.size)
    assertEquals("ok", threads.single().id)
  }

  @Test
  fun `appendCapped trims with a marker and a small cap still keeps half`() {
    val cap = 300
    val existing = (1..cap).map { msg(Role.USER, "m$it", at = T0) }
    val thread = ChatThread("t", T0, T0, null, null, existing)

    val updated = ChatThread.appendCapped(thread, msg(Role.USER, "new", at = T2), cap = cap, now = T2)

    val keep = cap - ChatThread.TRIM_HEADROOM
    assertEquals(keep + 1, updated.messages.size)
    val marker = updated.messages.first()
    assertEquals(Role.OTHER, marker.role)
    val dropped = cap + 1 - keep
    assertTrue(marker.text.contains(dropped.toString()), "marker should count $dropped dropped, was: ${marker.text}")
    assertEquals("new", updated.messages.last().text)
    assertEquals(T2, updated.lastModified)

    // A cap at (or below) the headroom must still keep a useful tail: half of the cap.
    val small = ChatThread.appendCapped(ChatThread("s", T0, T0, null, null, (1..100).map { msg(Role.USER, "s$it", at = T0) }),
      msg(Role.USER, "new", at = T2), cap = 100, now = T2)
    assertEquals(50 + 1, small.messages.size)
  }

  @Test
  fun `second trim reports the cumulative dropped count, not the marker itself`() {
    val cap = 200
    val first = ChatThread.appendCapped(ChatThread("t", T0, T0, null, null, (1..cap).map { msg(Role.USER, "m$it", at = T0) }),
      msg(Role.USER, "new", at = T2), cap = cap, now = T2)
    val firstDropped = cap + 1 - (cap - ChatThread.TRIM_HEADROOM)
    assertTrue(first.messages.first().text.contains(firstDropped.toString()))

    // Fill back up to the cap and overflow again: the new marker folds in the old count.
    var thread = first
    repeat(cap - thread.messages.size) { thread = ChatThread.appendCapped(thread, msg(Role.USER, "x$it", at = T2), cap = cap, now = T2) }
    val overflowed = ChatThread.appendCapped(thread, msg(Role.USER, "y", at = T2), cap = cap, now = T2)
    val cut = cap + 1 - (cap - ChatThread.TRIM_HEADROOM)
    // Old marker is folded (its count carried over), real cut excludes the marker row itself.
    val expected = firstDropped + (cut - 1)
    assertTrue(overflowed.messages.first().text.contains(expected.toString()),
      "expected cumulative $expected, was: ${overflowed.messages.first().text}")
  }

  @Test
  fun `appendCapped below cap just appends and bumps lastModified`() {
    val thread = ChatThread("t", T0, T0, null, null, listOf(msg(Role.USER, "a", at = T0)))
    val updated = ChatThread.appendCapped(thread, msg(Role.ASSISTANT, "b", at = T1), cap = 500, now = T1)
    assertEquals(2, updated.messages.size)
    assertEquals(T1, updated.lastModified)
  }

  @Test
  fun `title is the first line of the first user message`() {
    val thread = ChatThread(
      "t", T0, T0, null, null,
      listOf(
        msg(Role.OTHER, "системная строка"),
        msg(Role.USER, "  первая строка \nвторая строка"),
        msg(Role.USER, "другой вопрос"),
      ),
    )
    assertEquals("первая строка", thread.title)
  }

  @Test
  fun `title is empty when there is no user message`() {
    val thread = ChatThread("t", T0, T0, null, null, listOf(msg(Role.OTHER, "маркер")))
    assertEquals("", thread.title)
  }

  @Test
  fun `dialogueCount excludes OTHER messages`() {
    val thread = ChatThread(
      "t", T0, T0, null, null,
      listOf(
        msg(Role.USER, "q"),
        msg(Role.ASSISTANT, "a"),
        msg(Role.OTHER, "маркер"),
        msg(Role.USER, "q2"),
      ),
    )
    assertEquals(3, thread.dialogueCount)
  }
}
