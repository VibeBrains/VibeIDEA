// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import kotlin.math.exp
import kotlin.math.min

/**
 * The pulse of the microphone button: how loud the voice is, turned into how big the icon is
 *
 * The numbers are the ones VibeIDE uses, so the same voice moves the same button in both products
 * Pure: the mapping and the smoothing are the part worth testing, and they need no Swing
 */
object VoicePulse {
  /** Speech is a few hundredths of full scale; this gain makes an ordinary voice fill most of the range */
  const val GAIN = 18.0

  /** The icon grows by this much per unit of level, up to [MAX_GROWTH] */
  const val GROWTH_PER_LEVEL = 0.5
  const val MAX_GROWTH = 0.4

  /**
   * The time constant of the smoothing: after this long the shown size has covered about two thirds of the way to the new one
   * Frames arrive every 128 ms, and without smoothing the icon would jump by that step
   */
  const val SMOOTHING_MS = 120.0

  /** How often the button is redrawn while it pulses */
  const val FRAME_MS = 33

  /** [rms] (0 to 1 of full scale, as [VoiceCapture.rmsOf] gives it) as a level from 0 to 1 */
  fun level(rms: Double): Double = (rms * GAIN).coerceIn(0.0, 1.0)

  /** The size of the icon for a [level]: 1 is its own size, [MAX_GROWTH] more at the loudest */
  fun scale(level: Double): Double = 1.0 + min(MAX_GROWTH, level.coerceIn(0.0, 1.0) * GROWTH_PER_LEVEL)

  /**
   * The level the eye sees: it follows the latest one the microphone gave without jumping to it
   * An exponential approach, so the result does not depend on how often it is asked
   */
  class Smoother(private val timeConstantMs: Double = SMOOTHING_MS) {
    var shown = 0.0
      private set

    /** The shown level after [elapsedMs] more of moving toward [target] */
    fun next(target: Double, elapsedMs: Long): Double {
      val weight = 1.0 - exp(-elapsedMs.coerceAtLeast(0L) / timeConstantMs)
      shown += (target.coerceIn(0.0, 1.0) - shown) * weight
      return shown
    }

    fun reset() {
      shown = 0.0
    }
  }
}
