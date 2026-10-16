// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.history

import com.vibe.agent.providers.ChatMessage
import com.vibe.agent.providers.ImagePart

/**
 * The thread's records as the direct model's request carries them, before tool rounds are expanded
 *
 * The person's and the model's words travel, the feed's own lines do not
 * A turn that ended on tool calls without a single word is kept as an outcome record with its rounds
 * It used to be dropped here together with everything its tools had read:
 * The next turn began without those results, and the model asked to continue read the same files again
 * Such a record now travels as the model's answer with its rounds, and its words are the model-facing [ChatMessageRecord.wireText]
 */
object TranscriptWire {
  /** Whether [record] goes to the model; compaction counts positions over exactly these records */
  fun carried(record: ChatMessageRecord): Boolean = record.role != Role.OTHER || record.toolRounds.isNotEmpty()

  /** The carried records in order; [imageBearing] holds the transcript indices whose images still travel */
  fun messages(transcript: List<ChatMessageRecord>, imageBearing: Set<Int>): List<ChatMessage> =
    transcript.withIndex().filter { carried(it.value) }.map { (index, r) ->
      ChatMessage(
        role = if (r.role == Role.USER) "user" else "assistant",
        // An outcome record's text is the feed's line about the turn, not something the model said
        text = r.wireText ?: if (r.role == Role.OTHER) "" else r.text,
        images = if (index in imageBearing) r.images.map { ImagePart(it.mimeType, it.base64) } else emptyList(),
        reasoning = r.reasoning,
        toolRounds = r.toolRounds,
        responses = r.responses,
        effortMark = r.effortMark,
      )
    }

  /** The tools earlier rounds of the thread loaded through the search, so a reopened thread offers them again */
  fun loadedTools(transcript: List<ChatMessageRecord>): Set<String> =
    transcript.flatMapTo(LinkedHashSet()) { record -> record.toolRounds.flatMap { it.addedTools } }
}
