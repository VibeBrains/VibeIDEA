// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditSummaryTest {
  private fun line(actor: String, role: String? = null, action: String = "fs_write", ok: Boolean = true): String =
    AuditEvent(1L, action, ok,
               actor = when (actor) {
                 "human" -> AuditActor.HUMAN
                 "ide" -> AuditActor.IDE
                 else -> AuditActor.agent(role, "acp:claude")
               }).toJson().toString()

  @Test
  fun `the first question an investigation asks is answered in two numbers`() {
    val report = AuditSummary.of(listOf(
      line("agent", "developer"),
      line("agent", "developer", action = "terminal", ok = false),
      line("human", action = "permission"),
    ))
    assertEquals(3, report.total)
    val agent = report.rows.first()
    assertEquals("agent", agent.actor)
    assertEquals("developer", agent.role)
    assertEquals(2, agent.records)
    assertEquals(1, agent.failures)
  }

  @Test
  fun `roles are not merged into one agent`() {
    // «agent (reviewer)» and «agent (developer)» are different answers to «кто это сделал», and
    // merging them hides the one thing a pipeline is for.
    val report = AuditSummary.of(listOf(line("agent", "reviewer"), line("agent", "developer")))
    assertEquals(2, report.rows.size)
    assertEquals(setOf("reviewer", "developer"), report.rows.mapNotNull { it.role }.toSet())
  }

  @Test
  fun `the busiest actor comes first`() {
    val report = AuditSummary.of(listOf(line("human"), line("agent"), line("agent"), line("agent")))
    assertEquals("agent", report.rows.first().actor)
    assertEquals(3, report.rows.first().records)
  }

  @Test
  fun `records from before attribution existed are counted, not dropped`() {
    // An old journal is not an empty one; ignoring those lines would understate what happened.
    val old = """{"ts":1,"action":"prompt","ok":true}"""
    val report = AuditSummary.of(listOf(old, line("human")))
    assertEquals(2, report.total)
    assertEquals(1, report.unattributed)
    assertEquals(1, report.rows.single().records)
  }

  @Test
  fun `one malformed line does not kill the report`() {
    // A report that dies on a bad line is a report nobody trusts.
    val report = AuditSummary.of(listOf("{not json at all", line("human"), ""))
    assertEquals(2, report.total)
    assertEquals(1, report.unattributed)
    assertEquals(1, report.rows.single().records)
  }

  @Test
  fun `actions are listed with counts, busiest first`() {
    val report = AuditSummary.of(listOf(
      line("agent", action = "fs_write"),
      line("agent", action = "terminal"),
      line("agent", action = "terminal"),
    ))
    assertTrue(report.rows.single().actions.first().startsWith("terminal × 2"), report.rows.single().actions.toString())
  }
}
