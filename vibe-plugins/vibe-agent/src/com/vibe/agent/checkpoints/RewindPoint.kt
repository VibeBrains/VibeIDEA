// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.checkpoints

/**
 * Which checkpoint belongs to a message: the snapshot taken when that message was sent.
 *
 * A checkpoint is taken at the start of the turn the message opens — after the message is recorded and before the next
 * message is. So it is the first checkpoint at or after the message's time and before the next user message; a label
 * is not a key, two messages may start with the same words. None in that window — the turn took no snapshot (not a git
 * project, a direct model turn), and rewinding can bring back the conversation but not the files.
 *
 * Pure: times in, a checkpoint out.
 */
object RewindPoint {
  fun checkpointFor(checkpoints: List<Checkpoint>, messageAtMillis: Long, nextMessageAtMillis: Long?): Checkpoint? =
    checkpoints
      .filter { it.atMillis >= messageAtMillis && (nextMessageAtMillis == null || it.atMillis < nextMessageAtMillis) }
      .minByOrNull { it.atMillis }
}
