// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.defaults

import com.vibe.agent.util.YamlHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Revision bookkeeping of the shared seed set (`versions.json` of the VibeBrains repo) and the
 * per-file verdict that drives seeding. Pure — no IO, no platform — so the whole decision table
 * is unit-testable.
 *
 * Why revisions at all: a seed file in someone's `.vibe/` is one of two very different things —
 * an untouched copy of an older release (updating it silently loses nothing) or the user's own
 * edit (overwriting it would, say, switch a provider back on that they deliberately turned off).
 * Content alone cannot tell them apart, so the set publishes for every file its current sha256
 * plus `history` — the sha of every past revision. A copy matching any of those was never edited.
 *
 * The `"version": 1` inside a providers jsonc is the FORMAT version (VibeIDE contract) and
 * travels with the user's copy — deliberately not reused here.
 */
data class SeedRevision(
  val version: Int,
  val sha256: String,
  val history: Set<String>,
  /**
   * Semver the set declares for this file (`vibeVersion` beside `version`/`sha256`).
   * Absent for files outside the versioning contract and for registries written before it existed
   */
  val vibeVersion: String? = null,
)

enum class SeedVerdict {
  /** Nothing on disk — plain create. */
  CREATE,

  /** Identical to the release — nothing to do. */
  SAME,

  /** Untouched copy of an older revision: safe to overwrite, nothing of the user's is lost. */
  UPDATE,

  /**
   * The user edited it AND the set moved on. Never overwritten: their edit may be «I turned this
   * provider off». Surfaced so they can compare and decide.
   */
  CONFLICT,

  /** The user's file, and the set has not moved since — their business, stay quiet. */
  USER_EDIT,

  /** Set discipline broken: content differs at the same revision (bump.mjs was not run). */
  SET_DRIFT,
}

object SeedRevisions {
  private val json = Json { ignoreUnknownKeys = true }

  /** Parse `versions.json`; a broken or absent registry degrades to «no revision data». */
  fun parse(text: String?): Map<String, SeedRevision> {
    if (text.isNullOrBlank()) return emptyMap()
    return runCatching {
      json.parseToJsonElement(text).jsonObject["files"]?.jsonObject?.mapNotNull { (path, el) ->
        val o = el.jsonObject
        val sha = o["sha256"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        path to SeedRevision(
          version = o["version"]?.jsonPrimitive?.intOrNull ?: 1,
          sha256 = sha,
          history = o["history"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet(),
          // The field is new: an older registry simply has none, and the whole seeding keeps working
          vibeVersion = o["vibeVersion"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
      }?.toMap() ?: emptyMap()
    }.getOrDefault(emptyMap())
  }

  /**
   * Verdict for one file.
   *
   * [localSha]/[localShaLf] — digest of the copy on disk (raw and LF-normalized: a CRLF checkout
   * must not read as an edit). [journalSha] — what we recorded when we seeded it, still honoured
   * for projects seeded before revisions existed. [reconciledVersion] — the revision the user
   * last said «keep mine» about; a conflict does not come back until the set moves past it.
   */
  fun verdict(
    revision: SeedRevision?,
    localSha: String?,
    localShaLf: String? = localSha,
    journalSha: String? = null,
    journalVersion: Int? = null,
    reconciledVersion: Int? = null,
  ): SeedVerdict {
    if (localSha == null) return SeedVerdict.CREATE
    val matches = { sha: String -> sha == localSha || sha == localShaLf }
    // No registry (older set, or the file is not listed): fall back to the journal alone.
    if (revision == null) {
      return if (journalSha != null && matches(journalSha)) SeedVerdict.SAME else SeedVerdict.USER_EDIT
    }
    if (matches(revision.sha256)) return SeedVerdict.SAME
    val pastRevision = revision.history.any(matches)
    val ownSeed = journalSha != null && matches(journalSha)
    // The set never moved past what the user settled on — do not re-ask.
    if (reconciledVersion != null && reconciledVersion >= revision.version) return SeedVerdict.USER_EDIT
    // A copy of a past revision is an old seed whatever the journal remembers
    // The journal lives outside git and the seed may live inside it:
    // A branch switch or a reset brings the old file back while the journal stays at the new revision
    if (pastRevision) return SeedVerdict.UPDATE
    if (ownSeed) {
      // Our own untouched seed, unknown to `history`, at the CURRENT revision: the set was edited
      // without bumping — a discipline error, not a user edit: report, do not overwrite.
      val knownRevision = journalVersion ?: 0
      return if (knownRevision >= revision.version) SeedVerdict.SET_DRIFT else SeedVerdict.UPDATE
    }
    val userIsBehind = (journalVersion ?: 0) < revision.version
    return if (userIsBehind) SeedVerdict.CONFLICT else SeedVerdict.USER_EDIT
  }
}

/**
 * The version the set declares in a file's own header — the left side of «was → became»
 *
 * It is deliberately read from the USER'S copy before an update: the registry holds only what the
 * release says now, and «was 1.0.0 → became 1.2.0» is worth more than «updated»
 *
 * Two shapes, both fixed by the shared-set contract: a skill keeps its semver under
 * `metadata.vibeVersion`, a rule (`*.mdc`) has it at the top level
 */
object SeedHeader {
  private const val SKILL_FILE = "SKILL.md"
  private const val RULE_SUFFIX = ".mdc"

  /** Version from [text], or null when the file carries none (older copies, arbitrary seeds) */
  fun version(path: String, text: String?): String? {
    val header = text?.let { headerLines(it) } ?: return null
    return when {
      path.endsWith("/" + SKILL_FILE) || path == SKILL_FILE -> YamlHeader.nestedValue(header, "metadata", "vibeVersion")
      path.endsWith(RULE_SUFFIX) -> topLevel(header, "vibeVersion")
      else -> null
    }
  }

  /** The lines between the two `---` markers; null when the file has no closed header */
  private fun headerLines(text: String): List<String>? {
    val lines = text.lines()
    if (lines.firstOrNull()?.trim() != "---") return null
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return null
    return lines.subList(1, end + 1)
  }

  private fun topLevel(header: List<String>, key: String): String? {
    var i = 0
    while (i < header.size) {
      val line = header[i]
      if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) {
        i++
        continue
      }
      val colon = line.indexOf(':')
      if (colon <= 0) {
        i++
        continue
      }
      val name = line.substring(0, colon).trim()
      val parsed = YamlHeader.read(line.substring(colon + 1), header, i + 1)
      i = parsed.next
      if (name == key) return parsed.text.takeIf { it.isNotEmpty() }
    }
    return null
  }
}
