// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.sound

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundPolicyTest {
  private val all = SoundPolicy.Settings(
    enabled = true, onTurnFinished = true, onTurnStopped = true,
    onAwaitingPermission = true, muteWhenFocused = true,
  )
  private val now = 1_800_000_000_000L

  private fun play(
    event: SoundPolicy.Event = SoundPolicy.Event.TURN_FINISHED,
    settings: SoundPolicy.Settings = all,
    focused: Boolean = false,
    last: Long = 0,
  ) = SoundPolicy.shouldPlay(event, settings, focused, now, last)

  @Test
  fun `a finished turn while the window is in the background makes a sound`() {
    assertTrue(play())
  }

  @Test
  fun `an active window stays silent — the sound is for someone who looked away`() {
    // Beeping at a person already watching the screen is how sound gets turned off for good.
    assertFalse(play(focused = true))
    assertTrue(play(focused = true, settings = all.copy(muteWhenFocused = false)), "запрет снимается настройкой")
  }

  @Test
  fun `the global switch beats every per-event one`() {
    assertFalse(play(settings = all.copy(enabled = false)))
  }

  @Test
  fun `each event has its own switch`() {
    assertFalse(play(SoundPolicy.Event.TURN_FINISHED, all.copy(onTurnFinished = false)))
    assertTrue(play(SoundPolicy.Event.AWAITING_PERMISSION, all.copy(onTurnFinished = false)))
    assertFalse(play(SoundPolicy.Event.AWAITING_PERMISSION, all.copy(onAwaitingPermission = false)))
    assertFalse(play(SoundPolicy.Event.TURN_STOPPED, all.copy(onTurnStopped = false)))
  }

  @Test
  fun `events in a row are merged — a pipeline must not machine-gun`() {
    assertFalse(play(last = now - 500), "полсекунды назад уже пикали")
    assertFalse(play(last = now - SoundPolicy.DEBOUNCE_MS + 1))
    assertTrue(play(last = now - SoundPolicy.DEBOUNCE_MS))
  }

  @Test
  fun `the very first sound of a session is not debounced away`() {
    assertTrue(play(last = 0))
  }
}
