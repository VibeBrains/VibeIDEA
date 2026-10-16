// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.vibe.agent.history.ChatExport
import com.vibe.agent.history.ChatMessageRecord
import com.vibe.agent.history.ChatThread
import com.vibe.agent.history.ChatTranscriptCodec
import com.vibe.agent.history.Role
import com.vibe.agent.history.TranscriptWire
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnSegmentsTest {
  private sealed interface Event {
    data class Text(val chunk: String) : Event
    data class Tool(val title: String) : Event
    data class Thought(val chunk: String) : Event
  }

  /** The stream of one real turn: words, calls, words, calls, the report; chunks are token-sized and carry no separators */
  private val turn = listOf(
    Event.Text("Перевожу ссылки "), Event.Text("в пользовательских скиллах"), Event.Text(" на новую папку."),
    Event.Tool("Grep"), Event.Tool("Edit"),
    Event.Text("Обновляю ссылки "), Event.Text("в правилах проекта."),
    Event.Tool("Edit"),
    Event.Text("Бригадир: включён"), Event.Text(". Готово."),
  )

  /** What the panel does with the same events: a boundary stores the words so far, the call follows them */
  private fun run(events: List<Event>): Pair<List<ChatMessageRecord>, TurnSegments> {
    val segments = TurnSegments()
    val records = ArrayList<ChatMessageRecord>()
    var tick = 0
    fun at() = "2026-10-16T10:00:%02dZ".format(tick++)
    for (event in events) when (event) {
      is Event.Text -> segments.append(event.chunk)
      is Event.Thought -> segments.cut()?.let { segments.record(it, at()) }?.let(records::add)
      is Event.Tool -> {
        segments.cut()?.let { segments.record(it, at()) }?.let(records::add)
        records.add(ChatMessageRecord(Role.OTHER, event.title, at = at()))
      }
    }
    val last = segments.current.text.toString()
    if (last.isNotBlank()) records.add(ChatMessageRecord(Role.ASSISTANT, last, at = at()))
    return records to segments
  }

  @Test
  fun `words and calls keep the order they arrived in`() {
    val (records, _) = run(turn)

    assertEquals(
      listOf(
        Role.ASSISTANT to "Перевожу ссылки в пользовательских скиллах на новую папку.",
        Role.OTHER to "Grep",
        Role.OTHER to "Edit",
        Role.ASSISTANT to "Обновляю ссылки в правилах проекта.",
        Role.OTHER to "Edit",
        Role.ASSISTANT to "Бригадир: включён. Готово.",
      ),
      records.map { it.role to it.text },
    )
  }

  @Test
  fun `sentences of different segments are never glued into one text`() {
    val (records, _) = run(turn)

    assertTrue(records.none { it.text.contains("папку.Обновляю") })
    assertTrue(records.none { it.text.contains("проекта.Бригадир") })
  }

  @Test
  fun `only the last words are the answer, the earlier ones are interim`() {
    val (records, segments) = run(turn)

    assertEquals(listOf(true, false, false, true, false, false), records.map { it.interim })
    assertEquals(2, segments.cutCount)
  }

  @Test
  fun `a thought between words cuts them like a call does`() {
    val (records, _) = run(listOf(Event.Text("Смотрю."), Event.Thought("hmm"), Event.Text("Нашёл.")))

    assertEquals(listOf("Смотрю.", "Нашёл."), records.map { it.text })
  }

  @Test
  fun `a boundary with nothing said makes no segment`() {
    val segments = TurnSegments()

    assertNull(segments.cut())
    segments.append("  \n")
    val blank = assertNotNull(segments.cut())
    assertNull(segments.record(blank, "2026-10-16T10:00:00Z"))
    assertEquals(0, segments.cutCount)
  }

  @Test
  fun `a projection keeps reading its own segment after the next one has begun`() {
    val segments = TurnSegments()
    val first = segments.append("первое")
    segments.cut()
    val second = segments.append("второе")

    assertEquals("первое", first.text.toString())
    assertEquals("второе", second.text.toString())
    assertFalse(first === second)
    assertTrue(segments.current === second)
  }

  @Test
  fun `reset starts the next turn's count from zero`() {
    val (_, segments) = run(turn)

    segments.reset()

    assertEquals(0, segments.cutCount)
  }

  @Test
  fun `saved thread comes back in the same order with the same boundaries`() {
    val (records, _) = run(turn)
    val thread = ChatThread("t-1", "2026-10-16T09:59:00Z", "2026-10-16T10:01:00Z", null, null,
                            listOf(ChatMessageRecord(Role.USER, "переведи ссылки", at = "2026-10-16T09:59:30Z")) + records)

    val restored = assertNotNull(ChatTranscriptCodec.fromJson(Json.parseToJsonElement(ChatTranscriptCodec.toJson(thread).toString())))

    assertEquals(thread.messages.map { Triple(it.role, it.text, it.interim) }, restored.messages.map { Triple(it.role, it.text, it.interim) })
  }

  @Test
  fun `a thread saved before segments existed reads as it was`() {
    val old = """{"id":"t","messages":[{"role":"assistant","text":"ответ","at":"x"}]}"""

    val restored = assertNotNull(ChatTranscriptCodec.fromJson(Json.parseToJsonElement(old)))

    assertFalse(restored.messages.single().interim)
  }

  @Test
  fun `export lists segments and calls in arrival order`() {
    val (records, _) = run(turn)
    val thread = ChatThread("t-1", "2026-10-16T09:59:00Z", "2026-10-16T10:01:00Z", null, null, records)

    val text = ChatExport.toMarkdown(thread)

    val positions = listOf("Перевожу ссылки", "Grep", "Обновляю ссылки", "Бригадир: включён").map { text.indexOf(it) }
    assertTrue(positions.all { it >= 0 })
    assertEquals(positions.sorted(), positions)
    assertFalse(text.contains("папку.Обновляю"))
    assertEquals(1, thread.dialogueCount)
  }

  @Test
  fun `interim words do not travel to the model a second time`() {
    val (records, _) = run(turn)

    val wire = TranscriptWire.messages(records, emptySet())

    assertEquals(listOf("Бригадир: включён. Готово."), wire.map { it.text })
  }
}
