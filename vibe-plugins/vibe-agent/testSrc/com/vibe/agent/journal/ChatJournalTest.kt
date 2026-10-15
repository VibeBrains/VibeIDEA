// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.journal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatJournalTest {
  private val limit = 200

  private fun info(text: String, category: JournalCategory = JournalCategory.MISC, page: SettingsPage? = null) =
    JournalInput(category, JournalLevel.INFO, text, page)

  private fun warning(text: String, category: JournalCategory = JournalCategory.MISC, page: SettingsPage? = null) =
    JournalInput(category, JournalLevel.WARNING, text, page)

  /** The journal after the inputs, one tick of the clock apart, so the order of the entries reads from `at` */
  private fun journalOf(vararg inputs: JournalInput, max: Int = limit): List<JournalEntry> =
    inputs.foldIndexed(emptyList()) { i, entries, input -> ChatJournal.append(entries, input, 1000L + i, max) }

  @Test
  fun `a new line is unread, counted once and gets the next id`() {
    val entries = journalOf(info("a"), warning("b", JournalCategory.CHECKS))
    assertEquals(
      listOf(
        JournalEntry(1, 1000, 1, false, JournalCategory.MISC, JournalLevel.INFO, "a", null),
        JournalEntry(2, 1001, 1, false, JournalCategory.CHECKS, JournalLevel.WARNING, "b", null),
      ),
      entries,
    )
  }

  @Test
  fun `the same line in a row is one entry with a counter, a new time and unread again`() {
    val read = ChatJournal.markAllRead(journalOf(info("a")))
    val again = ChatJournal.append(read, info("a"), 5000, limit)
    assertEquals(listOf(JournalEntry(1, 5000, 2, false, JournalCategory.MISC, JournalLevel.INFO, "a", null)), again)
  }

  @Test
  fun `a line given as read does not count as unread`() {
    val entries = journalOf(info("greeting").copy(read = true), info("news"))
    assertEquals(listOf(true, false), entries.map { it.read })
    assertEquals(JournalSummary(total = 2, unread = 1, hasUnreadWarning = false), ChatJournal.summarize(entries))
    assertEquals(0, ChatJournal.summarize(journalOf(info("greeting").copy(read = true))).unread)
  }

  @Test
  fun `a repeat of a read line stays read, and an unread repeat makes it unread again`() {
    val seen = info("greeting").copy(read = true)
    val twice = journalOf(seen, seen)
    assertEquals(listOf(2), twice.map { it.count })
    assertEquals(0, ChatJournal.summarize(twice).unread)
    // The same text as news, not as information: the person has not seen this occurrence
    val asNews = ChatJournal.append(twice, info("greeting"), 9000, limit)
    assertEquals(1, ChatJournal.summarize(asNews).unread)
  }

  @Test
  fun `a read repeat does not mark an unread entry as read`() {
    val entries = journalOf(info("a"), info("a").copy(read = true))
    assertEquals(listOf(false), entries.map { it.read })
  }

  @Test
  fun `a warning given as unread keeps the badge lit`() {
    val summary = ChatJournal.summarize(journalOf(info("catalog").copy(read = true), warning("a key was rejected")))
    assertEquals(JournalSummary(total = 2, unread = 1, hasUnreadWarning = true), summary)
  }

  @Test
  fun `the same text with another level, category or page is a different line`() {
    val entries = journalOf(
      info("a"),
      warning("a"),
      warning("a", JournalCategory.CHECKS),
      warning("a", JournalCategory.CHECKS, SettingsPage.AGENT),
      warning("a", JournalCategory.CHECKS, SettingsPage.AGENT),
    )
    assertEquals(listOf(1L to 1, 2L to 1, 3L to 1, 4L to 2), entries.map { it.id to it.count })
  }

  @Test
  fun `only the newest entry folds, the same line after another one is a new entry`() {
    val entries = journalOf(info("a"), info("b"), info("a"))
    assertEquals(listOf("a" to 1, "b" to 1, "a" to 1), entries.map { it.text to it.count })
  }

  @Test
  fun `over the limit the oldest entries leave and the ids go on`() {
    val entries = journalOf(info("a"), info("b"), info("c"), info("d"), max = 3)
    assertEquals(listOf(2L to "b", 3L to "c", 4L to "d"), entries.map { it.id to it.text })
  }

  @Test
  fun `the text is trimmed and cut to the limit, and an empty line is not an entry`() {
    val long = journalOf(info("  " + "x".repeat(ChatJournal.MAX_TEXT_CHARS + 50) + "  ")).single()
    assertEquals(listOf("a"), journalOf(info("  a\n")).map { it.text })
    assertTrue(journalOf(info(""), info("  \n ")).isEmpty())
    assertEquals(ChatJournal.MAX_TEXT_CHARS, long.text.length)
    assertTrue(long.text.endsWith("…"))
  }

  @Test
  fun `trim cuts to a smaller limit and returns the same list when nothing has to go`() {
    val entries = journalOf(info("a"), info("b"), info("c"))
    assertEquals(listOf("b", "c"), ChatJournal.trim(entries, 2).map { it.text })
    assertSame(entries, ChatJournal.trim(entries, 3))
  }

  @Test
  fun `summary counts entries and not repeats, and a warning counts only while unread`() {
    val entries = journalOf(info("a"), info("a"), warning("w", JournalCategory.CHECKS))
    val read = ChatJournal.markAllRead(entries)
    val infoAgain = ChatJournal.append(read, info("i"), 9000, limit)
    assertEquals(JournalSummary(total = 2, unread = 2, hasUnreadWarning = true), ChatJournal.summarize(entries))
    assertEquals(JournalSummary(total = 2, unread = 0, hasUnreadWarning = false), ChatJournal.summarize(read))
    assertEquals(JournalSummary(total = 3, unread = 1, hasUnreadWarning = false), ChatJournal.summarize(infoAgain))
    assertEquals(JournalSummary(total = 0, unread = 0, hasUnreadWarning = false), ChatJournal.summarize(emptyList()))
  }

  @Test
  fun `a repeat of a read entry makes it unread and does not grow the number of unread entries past one`() {
    val read = ChatJournal.markAllRead(journalOf(warning("w")))
    val repeated = ChatJournal.append(ChatJournal.append(read, warning("w"), 2000, limit), warning("w"), 3000, limit)
    assertEquals(JournalSummary(total = 1, unread = 1, hasUnreadWarning = true), ChatJournal.summarize(repeated))
    assertEquals(3, repeated.single().count)
  }

  @Test
  fun `markAllRead reads everything and returns the same list when it already was`() {
    val entries = ChatJournal.markAllRead(journalOf(info("a"), warning("b")))
    assertEquals(listOf(true, true), entries.map { it.read })
    assertSame(entries, ChatJournal.markAllRead(entries))
  }

  @Test
  fun `filter gives the newest first, by category and by warnings only`() {
    val entries = journalOf(
      info("a", JournalCategory.PROVIDERS), warning("b", JournalCategory.CHECKS),
      warning("c", JournalCategory.PROVIDERS), info("d", JournalCategory.CHECKS),
    )
    fun texts(category: JournalCategory?, onlyWarnings: Boolean) =
      ChatJournal.filter(entries, JournalFilter(category, onlyWarnings)).map { it.text }
    assertEquals(listOf("d", "c", "b", "a"), texts(null, false))
    assertEquals(listOf("c", "a"), texts(JournalCategory.PROVIDERS, false))
    assertEquals(listOf("c", "b"), texts(null, true))
    assertEquals(listOf("c"), texts(JournalCategory.PROVIDERS, true))
    assertEquals(emptyList(), texts(JournalCategory.HOOKS, false))
  }

  @Test
  fun `countByCategory lists only the categories that have entries, in the fixed order`() {
    val entries = journalOf(info("a", JournalCategory.CHECKS), info("b", JournalCategory.PROVIDERS), info("c", JournalCategory.CHECKS))
    assertEquals(
      listOf(JournalCategoryCount(JournalCategory.PROVIDERS, 1), JournalCategoryCount(JournalCategory.CHECKS, 2)),
      ChatJournal.countByCategory(entries),
    )
  }

  @Test
  fun `the seven categories keep their stored ids`() {
    assertEquals(
      listOf("providers", "keys", "hooks", "config", "agents", "checks", "other"),
      JournalCategory.entries.map { it.id },
    )
  }

  @Test
  fun `preview keeps a short text whole and cuts a long one by lines or by characters`() {
    val manyLines = listOf("1", "2", "3", "4", "5").joinToString("\n")
    val wide = "w".repeat(ChatJournal.PREVIEW_CHARS + 20)
    assertEquals(JournalPreview("a\nb", false), ChatJournal.preview("a\nb"))
    assertEquals(JournalPreview("1\n2\n3\n4", false), ChatJournal.preview("1\n2\n3\n4"))
    assertEquals(JournalPreview("1\n2\n3\n4…", true), ChatJournal.preview(manyLines))
    assertEquals(ChatJournal.PREVIEW_CHARS, ChatJournal.preview(wide).text.length)
    assertTrue(ChatJournal.preview(wide).truncated)
  }

  @Test
  fun `an entry comes back from storage as it went, with its page`() {
    val entries = journalOf(
      warning("w \"quoted\" and\nnewline", JournalCategory.KEYS, SettingsPage.PROVIDERS),
      info("i"),
    )
    assertEquals(entries, ChatJournal.parse(ChatJournal.serialize(entries), limit))
  }

  @Test
  fun `damaged text, damaged entries and an unknown page cost only themselves`() {
    val good = ChatJournal.serialize(journalOf(info("good")))
    val withBad = good.dropLast(1) +
      ""","text",""" +
      """{"id":"x","at":1,"count":1,"read":false,"category":"other","level":"info","text":"t"},""" +
      """{"id":2,"at":1,"count":1,"read":false,"category":"nowhere","level":"info","text":"t"},""" +
      """{"id":3,"at":1,"count":0,"read":false,"category":"other","level":"info","text":"t"},""" +
      """{"id":4,"at":1,"count":1,"read":false,"category":"other","level":"info","text":"t","page":"nowhere"}]"""
    assertEquals(listOf(1L to null, 4L to null), ChatJournal.parse(withBad, limit).map { it.id to it.page })
    assertEquals(emptyList(), ChatJournal.parse("{", limit))
    assertEquals(emptyList(), ChatJournal.parse("""{"a":1}""", limit))
    assertEquals(emptyList(), ChatJournal.parse(null, limit))
    assertEquals(emptyList(), ChatJournal.parse("   ", limit))
  }

  @Test
  fun `a stored text is cut to the current limit when it is read back`() {
    val stored = ChatJournal.serialize(journalOf(info("a"), info("b"), info("c")))
    assertEquals(listOf("b", "c"), ChatJournal.parse(stored, 2).map { it.text })
  }

  @Test
  fun `a line already in the journal under its category is not news`() {
    val entries = journalOf(info("hint", JournalCategory.PROVIDERS), warning("other", JournalCategory.CHECKS))
    assertTrue(ChatJournal.hasLine(entries, JournalCategory.PROVIDERS, "hint"))
    assertFalse(ChatJournal.hasLine(entries, JournalCategory.CHECKS, "hint"), "the category is part of what the line says")
    assertFalse(ChatJournal.hasLine(entries, JournalCategory.PROVIDERS, "hint, but another list"))
    assertFalse(ChatJournal.hasLine(emptyList(), JournalCategory.PROVIDERS, "hint"))
  }

  @Test
  fun `a line is compared as the journal keeps it, clipped and trimmed`() {
    val long = "z".repeat(ChatJournal.MAX_TEXT_CHARS + 50)
    val entries = journalOf(info(long, JournalCategory.PROVIDERS))
    assertTrue(ChatJournal.hasLine(entries, JournalCategory.PROVIDERS, long))
    assertTrue(ChatJournal.hasLine(journalOf(info("  hint ", JournalCategory.PROVIDERS)), JournalCategory.PROVIDERS, "hint"))
  }

  @Test
  fun `a stored text longer than the cap is cut when it is read back`() {
    val stored = """[{"id":1,"at":1,"count":1,"read":true,"category":"other","level":"info","text":"${"y".repeat(ChatJournal.MAX_TEXT_CHARS + 10)}"}]"""
    assertEquals(ChatJournal.MAX_TEXT_CHARS, ChatJournal.parse(stored, limit).single().text.length)
  }
}
