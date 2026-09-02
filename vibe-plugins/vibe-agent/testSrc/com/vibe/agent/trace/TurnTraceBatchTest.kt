// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.trace

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnTraceBatchTest {
  private fun tool(name: String) = TurnTrace.Event(0, TurnTrace.Kind.TOOL, name)
  private fun other() = TurnTrace.Event(0, TurnTrace.Kind.GATE, "gate")

  @Test
  fun `a batch is a run of tool calls with nothing between them`() {
    val events = listOf(tool("read a"), tool("read b"), tool("read c"), other(), tool("write"), other())
    assertEquals(listOf(3, 1), TurnTrace.toolBatches(events))
    assertEquals(2.0, TurnTrace.averageBatch(events)!!, 1e-9)
  }

  @Test
  fun `one call per turn is what the regression looks like`() {
    // The loop detector cannot see this: the calls are different, so nothing repeats. The shape of
    // the trace is the only evidence.
    val events = listOf(tool("read a"), other(), tool("read b"), other(), tool("read c"), other())
    assertEquals(listOf(1, 1, 1), TurnTrace.toolBatches(events))
    assertEquals(1.0, TurnTrace.averageBatch(events)!!, 1e-9)
  }

  @Test
  fun `a trailing batch is counted, not dropped`() {
    val events = listOf(other(), tool("a"), tool("b"))
    assertEquals(listOf(2), TurnTrace.toolBatches(events))
  }

  @Test
  fun `a turn without tools has no average to report`() {
    // Zero would read as «модель перестала звать инструменты», which is a different statement.
    assertNull(TurnTrace.averageBatch(listOf(other(), other())))
    assertNull(TurnTrace.averageBatch(emptyList()))
    assertEquals(emptyList(), TurnTrace.toolBatches(listOf(other())))
  }
}
