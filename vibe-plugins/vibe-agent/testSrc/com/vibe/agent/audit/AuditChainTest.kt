// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditChainTest {
  /** Builds a journal the way AuditLog does: payload, then the link appended to it. */
  private fun journal(vararg payloads: String): MutableList<String> {
    var previous = AuditChain.GENESIS
    return payloads.mapTo(ArrayList()) { payload ->
      val link = AuditChain.link(previous, payload)
      previous = link
      payload.dropLast(1) + ",\"" + AuditChain.FIELD + "\":\"" + link + "\"}"
    }
  }

  private fun verify(lines: List<String>) =
    AuditChain.verify(lines, AuditChain::linkOf, AuditChain::withoutLink)

  private val a = """{"ts":1,"action":"prompt","ok":true}"""
  private val b = """{"ts":2,"action":"fs_write","ok":true}"""
  private val c = """{"ts":3,"action":"terminal","ok":true}"""

  @Test
  fun `an untouched journal verifies`() {
    val verdict = verify(journal(a, b, c))
    assertTrue(verdict.intact)
    assertEquals(3, verdict.checked)
  }

  @Test
  fun `an edited line is named by its number`() {
    // The point of the whole thing: not «что-то не так», but «строка 2 больше не сходится».
    val lines = journal(a, b, c)
    lines[1] = lines[1].replace("\"ok\":true", "\"ok\":false")
    val verdict = verify(lines)
    assertFalse(verdict.intact)
    assertEquals(2, verdict.brokenAtLine)
  }

  @Test
  fun `a deleted line breaks the link that followed it`() {
    val lines = journal(a, b, c)
    lines.removeAt(1)
    assertEquals(2, verify(lines).brokenAtLine)
  }

  @Test
  fun `a forged line cannot be inserted in the middle`() {
    // The forger would have to recompute every link after it — which is exactly the cost a chain
    // is for, and which leaves the tail disagreeing if they do not.
    val lines = journal(a, b, c)
    lines.add(1, """{"ts":9,"action":"prompt","ok":true,"h":"deadbeefcafe"}""")
    assertEquals(2, verify(lines).brokenAtLine)
  }

  @Test
  fun `reordering two lines is noticed`() {
    val lines = journal(a, b, c)
    val moved = mutableListOf(lines[1], lines[0], lines[2])
    assertNotNull(verify(moved).brokenAtLine)
  }

  @Test
  fun `an appended forgery at the end is noticed too`() {
    val lines = journal(a, b)
    lines.add("""{"ts":4,"action":"terminal","ok":true,"h":"000000000000"}""")
    assertEquals(3, verify(lines).brokenAtLine)
  }

  @Test
  fun `lines written before the chain existed are old, not forged`() {
    // Calling an honest old record a forgery would teach people to ignore the check on its first
    // run — the only way a check like this dies.
    val lines = mutableListOf(a, b)
    val verdict = verify(lines)
    assertNull(verdict.brokenAtLine)
    assertEquals(1, verdict.unlinkedAtLine)
    assertFalse(verdict.intact)
  }

  @Test
  fun `a chain continues over unlinked history`() {
    // Lines added after the upgrade still depend on everything written before them.
    var previous = AuditChain.GENESIS
    previous = AuditChain.link(previous, a)   // old line, no link stored
    val link = AuditChain.link(previous, b)
    val lines = listOf(a, b.dropLast(1) + ",\"" + AuditChain.FIELD + "\":\"" + link + "\"}")
    val verdict = verify(lines)
    assertNull(verdict.brokenAtLine)
    assertEquals(1, verdict.unlinkedAtLine)
  }

  @Test
  fun `blank lines are not records`() {
    val lines = journal(a, b).toMutableList().apply { add(1, "") }
    assertTrue(verify(lines).intact)
  }

  @Test
  fun `the link survives a round trip through the line`() {
    val line = journal(a).single()
    assertEquals(a, AuditChain.withoutLink(line))
    assertEquals(AuditChain.link(AuditChain.GENESIS, a), AuditChain.linkOf(line))
  }
}
