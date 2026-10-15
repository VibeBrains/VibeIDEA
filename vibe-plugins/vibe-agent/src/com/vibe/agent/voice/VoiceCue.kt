// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import com.vibe.agent.sound.SoundSynth

/**
 * The sound that says a recording has begun and that it has ended
 *
 * Two notes each: up when the microphone is really open, down when it has been closed
 * Synthesised on the spot by [SoundSynth], the way the chime of a finished turn is: the sounds of VS Code are MP3,
 * which the JDK cannot play, so a file would need a decoder and a notice, and the notes need neither
 * Not tied to the sound of agent events: whoever dictates wants to hear the start even with those switched off
 */
object VoiceCue {
  enum class Kind { START, STOP }

  /** Two notes a fourth apart, short enough to be over before the first word */
  private const val LOW_HZ = 660.0
  private const val HIGH_HZ = 880.0
  private const val NOTE_MS = 70

  fun pcm(kind: Kind): ByteArray {
    val (first, second) = when (kind) {
      Kind.START -> LOW_HZ to HIGH_HZ
      Kind.STOP -> HIGH_HZ to LOW_HZ
    }
    return SoundSynth.tone(first, NOTE_MS) + SoundSynth.tone(second, NOTE_MS)
  }

  /** How long a cue lasts, in milliseconds */
  const val DURATION_MS = NOTE_MS * 2

  /** Plays the cue and returns when it has been heard; blocks, so never on the UI thread */
  fun play(kind: Kind, volumePercent: Int) = SoundSynth.play(pcm(kind), volumePercent)
}
