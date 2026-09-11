// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals

/** Only the exit code speaks: the output of `claude auth status` names the account. */
class ClaudeLoginTest {
  @Test
  fun `the exit code decides and anything unexpected is «not checked»`() {
    assertEquals(ClaudeLogin.State.LOGGED_IN, ClaudeLogin.check(onPath = { true }) { 0 })
    assertEquals(ClaudeLogin.State.LOGGED_OUT, ClaudeLogin.check(onPath = { true }) { 1 })
    assertEquals(ClaudeLogin.State.UNKNOWN, ClaudeLogin.check(onPath = { true }) { null }, "тайм-аут — не ответ")
    assertEquals(ClaudeLogin.State.UNKNOWN, ClaudeLogin.check(onPath = { true }) { 127 })
  }

  @Test
  fun `no claude on the machine is «not checked», and nothing is run`() {
    assertEquals(ClaudeLogin.State.NOT_INSTALLED, ClaudeLogin.check(onPath = { false }) { error("запускать нечего") })
  }

  @Test
  fun `the probe asks exactly claude auth status`() {
    var asked: List<String>? = null
    ClaudeLogin.check(onPath = { true }) { asked = it; 0 }
    assertEquals(listOf("claude", "auth", "status"), asked)
  }
}
