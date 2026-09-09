// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

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
 * Pure: text in, digest out, decision out. The store of what was approved belongs to the caller.
 */
object SkillApproval {
  /** Hex characters kept from the digest — long enough that a collision cannot be aimed at us. */
  const val DIGEST_LENGTH = 16

  /**
   * The digest of a skill as approved: its header, its body, and the names of everything shipped
   * beside it.
   *
   * Attachment names are part of it because a skill that gains a file gains a capability — the
   * body may be unchanged while the recipe now points at a script that was not there yesterday.
   *
   * **The header is part of it because the header is what the person actually read.** `name` and
   * `description` are the whole of what the approval dialog and the `/skill:` popup show; the body
   * is one click further away and most people never open it. A digest over the body alone therefore
   * left the reviewed half of the skill free to change without revoking anything — and that is
   * precisely where the published attack puts its payload (embracethered.com, 02.2026: an
   * instruction hidden in the YAML `name` and `description` of an otherwise legitimate skill).
   */
  fun digest(body: String, attachments: List<String> = emptyList(), header: String = ""): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(header.toByteArray(Charsets.UTF_8))
    md.update(0)
    md.update(body.toByteArray(Charsets.UTF_8))
    // Sorted: the filesystem's order is not a property of the skill, and a reshuffled listing
    // must not read as a change.
    attachments.sorted().forEach {
      md.update(0)
      md.update(it.toByteArray(Charsets.UTF_8))
    }
    return md.digest().joinToString("", limit = DIGEST_LENGTH / 2, truncated = "") {
      "%02x".format(it.toInt() and 0xFF)
    }
  }

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
}
