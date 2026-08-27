// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibeAgentStatusServiceTest {
  @Test
  fun idleLabelIsEmpty() {
    val s = VibeAgentStatusService()
    assertEquals(VibeAgentStatusService.State.IDLE, s.state)
    assertEquals("", s.label())
  }

  @Test
  fun labelsPerState() {
    val s = VibeAgentStatusService()
    s.set(VibeAgentStatusService.State.RUNNING); assertTrue(s.label().contains("агент"))
    s.set(VibeAgentStatusService.State.GATE); assertTrue(s.label().contains("проверка"))
    s.set(VibeAgentStatusService.State.BLOCKED); assertTrue(s.label().contains("предохранитель"))
  }

  @Test
  fun listenerFiresOnlyOnChange() {
    val s = VibeAgentStatusService()
    var fired = 0
    s.bind { fired++ }
    s.set(VibeAgentStatusService.State.RUNNING)
    s.set(VibeAgentStatusService.State.RUNNING) // no change → no fire
    s.set(VibeAgentStatusService.State.IDLE)
    assertEquals(2, fired)
  }

  @Test
  fun unbindStopsNotifications() {
    val s = VibeAgentStatusService()
    var fired = 0
    s.bind { fired++ }
    s.bind(null)
    s.set(VibeAgentStatusService.State.RUNNING)
    assertEquals(0, fired)
  }
}
