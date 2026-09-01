// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.i18n.VibeI18n.t
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `.vibe/modelQuirks.json` — the quirk catalogue a person can fix themselves.
 *
 * The built-in catalogue names what a model refuses to be asked. It is a moving target: vendors
 * break compatibility on a Tuesday and fix it on a Friday, and until now the only way to follow
 * either move was to wait for an IDE release. Somebody who has the 400 in front of them should not
 * have to wait for us to see it too.
 *
 * The rules of the format follow the ones the localisation and the provider registry already use,
 * for the same reasons:
 *
 * - the built-in catalogue is the BASE, the file layers over it: a distribution with no file, or
 *   with a deleted one, behaves exactly as before;
 * - a file entry whose pattern matches WINS over the built-in rules that also match, and an empty
 *   `"quirks": []` is how a wrong built-in rule is switched off — without it there would be no way
 *   to say "this model is fine, stop rewriting my request";
 * - a malformed entry is skipped with a warning and never kills the catalogue: half a catalogue is
 *   still useful, and a chat that dies because of one typo in a config is not.
 */
object ModelQuirksFile {
  private val json = Json { ignoreUnknownKeys = true }

  /** One file entry: which models it speaks about, and what they refuse. */
  data class Entry(val pattern: Regex, val quirks: Set<ModelQuirks.Quirk>, val note: String?)

  /**
   * Parses the file. Never throws on content: the caller gets what could be read plus warnings.
   *
   * The whole file failing to parse IS reported, because that is the one case where silence would
   * leave someone editing a file that is not being read at all.
   */
  fun parse(text: String, source: String = "modelQuirks.json", onWarning: (String) -> Unit): List<Entry> {
    val root = try {
      json.parseToJsonElement(ProvidersFile.stripJsonc(text)).jsonObject
    }
    catch (e: Exception) {
      onWarning(t("quirks.warn.notParsed", "source" to source, "reason" to e.message))
      return emptyList()
    }
    val models = root["models"]?.jsonArray ?: run {
      onWarning(t("quirks.warn.noArray", "source" to source))
      return emptyList()
    }
    val result = ArrayList<Entry>()
    for (el in models) {
      try {
        val o = el.jsonObject
        val entry = parseEntry(o, source, onWarning) ?: continue
        result.add(entry)
      }
      catch (e: Exception) {
        onWarning(t("quirks.warn.entrySkipped", "source" to source, "reason" to e.message))
      }
    }
    return result
  }

  private fun parseEntry(o: JsonObject, source: String, onWarning: (String) -> Unit): Entry? {
    val pattern = o["match"]?.jsonPrimitive?.contentOrNull
    if (pattern.isNullOrBlank()) {
      onWarning(t("quirks.warn.noMatch", "source" to source))
      return null
    }
    val regex = try {
      Regex(pattern)
    }
    catch (e: Exception) {
      onWarning(t("quirks.warn.badRegex", "source" to source, "pattern" to pattern, "reason" to e.message))
      return null
    }
    // An entry without the field says nothing about quirks, which is not the same as saying
    // "no quirks": the first must not silently disable a built-in rule, the second must.
    val quirksArray = o["quirks"] ?: run {
      onWarning(t("quirks.warn.noQuirks", "source" to source, "pattern" to pattern))
      return null
    }
    val quirks = LinkedHashSet<ModelQuirks.Quirk>()
    for (q in quirksArray.jsonArray) {
      val name = q.jsonPrimitive.contentOrNull?.trim().orEmpty()
      val parsed = ModelQuirks.Quirk.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
      if (parsed == null) {
        // Skipped rather than fatal: a newer IDE may know a quirk this one does not, and one
        // unknown word must not cost the person the rest of their file.
        onWarning(t("quirks.warn.unknownQuirk", "source" to source, "name" to name, "pattern" to pattern))
        continue
      }
      quirks.add(parsed)
    }
    return Entry(regex, quirks, o["note"]?.jsonPrimitive?.contentOrNull)
  }

  /** File entries as catalogue rules, ready for [ModelQuirks.setOverrides]. */
  fun rules(entries: List<Entry>): List<ModelQuirks.Rule> =
    entries.map { ModelQuirks.Rule(it.pattern, it.quirks, it.note ?: "modelQuirks.json: ${it.pattern.pattern}") }
}
