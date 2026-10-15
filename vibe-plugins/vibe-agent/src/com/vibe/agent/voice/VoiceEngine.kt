// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import java.io.File

/**
 * One way of turning a recording into text; the dictation asks it and never looks at what is behind
 *
 * Every transcriber sits behind this: sherpa-onnx in the IDE itself, whisper.cpp, whisper (Python), and whatever comes next
 * What differs between them is declared here and not decided by a branch in the dictation:
 * whether the text can appear while the person speaks, what to say while the final text is made, which model is in use
 * Which engine a machine gets is [VoiceEngines.choose]'s decision, and a new engine becomes a new line there
 */
interface VoiceEngine {
  /** A short name for diagnostics, never for the person's decisions */
  val name: String

  /** The model in use, if the engine has one: a file, or the name of a set of models */
  val model: String?

  /** How the text appears while the person speaks; null when the engine answers only after the recording */
  val live: LivePreview?

  /** What to say under the input while the final text is made, when the default is not enough; null for the default */
  val waitHint: String?

  /** Text of the recording so far; called only when [live] is [LivePreview.Snapshots], never twice at once, may throw */
  fun preview(wav: ByteArray): String

  /**
   * Starts hearing a recording frame by frame; only when [live] is [LivePreview.Frames]
   *
   * @param onText the draft of the phrase being spoken and each phrase once it is final, from a thread of the engine
   * @param onProblem a failure while the person still speaks, so they learn it now and not at the stop; said once
   */
  fun listen(onText: (LiveText) -> Unit, onProblem: (VoiceProblem) -> Unit): Listening

  /**
   * The text of the finished recording, a WAV of [VoiceCapture.format]
   *
   * @param cancelled turns true when the person gave up; the engine stops what it started and answers [Outcome.Cancelled]
   * @param note a problem that does not end the dictation (a live server failed and the engine goes on another way)
   */
  fun transcribe(audio: File, cancelled: () -> Boolean, note: (VoiceProblem) -> Unit): Outcome

  /** How the live text is made */
  sealed interface LivePreview {
    /**
     * The whole recording so far is asked for again and again
     *
     * @property intervalMs the pause between two requests
     * @property maxRecordingMs past this length the live text stops, because each request transcribes the whole recording again
     */
    data class Snapshots(val intervalMs: Long, val maxRecordingMs: Long) : LivePreview

    /** The engine hears each frame as the microphone reads it, finds where a phrase ends, and makes it final there */
    data object Frames : LivePreview
  }

  /** What a [Listening] engine says as the person speaks */
  sealed interface LiveText {
    /** The phrase being spoken as the engine has it so far; the next draft or the phrase replaces it */
    data class Draft(val text: String) : LiveText

    /** A finished phrase: the text that stays */
    data class Phrase(val text: String) : LiveText
  }

  /** One recording being heard by an engine of [LivePreview.Frames] */
  interface Listening {
    /** The next [length] bytes of [pcm] in [VoiceCapture.format]; copied before it returns, from the thread that reads the device */
    fun feed(pcm: ByteArray, length: Int)

    /** The recording is over: the last phrase is made final; blocks, so never on the UI thread; the text is every phrase */
    fun finish(): Outcome

    /** The person gave up: nothing more is said */
    fun cancel()
  }

  sealed interface Outcome {
    data class Text(val text: String) : Outcome

    /** The engine could not make text; the problem says why and what to do */
    data class Failed(val problem: VoiceProblem) : Outcome

    /** The person gave up while the engine worked; they have been told already */
    data object Cancelled : Outcome
  }
}
