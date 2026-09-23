// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A project's say over the catalogue: `.vibe/slop.json`.
 *
 * The catalogue is general, a project is not. A product literally named Ecosystem cannot have the word flagged in
 * every sentence, a house style may use bold as labels, and a team may want its own banned phrases. Without a place
 * for that, the only way to quiet a wrong finding would be to stop reading the detector.
 *
 * - [disable] — rule ids that do not apply here;
 * - [allow] — words and phrases that are this project's real terms, in the catalogue's notation (a trailing `*` on a
 *   word for any ending);
 * - [rules] — the project's own rules in the catalogue's format; an id the catalogue has replaces its rule;
 * - [passScore] — the score a text needs here.
 */
data class SlopOverrides(
  val disable: Set<String> = emptySet(),
  val allow: List<String> = emptyList(),
  val rules: List<SlopRule> = emptyList(),
  val passScore: Double? = null,
) {
  /** The catalogue as this project sees it: a new compiled catalogue, the shared one untouched. */
  fun applyTo(catalog: CompiledCatalog, onWarning: (String) -> Unit): CompiledCatalog {
    val off = disable.map { it.uppercase() }.toSet()
    val own = rules.mapNotNull { CompiledCatalog.compile(it, onWarning) }
    val ownIds = own.map { it.rule.id.uppercase() }.toSet()
    val kept = catalog.rules.filter { it.rule.id.uppercase() !in off && it.rule.id.uppercase() !in ownIds }
    val scoring = passScore?.let { catalog.scoring.copy(passScore = it) } ?: catalog.scoring
    return CompiledCatalog(scoring, kept + own.filter { it.rule.id.uppercase() !in off },
                           catalog.allow + CompiledCatalog.allowPatterns(allow))
  }

  companion object {
    val NONE = SlopOverrides()

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, onWarning: (String) -> Unit): SlopOverrides {
      val root = runCatching { json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(text)) as? JsonObject }
        .getOrElse { onWarning("slop.json: ${it.message}"); null } ?: return NONE
      return SlopOverrides(
        disable = strings(root["disable"]).toSet(),
        allow = strings(root["allow"]),
        rules = SlopCatalog.rulesOf(root["rules"], "slop.json", onWarning),
        passScore = (root["passScore"] as? JsonPrimitive)?.doubleOrNull,
      )
    }

    private fun strings(e: kotlinx.serialization.json.JsonElement?): List<String> =
      (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
  }
}
