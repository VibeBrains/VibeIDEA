// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.journal

import com.intellij.openapi.util.Disposer
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatJournalServiceTest {
  private fun service(limit: Int = 200) = ChatJournalService().also { it.limitSource = { limit } }

  private fun info(text: String) = JournalInput(JournalCategory.MISC, JournalLevel.INFO, text)
  private fun warning(text: String) = JournalInput(JournalCategory.CHECKS, JournalLevel.WARNING, text, SettingsPage.AGENT)

  @Test
  fun `a line is appended, counted and folded with its repeat`() {
    val journal = service()
    journal.append(info("a"))
    journal.append(info("a"))
    journal.append(warning("w"))
    assertEquals(listOf("a" to 2, "w" to 1), journal.entries.map { it.text to it.count })
    assertEquals(JournalSummary(total = 2, unread = 2, hasUnreadWarning = true), journal.summary())
  }

  @Test
  fun `listeners hear every change and nothing when nothing changed`() {
    val journal = service()
    val parent = Disposer.newDisposable()
    var heard = 0
    journal.addListener(parent) { heard++ }
    journal.append(info("a"))
    journal.markAllRead()
    journal.markAllRead()
    journal.clear()
    journal.clear()
    assertEquals(3, heard)
    Disposer.dispose(parent)
    journal.append(info("b"))
    assertEquals(3, heard)
  }

  @Test
  fun `the limit is read on every line and lowering it cuts the buffer`() {
    var limit = 5
    val journal = ChatJournalService().also { it.limitSource = { limit } }
    (1..5).forEach { journal.append(info("line $it")) }
    limit = 2
    journal.applyLimit()
    assertEquals(listOf("line 4", "line 5"), journal.entries.map { it.text })
    journal.append(info("line 6"))
    assertEquals(listOf("line 5", "line 6"), journal.entries.map { it.text })
  }

  @Test
  fun `the state comes back as it went, unread flags included`() {
    val journal = service()
    journal.append(warning("w"))
    journal.markAllRead()
    journal.append(info("i"))
    val restored = service().also { it.loadState(journal.state) }
    assertEquals(journal.entries, restored.entries)
    assertEquals(JournalSummary(total = 2, unread = 1, hasUnreadWarning = false), restored.summary())
  }

  @Test
  fun `the state goes through the XML of the workspace file and back`() {
    val journal = service()
    journal.append(warning("a <b> & \"c\"\nnext line"))
    journal.append(info("i"))
    val xml = XmlSerializer.serialize(journal.state)
    val loaded = XmlSerializer.deserialize(xml, ChatJournalService.State::class.java)
    val restored = service().also { it.loadState(loaded) }
    assertEquals(journal.entries, restored.entries)
  }

  @Test
  fun `an empty journal leaves nothing in the state, before the first line and after clear`() {
    val journal = service()
    assertEquals("", journal.state.entries)
    journal.append(info("a"))
    assertTrue(journal.state.entries.isNotEmpty())
    journal.clear()
    assertEquals("", journal.state.entries)
  }

  @Test
  fun `a damaged state opens an empty journal and the next line works`() {
    val journal = service()
    journal.loadState(ChatJournalService.State().apply { entries = "{not json" })
    assertTrue(journal.entries.isEmpty())
    journal.append(info("after"))
    assertEquals(listOf("after"), journal.entries.map { it.text })
  }

  @Test
  fun `a stored journal longer than the limit is cut when it is loaded`() {
    val big = service(limit = 50)
    (1..30).forEach { big.append(info("line $it")) }
    val small = service(limit = 10).also { it.loadState(big.state) }
    assertEquals((21..30).map { "line $it" }, small.entries.map { it.text })
  }

  @Test
  fun `clear empties the journal and entries are a new list after each change`() {
    val journal = service()
    journal.append(info("a"))
    val before = journal.entries
    journal.append(info("b"))
    assertTrue(before !== journal.entries)
    assertSame(journal.entries, journal.entries)
    journal.clear()
    assertTrue(journal.entries.isEmpty())
  }
}
