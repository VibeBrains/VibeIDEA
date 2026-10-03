// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

/**
 * A reasoning effort changed mid-conversation without breaking the prompt cache ([ModelQuirks.Quirk.EFFORT_BY_UPDATE])
 *
 * The request-level `reasoning.effort` is part of the prefix the cache matches: moving the slider mid-thread rewrote
 * it, and everything after was billed as fresh input. The vendor's way keeps the effort of the thread's first request
 * and puts a `configuration_update` item before the user message where the effort changed
 * (developers.openai.com/api/docs/guides/reasoning#change-reasoning-mid-conversation)
 *
 * History travels whole with every request here, and the vendor asks to replay each update at its original place:
 * an answer remembers the effort its request was sent at ([ChatMessage.effortMark], `provider/model#effort`), and the
 * plan puts every update back before the user message that answer followed
 *
 * Pure: the messages, the model's key and the effort asked now in, the messages to send and the request's effort out
 */
object EffortUpdates {
  /** A message standing for the `configuration_update` item; its text is the effort ([ResponsesWire.input]) */
  const val ROLE = "configuration_update"

  /** The request's effort, the messages with the updates in place, and the mark the answer is stored with */
  data class Plan(val requestEffort: String, val messages: List<ChatMessage>, val mark: String)

  fun mark(key: String, effort: String): String = "$key$SEPARATOR$effort"

  fun plan(messages: List<ChatMessage>, key: String, current: String): Plan {
    val prefix = key + SEPARATOR
    val lastUser = messages.indexOfLast { it.role == USER }
    // The effort each earlier user message was answered at: the mark of the answer that followed it, for this model
    val answeredAt = HashMap<Int, String>()
    var pendingUser = -1
    messages.forEachIndexed { index, m ->
      when {
        m.role == USER -> pendingUser = index
        m.role == ASSISTANT && pendingUser >= 0 && m.effortMark?.startsWith(prefix) == true -> {
          answeredAt[pendingUser] = m.effortMark.removePrefix(prefix)
          pendingUser = -1
        }
      }
    }
    val base = answeredAt.toSortedMap().values.firstOrNull() ?: current
    var effective = base
    val planned = ArrayList<ChatMessage>(messages.size + 2)
    messages.forEachIndexed { index, m ->
      val effort = if (index == lastUser) current else answeredAt[index]
      if (m.role == USER && effort != null && effort != effective) {
        planned += ChatMessage(ROLE, effort)
        effective = effort
      }
      planned += m
    }
    return Plan(base, planned, mark(key, current))
  }

  private const val SEPARATOR = "#"
  private const val USER = "user"
  private const val ASSISTANT = "assistant"
}
