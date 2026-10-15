// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import java.io.File

/**
 * One way of turning a recording into text; the dictation asks it and never looks at what is behind
 *
 * Every transcriber sits behind this: whisper.cpp, whisper (Python), and whatever comes next
 * What differs between them is declared here and not decided by a branch in the dictation:
 * whether the text can appear while the person speaks, what to say while the final text is made, which model is in use
 * Which engine a machine gets is [VoiceEngines.choose]'s decision, and a new engine becomes a new line there
 */
interface VoiceEngine {
  /** A short name for diagnostics, never for the person's decisions */
  val name: String

  /** The model file in use, if the engine has one */
  val model: String?

  /** How the text appears while the person speaks; null when the engine answers only after the recording */
  val live: LivePreview?

  /** What to say under the input while the final text is made, when the default is not enough; null for the default */
  val waitHint: String?

  /** Text of the recording so far; called only when [live] is set, never twice at once, may throw */
  fun preview(wav: ByteArray): String

  /**
   * The text of the finished recording
   *
   * @param cancelled turns true when the person gave up; the engine stops what it started and answers [Outcome.Cancelled]
   * @param note a problem that does not end the dictation (a live server failed and the engine goes on another way)
   */
  fun transcribe(audio: File, cancelled: () -> Boolean, note: (VoiceProblem) -> Unit): Outcome

  /**
   * How the live text is asked for
   *
   * @property intervalMs the pause between two requests
   * @property maxRecordingMs past this length the live text stops, because each request transcribes the whole recording again
   */
  data class LivePreview(val intervalMs: Long, val maxRecordingMs: Long)

  sealed interface Outcome {
    data class Text(val text: String) : Outcome

    /** The engine could not make text; the problem says why and what to do */
    data class Failed(val problem: VoiceProblem) : Outcome

    /** The person gave up while the engine worked; they have been told already */
    data object Cancelled : Outcome
  }
}
