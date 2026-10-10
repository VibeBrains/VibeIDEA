// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.mcp.AgentEditJournal.Change
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the in-editor review asks of the journal: move the baseline, follow the text, and tell the subscribers */
class AgentEditJournalReviewTest {
  private val journal = AgentEditJournal()
  private val seen = ArrayList<Change>()

  private fun watch() = journal.subscribe { seen.add(it) }

  @Test
  fun `accepting part of a file moves the baseline and keeps what the agent wrote`() {
    journal.record("/p/a.txt", "old", "agent")
    watch()
    journal.rebase("/p/a.txt", "half", settled = false)
    val entry = journal.all().single()
    assertEquals("half", entry.before)
    assertEquals("agent", entry.after)
    assertEquals(listOf<Change>(Change.Resolved("/p/a.txt")), seen)
  }

  @Test
  fun `accepting the last part removes the entry like accept does`() {
    journal.record("/p/a.txt", "old", "agent")
    watch()
    journal.rebase("/p/a.txt", "agent", settled = true)
    assertTrue(journal.isEmpty())
    assertEquals(listOf<Change>(Change.Removed("/p/a.txt")), seen)
  }

  @Test
  fun `rejecting part of a file moves the recorded text only when asked, so a person's edit still reads as drift`() {
    journal.record("/p/a.txt", "old", "agent")
    journal.follow("/p/a.txt", "after a reject", settled = false)
    assertEquals("after a reject", journal.all().single().after)
    journal.follow("/p/a.txt", null, settled = false)
    assertEquals("after a reject", journal.all().single().after)
    assertEquals("old", journal.all().single().before)
  }

  @Test
  fun `rejecting the last part removes the entry`() {
    journal.record("/p/a.txt", "old", "agent")
    watch()
    journal.follow("/p/a.txt", "old", settled = true)
    assertTrue(journal.isEmpty())
    assertEquals(listOf<Change>(Change.Removed("/p/a.txt")), seen)
  }

  @Test
  fun `a path that is not in the journal is left alone and nobody is told`() {
    watch()
    journal.rebase("/p/none.txt", "x", settled = false)
    journal.follow("/p/none.txt", "x", settled = false)
    journal.accept("/p/none.txt")
    assertTrue(seen.isEmpty())
    assertNull(journal.all().firstOrNull())
  }

  @Test
  fun `subscribers hear the agent write, a whole accept and accept all, until they close`() {
    val subscription = watch()
    journal.record("/p/a.txt", null, "new")
    journal.record("/p/b.txt", "b", "B")
    journal.accept("/p/a.txt")
    journal.acceptAll()
    assertEquals(
      listOf<Change>(Change.Recorded("/p/a.txt", "new"), Change.Recorded("/p/b.txt", "B"), Change.Removed("/p/a.txt"),
                     Change.Removed("/p/b.txt")),
      seen)
    subscription.close()
    journal.record("/p/c.txt", "c", "C")
    assertEquals(4, seen.size)
  }

  @Test
  fun `a subscriber that throws does not fail the write that was recorded`() {
    journal.subscribe { error("a broken subscriber") }
    watch()
    journal.record("/p/a.txt", "old", "agent")
    assertEquals("agent", journal.all().single().after)
    assertEquals(1, seen.size, "the next subscriber is still told")
  }
}
