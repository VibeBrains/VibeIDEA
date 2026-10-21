// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Fingerprint of an MCP server's tool set, and what changed in it.
 *
 * A tool description is what a person reads when deciding whether to let a call through, which makes it a
 * security boundary rather than documentation. A known supply-chain technique exploits exactly that: the server
 * counts calls per client and, after the third `tools/call`, starts returning different `tools/list` and
 * `prompts/get` contents — instructions to hunt for SSH keys, cloud credentials and cluster configs. The threshold
 * is chosen so that a short review never reaches it and real work always does
 * (pillar.security/blog/deadbugz-currently-active-mcp-supply-chain-campaign).
 *
 * Asking the person before every foreign call does not help here: the question is phrased by the description,
 * and the description is what gets swapped. So the set is fingerprinted when the person first accepts it and
 * compared on every new connection; a change is a security event that must be shown and re-approved.
 */
object ToolFingerprint {
  /**
   * Fingerprint of one tool: name, description and input schema.
   *
   * The schema is part of it on purpose: a new `path` parameter on a harmless formatter changes what a call does
   * without touching a single word of the description.
   *
   * The schema is hashed in a comparable form ([comparableSchema]), not as the server wrote it: a server that builds a
   * list from a set (Python `set`, JVM `HashSet`) returns the same words in another order on every launch, and a
   * fingerprint of the raw text would call each launch a swap.
   */
  fun of(spec: ToolSpec): String = sha256(listOf(spec.name, spec.description, comparableSchema(spec.schema).toString()))

  /**
   * Fingerprint the way it was computed before the schema was normalized: raw schema text.
   *
   * Approvals stored by earlier builds hold this form. A tool whose current legacy fingerprint equals the stored one
   * is byte-for-byte what the person approved, so it is not a change; see [compare].
   */
  fun legacyOf(spec: ToolSpec): String = sha256(listOf(spec.name, spec.description, spec.schema.toString()))

  /** Fingerprint of the whole set: order does not matter, membership does. */
  fun ofAll(specs: List<ToolSpec>): String = sha256(specs.map { of(it) }.sorted())

  /** What changed, in terms that can be shown to a person. */
  data class Drift(val added: List<String>, val removed: List<String>, val changed: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
  }

  /**
   * Compare the approved set with the current one.
   *
   * A changed description and a changed schema give the same answer — "this tool is different now". Telling them
   * apart would not help the person: what matters is that the consent was given to something else.
   *
   * [legacy] is the current set in the old fingerprint form. An approval stored before normalization existed matches
   * it exactly when nothing changed, which keeps an update of the IDE from flagging every approved tool at once.
   */
  fun compare(approved: Map<String, String>, current: Map<String, String>, legacy: Map<String, String> = emptyMap()): Drift = Drift(
    added = current.keys.filter { it !in approved }.sorted(),
    removed = approved.keys.filter { it !in current }.sorted(),
    changed = current.filter { (name, print) ->
      val was = approved[name]
      was != null && was != print && was != legacy[name]
    }.keys.sorted(),
  )

  /** Per-tool fingerprints in the pre-normalization form, to pass as `legacy` to [compare]. */
  fun legacyMap(specs: List<ToolSpec>): Map<String, String> = specs.associate { it.name to legacyOf(it) }

  /** Per-tool fingerprints — what is stored together with an approval. */
  fun map(specs: List<ToolSpec>): Map<String, String> = specs.associate { it.name to of(it) }

  /** Schema keywords whose array value is a set: JSON Schema gives the order of its items no meaning. */
  private val UNORDERED_KEYWORDS = setOf("required", "enum", "type")

  /** Schema keywords whose value is a hint: compared as the multiset of its words. */
  private val HINT_KEYWORDS = setOf("default", "examples", "example")

  /** Keywords whose value is a table "name -> schema": its keys are names, so a property may be called `default`. */
  private val SCHEMA_TABLE_KEYWORDS = setOf("properties", "patternProperties", "\$defs", "definitions", "dependentSchemas")

  /** Separators of hint words: whitespace and what servers use to glue a list into a string. */
  private val HINT_SEPARATORS = Regex("[\\s,;|]+")

  /**
   * The schema with everything a server may reorder brought to one order, and keys sorted at every level.
   *
   * A swap is made of new words: an order in a description, a new parameter, a new value. Reordering words that were
   * already approved tells the model nothing new, so a new word in a hint still changes the fingerprint.
   * Descriptions and titles stay verbatim: prose changes meaning with word order. `const` is left as it is.
   */
  fun comparableSchema(node: JsonElement): JsonElement = sortKeys(normalize(node))

  private fun normalize(node: JsonElement): JsonElement = when (node) {
    is JsonArray -> JsonArray(node.map { normalize(it) })
    is JsonObject -> JsonObject(node.mapValues { (keyword, value) ->
      when {
        keyword in HINT_KEYWORDS -> JsonObject(mapOf("words" to JsonArray(hintWords(value).sorted().map { JsonPrimitive(it) })))
        keyword in UNORDERED_KEYWORDS && value is JsonArray -> JsonArray(value.map { sortKeys(it).toString() }.sorted().map { JsonPrimitive(it) })
        keyword in SCHEMA_TABLE_KEYWORDS && value is JsonObject -> JsonObject(value.mapValues { normalize(it.value) })
        keyword == "const" -> value
        else -> normalize(value)
      }
    })
    else -> node
  }

  private fun hintWords(value: JsonElement): List<String> = when (value) {
    is JsonArray -> value.flatMap { hintWords(it) }
    is JsonObject -> value.flatMap { (key, item) -> splitWords(key) + hintWords(item) }
    is JsonNull -> splitWords("null")
    is JsonPrimitive -> splitWords(value.content)
  }

  private fun splitWords(text: String): List<String> = text.split(HINT_SEPARATORS).filter { it.isNotEmpty() }

  private fun sortKeys(node: JsonElement): JsonElement = when (node) {
    is JsonArray -> JsonArray(node.map { sortKeys(it) })
    is JsonObject -> JsonObject(node.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
    else -> node
  }

  private fun sha256(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    // A separator that never occurs in names or descriptions: without it "ab"+"c" and "a"+"bc" would hash the same,
    // and text moved from one field to another would slip through unnoticed.
    parts.forEach { digest.update(it.toByteArray()); digest.update(0) }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
