// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.hooks

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HookConfigTest {
  private fun parse(s: String): Pair<List<Hook>, List<String>> {
    val warnings = ArrayList<String>()
    val hooks = HookConfig.parse(s) { warnings.add(it) }
    return hooks to warnings
  }

  @Test
  fun parsesValidHook() {
    val (hooks, warnings) = parse("""{"hooks":[{"event":"preToolUse","command":"node guard.js","tools":["run_command"],"timeoutMs":5000,"label":"Guard"}]}""")
    assertEquals(1, hooks.size)
    assertTrue(warnings.isEmpty())
    val h = hooks[0]
    assertEquals(HookEvent.PRE_TOOL_USE, h.event)
    assertEquals("node guard.js", h.command)
    assertEquals(listOf("run_command"), h.tools)
    assertEquals(5000L, h.timeoutMs)
    assertEquals("Guard", h.name())
  }

  @Test
  fun defaultsTimeoutAndEmptyTools() {
    val (hooks, _) = parse("""{"hooks":[{"event":"turnEnd","command":"npm test"}]}""")
    assertEquals(HookConfig.DEFAULT_TIMEOUT_MS, hooks[0].timeoutMs)
    assertTrue(hooks[0].tools.isEmpty())
    assertEquals("npm test", hooks[0].name()) // label falls back to command
  }

  @Test
  fun timeoutCappedWithWarning() {
    val (hooks, warnings) = parse("""{"hooks":[{"event":"preToolUse","command":"x","timeoutMs":999999}]}""")
    assertEquals(HookConfig.MAX_TIMEOUT_MS, hooks[0].timeoutMs)
    assertTrue(warnings.any { it.contains("урезан") })
  }

  @Test
  fun turnEndDropsToolsWithWarning() {
    val (hooks, warnings) = parse("""{"hooks":[{"event":"turnEnd","command":"x","tools":["edit_file"]}]}""")
    assertTrue(hooks[0].tools.isEmpty())
    assertTrue(warnings.any { it.contains("игнорируется") })
  }

  @Test
  fun badHookDroppedWholeOthersSurvive() {
    val (hooks, warnings) = parse("""{"hooks":[{"event":"nope","command":"x"},{"event":"postToolUse","command":"y"}]}""")
    assertEquals(1, hooks.size)
    assertEquals("y", hooks[0].command)
    assertTrue(warnings.any { it.contains("неизвестным event") })
  }

  @Test
  fun missingCommandDropped() {
    val (hooks, warnings) = parse("""{"hooks":[{"event":"preToolUse","command":"  "}]}""")
    assertTrue(hooks.isEmpty())
    assertTrue(warnings.any { it.contains("без command") })
  }

  @Test
  fun topLevelShapeError() {
    assertTrue(parse("""{"nothooks":[]}""").first.isEmpty())
    assertTrue(parse("""[]""").first.isEmpty())
    assertTrue(parse("""not json""").first.isEmpty())
  }

  @Test
  fun hooksForFiltersByEventAndTool() {
    val hooks = listOf(
      Hook(HookEvent.PRE_TOOL_USE, "a", listOf("run_command"), 1, null),
      Hook(HookEvent.PRE_TOOL_USE, "b", emptyList(), 1, null),
      Hook(HookEvent.POST_TOOL_USE, "c", emptyList(), 1, null),
      Hook(HookEvent.TURN_END, "d", emptyList(), 1, null),
    )
    // exact tool match + empty-tools wildcard, in file order
    assertEquals(listOf("a", "b"), HookConfig.hooksFor(hooks, HookEvent.PRE_TOOL_USE, "run_command").map { it.command })
    // non-matching tool leaves only the wildcard
    assertEquals(listOf("b"), HookConfig.hooksFor(hooks, HookEvent.PRE_TOOL_USE, "edit_file").map { it.command })
    // turnEnd ignores tool entirely
    assertEquals(listOf("d"), HookConfig.hooksFor(hooks, HookEvent.TURN_END, null).map { it.command })
  }
}
