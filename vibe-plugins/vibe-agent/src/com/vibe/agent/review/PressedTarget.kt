// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

/**
 * A click made of a press and a release on the same target
 *
 * The editor sends no `mouseClicked` once a listener has consumed the press, and the press has to be consumed or the
 * text behind the button starts a selection: so the click is assembled here from the two events that do arrive
 */
internal class PressedTarget<T : Any> {
  private var pressed: T? = null

  fun press(target: T?) {
    pressed = target
  }

  /** The target when the release is over the one that was pressed, null otherwise; the press is forgotten either way */
  fun release(target: T?): T? {
    val was = pressed
    pressed = null
    return if (was != null && was == target) was else null
  }
}
