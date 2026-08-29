// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuditTailTest {
  private val key = "inode-1"

  @Test
  fun `a first look reads from the beginning`() {
    assertIs<AuditTail.Step.Restart>(AuditTail.next(null, 100, key))
  }

  @Test
  fun `a grown file is read on from where we stopped`() {
    val step = AuditTail.next(AuditTail.Position(100, key), 250, key)
    assertEquals(100, assertIs<AuditTail.Step.Append>(step).from)
  }

  @Test
  fun `an unchanged file costs nothing`() {
    assertIs<AuditTail.Step.Idle>(AuditTail.next(AuditTail.Position(100, key), 100, key))
  }

  @Test
  fun `a shortened file means rotation — continuing would render garbage`() {
    assertIs<AuditTail.Step.Restart>(AuditTail.next(AuditTail.Position(100, key), 40, key))
  }

  @Test
  fun `a replaced file of the SAME size is still a rotation`() {
    // Size alone cannot see this, and the offset would then point into a different file.
    assertIs<AuditTail.Step.Restart>(AuditTail.next(AuditTail.Position(100, key), 100, "inode-2"))
  }

  @Test
  fun `a half-written line is carried to the next tick`() {
    // A tail read lands mid-record while the agent writes; parsing that half would produce a broken
    // entry that never repairs itself.
    val (lines, carry) = AuditTail.completeLines("{\"a\":1}\n{\"b\":2}\n{\"c\":")
    assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), lines)
    assertEquals("{\"c\":", carry)
  }

  @Test
  fun `a chunk without a single break yields nothing but keeps everything`() {
    val (lines, carry) = AuditTail.completeLines("{\"partial\"")
    assertTrue(lines.isEmpty())
    assertEquals("{\"partial\"", carry)
  }

  @Test
  fun `blank lines are dropped, content is not`() {
    val (lines, _) = AuditTail.completeLines("a\n\n b \n")
    assertEquals(listOf("a", " b "), lines)
  }

  @Test
  fun `the view is bounded — hours of work must not become a memory leak`() {
    val many = (1..5000).map { "line $it" }
    val kept = AuditTail.trim(many, 100)
    assertEquals(100, kept.size)
    assertEquals("line 5000", kept.last(), "оставляем свежие, а не первые")
  }
}
