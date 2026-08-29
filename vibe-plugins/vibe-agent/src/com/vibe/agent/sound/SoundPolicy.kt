// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.sound

/**
 * Whether a sound should be played at all.
 *
 * Three gates, and each exists because of a way notification sounds become hated:
 *
 * - **per-event**: a person who wants to know about a permission request does not necessarily want
 *   a chime after every finished turn;
 * - **focus**: the sound is for someone who looked away. Beeping at a person already watching the
 *   screen is the fastest way to get sound turned off entirely;
 * - **debounce**: pipeline steps finish back to back, and without a window this becomes a burst of
 *   beeps that says nothing about any single step.
 *
 * Pure: the decision is testable, the playback is not.
 */
object SoundPolicy {
  enum class Event { TURN_FINISHED, TURN_STOPPED, AWAITING_PERMISSION }

  /** Two beeps in the same second carry no more information than one. */
  const val DEBOUNCE_MS = 1_500L

  data class Settings(
    val enabled: Boolean,
    val onTurnFinished: Boolean,
    val onTurnStopped: Boolean,
    val onAwaitingPermission: Boolean,
    /** Stay silent while the IDE window has focus — the default, and the point of the feature. */
    val muteWhenFocused: Boolean,
  )

  fun shouldPlay(
    event: Event,
    settings: Settings,
    windowFocused: Boolean,
    nowMs: Long,
    lastPlayedMs: Long,
  ): Boolean {
    if (!settings.enabled) return false
    val eventEnabled = when (event) {
      Event.TURN_FINISHED -> settings.onTurnFinished
      Event.TURN_STOPPED -> settings.onTurnStopped
      Event.AWAITING_PERMISSION -> settings.onAwaitingPermission
    }
    if (!eventEnabled) return false
    if (settings.muteWhenFocused && windowFocused) return false
    return nowMs - lastPlayedMs >= DEBOUNCE_MS
  }
}
