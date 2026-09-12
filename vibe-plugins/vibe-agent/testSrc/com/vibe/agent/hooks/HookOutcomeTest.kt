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
  fun postToolUseRefuseDoesNotBlockButIsFlagged() {
    val d = HookOutcome.decideHooks(HookEvent.POST_TOOL_USE, listOf(verdict(2, err = "чините")))
    assertFalse(d.blocked)
    assertTrue(d.flagged) // a post refusal is a real problem even though it can't block → audit ok=false
    assertTrue(d.agentMessage!!.contains("только что сделано"))
  }

  @Test
  fun notesAndCleanAreNotFlagged() {
    assertFalse(HookOutcome.decideHooks(HookEvent.POST_TOOL_USE, listOf(verdict(0, out = "note"))).flagged)
    assertFalse(HookOutcome.decideHooks(HookEvent.TURN_END, listOf(verdict(0))).flagged)
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

  // Hooks ported from Claude Code refuse with JSON on exit 0. The same table of cases lives in
  // VibeIDE (hookOutcome.test.ts): one contract, one behaviour.

  @Test
  fun foreignDenyOnExitZeroRefuses() {
    val r = verdict(0, out = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Destructive command"}}""")
    assertEquals(HookVerdict.REFUSE, r.verdict)
    assertEquals("Destructive command", r.message)
    assertTrue(HookOutcome.decideHooks(HookEvent.PRE_TOOL_USE, listOf(r)).blocked)
  }

  @Test
  fun foreignLegacyDecisionRefuses() {
    assertEquals(HookVerdict.REFUSE, verdict(0, out = """{"decision":"block","reason":"нет"}""").verdict)
    assertEquals("нет", verdict(0, out = """{"decision":"deny","reason":"нет"}""").message)
  }

  @Test
  fun foreignRefusalWithoutReasonStillRefuses() {
    val r = verdict(0, out = """{"decision":"block"}""")
    assertEquals(HookVerdict.REFUSE, r.verdict)
    assertTrue(r.message!!.contains("без объяснения"))
  }

  @Test
  fun foreignContinueFalseIsRefusal() {
    val r = verdict(0, out = """{"continue": false, "stopReason": "хватит"}""")
    assertEquals(HookVerdict.REFUSE, r.verdict)
    assertEquals("хватит", r.message)
  }

  @Test
  fun foreignAllowIsNotANote() {
    assertEquals(HookVerdict.OK, verdict(0, out = """{"decision":"approve"}""").verdict)
    assertNull(verdict(0, out = """{"hookSpecificOutput":{"permissionDecision":"allow"}}""").message)
  }

  @Test
  fun foreignAskIsToldToTheUserAndDoesNotBlock() {
    val r = verdict(0, out = """{"hookSpecificOutput":{"permissionDecision":"ask"}}""")
    assertEquals(HookVerdict.BROKEN, r.verdict)
    val d = HookOutcome.decideHooks(HookEvent.PRE_TOOL_USE, listOf(r))
    assertFalse(d.blocked)
    assertEquals(listOf("H"), d.brokenHooks)
  }

  @Test
  fun jsonWithoutADecisionStaysANote() {
    assertEquals(HookVerdict.NOTE, verdict(0, out = """{"lines": 350, "file": "a.kt"}""").verdict)
    assertEquals(HookVerdict.NOTE, verdict(0, out = """{"decision":"whatever"}""").verdict)
  }

  @Test
  fun onlyRealJsonCounts() {
    // Their rule verbatim: stdout is a decision only when it starts with { and ends with }.
    assertEquals(HookVerdict.NOTE, verdict(0, out = """the hook says {"decision":"block"}""").verdict)
    assertEquals(HookVerdict.NOTE, verdict(0, out = """{"decision":"block" """).verdict)
    assertEquals(HookVerdict.NOTE, verdict(0, out = """{"decision": }""").verdict)
  }

  @Test
  fun exitTwoWinsOverAForeignAllow() {
    // Claude Code: a JSON allow cannot override exit 2. Neither here.
    val r = verdict(2, out = """{"decision":"approve"}""", err = "стоп")
    assertEquals(HookVerdict.REFUSE, r.verdict)
    assertEquals("стоп", r.message)
  }
}
