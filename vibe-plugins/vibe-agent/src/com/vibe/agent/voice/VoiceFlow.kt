// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.i18n.VibeI18n.t

/**
 * What the microphone button does and shows at each moment of a dictation
 *
 * The button used to have two states, and everything between them (opening the device, which can wait
 * for the system to ask for permission, and transcribing, which can take minutes) looked like a frozen IDE
 * Pure: the states and what a click means in each are the part worth testing, and they need no Swing
 */
object VoiceFlow {
  enum class State {
    IDLE,

    /** The device is being opened, off the UI thread */
    OPENING,
    RECORDING,

    /** The recording is closed and turning into text */
    TRANSCRIBING,
  }

  /** What a click on the button asks for in a given state */
  enum class Click {
    OPEN,
    CANCEL_OPEN,
    STOP,
    CANCEL_TRANSCRIBE,
  }

  fun onClick(state: State): Click = when (state) {
    State.IDLE -> Click.OPEN
    State.OPENING -> Click.CANCEL_OPEN
    State.RECORDING -> Click.STOP
    State.TRANSCRIBING -> Click.CANCEL_TRANSCRIBE
  }

  fun tooltip(state: State): String = when (state) {
    State.IDLE -> t("chat.voice.start")
    State.OPENING -> t("chat.voice.openingTip")
    State.RECORDING -> t("chat.voice.stop")
    State.TRANSCRIBING -> t("chat.voice.transcribingTip")
  }

  /** The microphone is red only while it is really recording */
  fun micOn(state: State): Boolean = state == State.RECORDING

  /**
   * The line under the input for a state; null hides it
   *
   * While recording it belongs to the live transcript, which fills it itself
   */
  fun status(state: State): String? = when (state) {
    State.OPENING -> line(t("chat.voice.opening"))
    State.TRANSCRIBING -> line(t("chat.voice.transcribing"))
    State.IDLE, State.RECORDING -> null
  }

  /** [text] in the form the line under the input shows everything in */
  fun line(text: String): String = t("chat.voice.preview", "text" to text)
}
