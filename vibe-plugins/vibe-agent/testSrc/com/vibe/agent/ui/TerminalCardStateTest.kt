// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class TerminalCardStateTest {
  @Test
  fun `running card is expanded`() {
    assertFalse(TerminalCardState().collapsed)
  }

  @Test
  fun `finished card collapses on its own`() {
    val state = TerminalCardState()
    state.onFinished()
    assertTrue(state.collapsed)
  }

  @Test
  fun `restored finished card starts collapsed`() {
    assertTrue(TerminalCardState(finished = true).collapsed)
  }

  @Test
  fun `user collapse during run is kept after finish`() {
    val state = TerminalCardState()
    state.toggle()
    state.onFinished()
    assertTrue(state.collapsed)
  }

  @Test
  fun `user expand after finish is kept`() {
    val state = TerminalCardState()
    state.onFinished()
    state.toggle()
    assertFalse(state.collapsed)
  }

  @Test
  fun `user expand while running is not overridden by finish`() {
    val state = TerminalCardState()
    state.toggle()
    state.toggle()
    state.onFinished()
    assertFalse(state.collapsed)
  }
}
