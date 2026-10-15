// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.i18n.VibeI18n.t

/**
 * What the microphone button does and shows at each moment of a dictation
 *
 * The button used to have two states, and everything between them (opening the device, which can wait
 * for the system to ask for permission, and transcribing, which can take minutes) looked like a frozen IDE
 * Every state looks different without the mouse over it: a spinner while the device opens, red and pulsing while it records,
 * a spinner while the text is made, a check for a moment when it is in the draft
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

    /** The text is in the draft; the button shows it for [DONE_SHOW_MS] and is idle again */
    DONE,

    /** The models of local recognition are being downloaded; no recording starts before they are in place */
    DOWNLOADING,
  }

  /** What a click on the button asks for in a given state */
  enum class Click {
    OPEN,
    CANCEL_OPEN,
    STOP,

    /** A click does nothing: while the text is made, giving up is its own deliberate act and not a second press of the same button */
    NONE,
  }

  /** How long the check mark stays after the text has reached the draft */
  const val DONE_SHOW_MS = 1_500L

  fun onClick(state: State): Click = when (state) {
    State.IDLE, State.DONE -> Click.OPEN
    State.OPENING -> Click.CANCEL_OPEN
    State.RECORDING -> Click.STOP
    State.TRANSCRIBING, State.DOWNLOADING -> Click.NONE
  }

  /** Whether there is something in progress that the Esc key drops: the device opening, the recording, the text, a download */
  fun cancellable(state: State): Boolean =
    state == State.OPENING || state == State.RECORDING || state == State.TRANSCRIBING || state == State.DOWNLOADING

  fun tooltip(state: State): String = when (state) {
    State.IDLE -> t("chat.voice.start")
    State.OPENING -> t("chat.voice.openingTip")
    State.RECORDING -> t("chat.voice.stop")
    State.TRANSCRIBING -> t("chat.voice.transcribingTip")
    State.DONE -> t("chat.voice.doneTip")
    State.DOWNLOADING -> t("chat.voice.downloadingTip")
  }

  /** The microphone is red only while it is really recording */
  fun micOn(state: State): Boolean = state == State.RECORDING

  /** The states in which the button turns, because the person is waiting for something that has no picture of its own */
  fun spinning(state: State): Boolean = state == State.OPENING || state == State.TRANSCRIBING || state == State.DOWNLOADING

  /**
   * The line under the input for a state; null hides it
   *
   * The live text itself goes into the input, not here: this line says what the dictation is doing
   */
  fun status(state: State): String? = when (state) {
    State.OPENING -> line(t("chat.voice.opening"))
    State.RECORDING -> line(t("chat.voice.recording"))
    State.TRANSCRIBING -> line(t("chat.voice.transcribing"))
    State.DONE -> line(t("chat.voice.done"))
    State.DOWNLOADING -> downloading(0)
    State.IDLE -> null
  }

  /** The line while the models download, with how much of them is here */
  fun downloading(percent: Int): String = line(t("chat.voice.downloading", "percent" to percent))

  /** The line while the final text is made by an engine that has something to say about the wait, such as a download */
  fun waiting(hint: String): String = line(t("chat.voice.transcribingWith", "hint" to hint))

  /** [text] in the form the line under the input shows everything in */
  fun line(text: String): String = t("chat.voice.preview", "text" to text)
}
