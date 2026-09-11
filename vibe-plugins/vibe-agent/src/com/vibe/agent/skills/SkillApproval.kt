// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * What was approved is the CONTENT of a skill, not its name.
 *
 * A skill is a recipe the agent follows with the person's authority, and it lives in the
 * repository: it arrives with a pull request, changes with a rebase, and is edited by whoever has
 * commit rights. «Я разрешил grill вчера» therefore says nothing about what grill does today, and
 * the gap is not hypothetical — it is the ordinary way a repository works.
 *
 * The rule borrowed from SEP-2640, the one part of it that needs no MCP at all: approval is bound
 * to a digest, and a changed digest revokes it. Everything else in that draft is protocol we do
 * not speak.
 *
 * Pure: file hashes in, digest out, decision out. The store of what was approved belongs to the
 * caller.
 */
object SkillApproval {
  /** Hex characters kept from the digest — long enough that a collision cannot be aimed at us. */
  const val DIGEST_LENGTH = 16

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * The digest of a skill as approved: every file of its directory, by relative path and content —
   * see [SkillFiles] for why the whole directory.
   *
   * SKILL.md is one of the files, so its header is covered with the body. **The header matters most**:
   * `name` and `description` are what the approval dialog and the `/skill:` popup show first, and it
   * is precisely where the published attack puts its payload (embracethered.com, 02.2026: an
   * instruction hidden in the YAML `name` and `description` of an otherwise legitimate skill).
   */
  fun digest(files: Map<String, String>): String {
    val md = MessageDigest.getInstance("SHA-256")
    // Sorted: the filesystem's order is not a property of the skill, and a reshuffled listing
    // must not read as a change.
    for ((path, hash) in files.toSortedMap()) {
      md.update(path.toByteArray(Charsets.UTF_8))
      md.update(0)
      md.update(hash.toByteArray(Charsets.UTF_8))
      md.update(NEWLINE)
    }
    return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(DIGEST_LENGTH)
  }

  /** Which files differ from what was approved, by path. */
  data class Changes(val added: List<String>, val removed: List<String>, val modified: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && modified.isEmpty()
  }

  /** The answer to «what changed» the dialog owes the person — files, not a new digest. */
  fun changes(approved: Map<String, String>, current: Map<String, String>): Changes = Changes(
    added = (current.keys - approved.keys).sorted(),
    removed = (approved.keys - current.keys).sorted(),
    modified = current.filter { (path, hash) -> approved[path]?.let { it != hash } == true }.keys.sorted(),
  )

  /** The approved file map as it is stored beside the digest: the settings store keeps strings. */
  fun encodeFiles(files: Map<String, String>): String =
    JsonObject(files.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()

  /** Null when the stored text is not a file map — then there is nothing to compare with. */
  fun decodeFiles(text: String): Map<String, String>? =
    runCatching { json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content } }.getOrNull()

  /** What to do with a skill the person is about to use. */
  enum class Verdict {
    /** Never seen before: ask once, remember the answer. */
    NEW,

    /** Approved, and the content still matches — nothing to ask. */
    UNCHANGED,

    /** Approved earlier, but the content changed since: ask again, showing what it does now. */
    CHANGED,
  }

  fun verdictFor(current: String, approved: String?): Verdict = when {
    approved == null -> Verdict.NEW
    approved == current -> Verdict.UNCHANGED
    else -> Verdict.CHANGED
  }

  private const val NEWLINE: Byte = 0x0A
}
