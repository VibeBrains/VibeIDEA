// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.hooks

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HookOutcomeTest {
  private val hook = Hook(HookEvent.PRE_TOOL_USE, "cmd", emptyList(), 1000, "H")

  private fun verdict(exit: Int?, timedOut: Boolean = false, spawnFailed: Boolean = false, out: String = "", err: String = "") =
    HookOutcome.verdictOf(hook, exit, timedOut, spawnFailed, out, err)

  @Test
  fun exitZeroEmptyIsOk() {
    assertEquals(HookVerdict.OK, verdict(0).verdict)
  }

  @Test
  fun exitZeroWithStdoutIsNote() {
    val r = verdict(0, out = "looks fine")
    assertEquals(HookVerdict.NOTE, r.verdict)
    assertEquals("looks fine", r.message)
  }

  @Test
  fun exitTwoIsRefuseWithStderrReason() {
    val r = verdict(2, out = "ignored", err = "нельзя трогать migrations")
    assertEquals(HookVerdict.REFUSE, r.verdict)
    assertEquals("нельзя трогать migrations", r.message)
  }

  @Test
  fun exitTwoEmptyGivesDefaultReason() {
    assertTrue(verdict(2).message!!.contains("без объяснения"))
  }

  @Test
  fun exitOneIsBrokenNotRefuse() {
    val r = verdict(1, err = "command not found")
    assertEquals(HookVerdict.BROKEN, r.verdict)
    assertTrue(r.message!!.contains("код 2"))
  }

  @Test
  fun timeoutAndSpawnFailAreBroken() {
    assertEquals(HookVerdict.BROKEN, verdict(null, timedOut = true).verdict)
    assertEquals(HookVerdict.BROKEN, verdict(null, spawnFailed = true).verdict)
  }

  @Test
  fun outputClippedToLimit() {
    val huge = "x".repeat(HookOutcome.OUTPUT_LIMIT + 500)
    val r = verdict(0, out = huge)
    assertTrue(r.message!!.length <= HookOutcome.OUTPUT_LIMIT + 40)
    assertTrue(r.message!!.contains("обрезан"))
  }

  @Test
  fun preToolUseRefuseBlocks() {
    val d = HookOutcome.decideHooks(HookEvent.PRE_TOOL_USE, listOf(verdict(2, err = "нет")))
    assertTrue(d.blocked)
    assertTrue(d.agentMessage!!.contains("остановлено"))
  }

  @Test
  fun postToolUseRefuseDoesNotBlock() {
    val d = HookOutcome.decideHooks(HookEvent.POST_TOOL_USE, listOf(verdict(2, err = "чините")))
    assertFalse(d.blocked)
    assertTrue(d.agentMessage!!.contains("только что сделано"))
  }

  @Test
  fun notesAccumulateWhenNoRefusal() {
    val d = HookOutcome.decideHooks(HookEvent.POST_TOOL_USE, listOf(verdict(0, out = "a"), verdict(0, out = "b")))
    assertFalse(d.blocked)
    assertEquals("a\nb", d.agentMessage)
  }

  @Test
  fun refuseWinsOverNotesAndBrokenTracked() {
    val results = listOf(verdict(0, out = "note"), verdict(1, err = "boom"), verdict(2, err = "стоп"))
    val d = HookOutcome.decideHooks(HookEvent.PRE_TOOL_USE, results)
    assertTrue(d.blocked)
    assertTrue(d.agentMessage!!.contains("стоп"))
    assertEquals(listOf("H"), d.brokenHooks)
  }

  @Test
  fun allOkGivesNoMessage() {
    val d = HookOutcome.decideHooks(HookEvent.TURN_END, listOf(verdict(0)))
    assertFalse(d.blocked)
    assertNull(d.agentMessage)
  }
}
