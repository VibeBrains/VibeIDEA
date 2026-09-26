// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The models that answered instead of the ones asked, summed up from the audit log
 *
 * The chat names a substitution once, in the turn it happened; a model that keeps being replaced shows up only here
 * Pure: the log's raw lines in, one entry per «asked → answered» pair out, most frequent first
 */
object ModelSubstitutions {
  data class Entry(val asked: String, val answered: String, val count: Int, val lastTs: Long)

  private val json = Json { ignoreUnknownKeys = true }

  fun of(lines: List<String>): List<Entry> {
    val pairs = LinkedHashMap<Pair<String, String>, Entry>()
    for (line in lines) {
      val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
      if (o["action"]?.jsonPrimitive?.contentOrNull != AuditEvent.Action.MODEL_SUBSTITUTED) continue
      val meta = o["meta"] as? JsonObject ?: continue
      val asked = meta["asked"]?.jsonPrimitive?.contentOrNull ?: continue
      val answered = meta["answered"]?.jsonPrimitive?.contentOrNull.orEmpty()
      val ts = o["ts"]?.jsonPrimitive?.longOrNull ?: 0
      val key = asked to answered
      val seen = pairs[key]
      pairs[key] = Entry(asked, answered, (seen?.count ?: 0) + 1, maxOf(seen?.lastTs ?: 0, ts))
    }
    return pairs.values.sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastTs })
  }
}
