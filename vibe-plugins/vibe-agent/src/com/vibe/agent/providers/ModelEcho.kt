// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which model actually answered.
 *
 * We ask for a model by id and count the price by that id, but between us and the vendor there can
 * be a proxy, an aggregator and our own failover chain, and a substitution is silent: the answer
 * simply comes from somewhere else. Every wire reports what it served, so it is compared with what
 * was asked and a mismatch is said out loud — once, not on every turn.
 *
 * The comparison is deliberately forgiving, because an honest answer often renames the same model:
 * an alias resolves to a dated build (`gpt-4o` → `gpt-4o-2024-08-06`) and an aggregator prefixes the
 * vendor (`openai/gpt-4o`). Crying «подмена» on those would teach the owner to ignore the line, and
 * an ignored warning is worse than none.
 */
object ModelEcho {
  /** OpenAI-compatible wires carry it at the top level of every chunk and of a whole answer. */
  fun fromOpenAiChunk(chunk: JsonObject): String? = chunk.string("model")

  /** Anthropic says it once, inside `message_start`. */
  fun fromAnthropicEvent(event: JsonObject): String? = (event["message"] as? JsonObject)?.string("model")

  /** Gemini repeats it in every event as `modelVersion`. */
  fun fromGeminiEvent(event: JsonObject): String? = event.string("modelVersion")

  /** True when [answered] is a different model, not another spelling of [requested]. */
  fun substituted(requested: String, answered: String?): Boolean {
    val asked = tail(requested.trim().lowercase())
    val got = tail(answered?.trim()?.lowercase().orEmpty())
    if (asked.isEmpty() || got.isEmpty()) return false
    return !(asked == got || variantOf(asked, got) || variantOf(got, asked))
  }

  /**
   * `openai/gpt-4o` and `gpt-4o` are one model behind an aggregator's namespace.
   *
   * Плавающий алиас приводится к базе: `~openai/gpt-5-latest` — это просьба «дай текущую сборку
   * gpt-5», и вендор честно отвечает датированным снапшотом. Не сняв `~` и хвост `-latest`, мы
   * сравнивали бы `gpt-5-latest` с `gpt-5-2026-08-01` и объявляли подменой КАЖДЫЙ такой ход —
   * предупреждение, которое кричит всегда, перестают читать, и настоящая подмена теряется в нём.
   */
  fun tail(id: String): String =
    id.removePrefix("~").substringAfterLast('/').removeSuffix("-latest").removeSuffix(":latest")

  /**
   * `gpt-4o` against `gpt-4o-2024-08-06`: the same alias, resolved to a dated build.
   *
   * The tail decides. A build marker starts with digits (`2024-08-06`, `002`, `0905-preview`), while
   * a word tail is another model — `gpt-4o-mini` is not `gpt-4o`, and reading that as a rename would
   * hide the very substitution worth knowing about.
   */
  private fun variantOf(base: String, longer: String): Boolean {
    if (longer.length <= base.length || !longer.startsWith(base)) return false
    if (longer[base.length] !in SEPARATORS) return false
    val head = longer.substring(base.length + 1).takeWhile { it !in SEPARATORS }
    return head.isNotEmpty() && head.all { it.isDigit() }
  }

  private const val SEPARATORS = "-_:@."

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeUnless { it.isEmpty() }
}
