// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptSearchTest {
  private fun msg(role: Role, text: String) = ChatMessageRecord(role, text, at = "2026-01-01T00:00:00Z")

  private fun thread(id: String, vararg messages: ChatMessageRecord) =
    ChatThread(id, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null, null, messages.toList())

  @Test
  fun `empty and blank query return empty map`() {
    val threads = listOf(thread("a", msg(Role.USER, "anything")))
    assertTrue(TranscriptSearch.search("", threads).isEmpty())
    assertTrue(TranscriptSearch.search("   \n\t ", threads).isEmpty())
  }

  @Test
  fun `non-matching thread is excluded`() {
    val threads = listOf(
      thread("hit", msg(Role.USER, "mangler details")),
      thread("miss", msg(Role.USER, "nothing relevant")),
    )
    val result = TranscriptSearch.search("mangler", threads)
    assertEquals(setOf("hit"), result.keys)
    assertEquals("hit", result.getValue("hit").threadId)
  }

  @Test
  fun `matching is case-insensitive both ways`() {
    val threads = listOf(thread("a", msg(Role.USER, "Hello World")))
    assertEquals(12, TranscriptSearch.search("hELLo", threads).getValue("a").score)
  }

  @Test
  fun `title weight goes to the first USER message and an OTHER head weighs 1`() {
    // No user message at all: the first message is still the de-facto title unless it is OTHER.
    val assistantFirst = thread("a", msg(Role.ASSISTANT, "alpha only"))
    val otherFirst = thread("o", msg(Role.OTHER, "alpha only"))
    val markerThenUser = thread("m", msg(Role.OTHER, "alpha"), msg(Role.USER, "alpha"))
    val result = TranscriptSearch.search("alpha", listOf(assistantFirst, otherFirst, markerThenUser))
    assertEquals(2, result.getValue("a").score)
    assertEquals(1, result.getValue("o").score)
    // The surviving first USER message keeps the title weight even behind a trim marker.
    assertEquals(1 + 12, result.getValue("m").score)
  }

  @Test
  fun `non-first messages weigh by role`() {
    val t = thread(
      "a",
      msg(Role.USER, "intro"),
      msg(Role.USER, "term here"),
      msg(Role.ASSISTANT, "term here"),
      msg(Role.OTHER, "term here"),
    )
    // 6 (user) + 2 (assistant) + 1 (other); the first message does not contain the term.
    val match = TranscriptSearch.search("term", listOf(t)).getValue("a")
    assertEquals(9, match.score)
  }

  @Test
  fun `occurrences saturate at three per term per message`() {
    val t = thread("a", msg(Role.USER, "start"), msg(Role.USER, "cat cat cat cat cat"))
    // 5 occurrences saturate at 3, times user weight 6.
    assertEquals(18, TranscriptSearch.search("cat", listOf(t)).getValue("a").score)
  }

  @Test
  fun `bonus applies when at least two terms and all present`() {
    val t = thread("a", msg(Role.USER, "alpha"), msg(Role.USER, "beta"))
    // 12 (alpha in first) + 6 (beta, user) + 10 (all terms present).
    assertEquals(28, TranscriptSearch.search("alpha beta", listOf(t)).getValue("a").score)
  }

  @Test
  fun `no bonus when a term is missing`() {
    val t = thread("a", msg(Role.USER, "alpha"), msg(Role.USER, "beta"))
    assertEquals(12, TranscriptSearch.search("alpha gamma", listOf(t)).getValue("a").score)
  }

  @Test
  fun `no bonus for a single term however often it matches`() {
    val t = thread("a", msg(Role.USER, "alpha"), msg(Role.USER, "alpha"))
    assertEquals(18, TranscriptSearch.search("alpha", listOf(t)).getValue("a").score)
  }

  @Test
  fun `quote is null when only the first message matches`() {
    val t = thread("a", msg(Role.USER, "alpha topic"), msg(Role.ASSISTANT, "unrelated"))
    val match = TranscriptSearch.search("alpha", listOf(t)).getValue("a")
    assertEquals(12, match.score)
    assertNull(match.quote)
  }

  @Test
  fun `quote picks the best non-first message`() {
    val t = thread(
      "a",
      msg(Role.USER, "topic"),
      msg(Role.ASSISTANT, "cat"),
      msg(Role.USER, "cat"),
    )
    val quote = TranscriptSearch.search("cat", listOf(t)).getValue("a").quote
    assertNotNull(quote)
    assertEquals(2, quote.messageIndex)
    assertEquals(Role.USER, quote.role)
    assertEquals("cat", quote.snippet)
  }

  @Test
  fun `quote skips the first message even when it scores highest`() {
    val t = thread("a", msg(Role.USER, "cat cat cat"), msg(Role.ASSISTANT, "one cat"))
    val match = TranscriptSearch.search("cat", listOf(t)).getValue("a")
    assertEquals(38, match.score) // 3 * 12 in the first message + 1 * 2 in the answer.
    assertNotNull(match.quote)
    assertEquals(1, match.quote!!.messageIndex)
  }

  @Test
  fun `short snippet is the whole text with whitespace collapsed`() {
    val t = thread("a", msg(Role.USER, "topic"), msg(Role.USER, "line one\n  line\ttwo cat"))
    val quote = TranscriptSearch.search("cat", listOf(t)).getValue("a").quote
    assertNotNull(quote)
    assertEquals("line one line two cat", quote.snippet)
  }

  @Test
  fun `long snippet is a window centred on the term with ellipses`() {
    val text = "b".repeat(300) + " needle " + "c".repeat(300)
    val t = thread("a", msg(Role.USER, "topic"), msg(Role.USER, text))
    val quote = TranscriptSearch.search("needle", listOf(t)).getValue("a").quote
    assertNotNull(quote)
    val snippet = quote.snippet
    assertTrue(snippet.startsWith("…"))
    assertTrue(snippet.endsWith("…"))
    assertEquals(TranscriptSearch.SNIPPET_WINDOW + 2, snippet.length) // window plus two ellipses
    val at = snippet.indexOf("needle")
    assertTrue(at > 0)
    // The term's midpoint sits near the window's midpoint.
    val termCentre = at + "needle".length / 2
    val windowCentre = snippet.length / 2
    assertTrue(kotlin.math.abs(termCentre - windowCentre) <= 2, "term centre $termCentre vs window centre $windowCentre")
  }

  @Test
  fun `snippet window clamps at text edges`() {
    val atStart = "needle " + "z".repeat(300)
    val startQuote = TranscriptSearch.search("needle", listOf(thread("s", msg(Role.USER, "topic"), msg(Role.USER, atStart))))
      .getValue("s").quote
    assertNotNull(startQuote)
    assertTrue(startQuote.snippet.startsWith("needle"))
    assertFalse(startQuote.snippet.startsWith("…"))
    assertTrue(startQuote.snippet.endsWith("…"))

    val atEnd = "z".repeat(300) + " needle"
    val endQuote = TranscriptSearch.search("needle", listOf(thread("e", msg(Role.USER, "topic"), msg(Role.USER, atEnd))))
      .getValue("e").quote
    assertNotNull(endQuote)
    assertTrue(endQuote.snippet.startsWith("…"))
    assertTrue(endQuote.snippet.endsWith("needle"))
  }
}
