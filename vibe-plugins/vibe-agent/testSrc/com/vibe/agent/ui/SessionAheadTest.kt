// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.vibe.agent.ui.SessionAhead.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionAheadTest {
  @Test fun `model target never starts an agent`() =
    assertEquals(Action.SKIP, SessionAhead.decide(false, false, null, "a", false))

  @Test fun `idle agent target starts`() =
    assertEquals(Action.START, SessionAhead.decide(true, false, null, "a", false))

  @Test fun `a start in flight is not duplicated`() =
    assertEquals(Action.SKIP, SessionAhead.decide(true, false, null, "a", true))

  @Test fun `running turn blocks the start`() =
    assertEquals(Action.SKIP, SessionAhead.decide(true, true, "a", "a", false))

  @Test fun `foreign thread during a turn gets empty pickers`() =
    assertEquals(Action.SHOW_EMPTY, SessionAhead.decide(true, true, "a", "b", false))

  @Test fun `result is dropped when thread or target changed`() {
    assertTrue(SessionAhead.stillRelevant("a", "a", true))
    assertFalse(SessionAhead.stillRelevant("b", "a", true))
    assertFalse(SessionAhead.stillRelevant("a", "a", false))
  }
}
