// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import java.security.MessageDigest

/**
 * Makes the journal tamper-EVIDENT: every line carries the fingerprint of the one before it.
 *
 * The agent can no longer write to the journal (see `AccessPolicy.isProtectedJournal`), but «cannot
 * through our tools» is not «cannot at all»: the file is on a disk, and anything running as the
 * person can open it. What a chain adds is not prevention, it is *evidence* — an edited, deleted or
 * inserted line breaks the link, and the break says exactly which line stopped agreeing with the
 * one before it.
 *
 * This is the control the July 2026 incident write-ups keep asking for and the one part of them we
 * can actually implement in an IDE: a verifiable evidence chain over recorded actions. It is
 * deliberately NOT a signature — a key would have to live on the same machine as the file it
 * protects, which proves nothing to anybody. A chain proves a narrower and honest thing: this
 * journal has not been edited since it was written, or here is where it was.
 *
 * Pure and offline: a hash of the previous line's hash plus this line's content.
 */
object AuditChain {
  /** The field carrying the link; short because it is written on every single line. */
  const val FIELD = "h"

  /** The first line's predecessor — a fixed value, so an empty journal has a defined start. */
  const val GENESIS = "0"

  /**
   * Hex characters in a link: enough that a forger cannot fit a collision into a record that still
   * reads as a plausible action, short enough that it does not visibly grow every line.
   */
  const val LINK_LENGTH = 12

  /**
   * The link for a line: hash(previous link + the line's own JSON, without the link itself).
   *
   * The line is hashed WITHOUT its own `h`, or the value would have to contain itself. The
   * previous link goes first so that reordering two lines breaks both.
   */
  fun link(previous: String, payload: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(previous.toByteArray(Charsets.UTF_8))
    digest.update(payload.toByteArray(Charsets.UTF_8))
    // Masked explicitly. Java's Formatter does treat a Byte as unsigned (checked, `%02x` of 0xAB
    // is «ab»), but that rule is one `toInt()` away from silently producing «ffffffab», and the
    // length of a link is load-bearing: the rotation budget is computed from it.
    return digest.digest()
      .joinToString("", limit = LINK_LENGTH / 2, truncated = "") { "%02x".format(it.toInt() and 0xFF) }
  }

  /**
   * The link carried by a JSONL line, or null when the line has none.
   *
   * Read by string rather than by parsing: the payload must be hashed EXACTLY as it was written,
   * and re-serialising a parsed object would reorder or reformat it — a check that fails on
   * untouched files teaches people to ignore it.
   */
  fun linkOf(line: String): String? {
    val marker = ",\"" + FIELD + "\":\""
    val at = line.lastIndexOf(marker)
    if (at < 0) return null
    val from = at + marker.length
    val to = line.indexOf('"', from)
    return if (to < 0) null else line.substring(from, to)
  }

  /** The line as it was hashed: everything before the link was appended. */
  fun withoutLink(line: String): String {
    val marker = ",\"" + FIELD + "\":\""
    val at = line.lastIndexOf(marker)
    return if (at < 0) line else line.substring(0, at) + "}"
  }

  /** What a verification found. */
  data class Verdict(
    val checked: Int,
    /** 1-based number of the first line that stopped agreeing with the chain, or null. */
    val brokenAtLine: Int? = null,
    /** 1-based number of the first line that carries no link at all, or null. */
    val unlinkedAtLine: Int? = null,
  ) {
    val intact: Boolean get() = brokenAtLine == null && unlinkedAtLine == null
  }

  /**
   * Walks the journal.
   *
   * Lines written before this feature existed have no link. They are reported as «unlinked» rather
   * than «broken»: calling an old honest record a forgery would teach people to ignore the check
   * on its first run, which is the only way a check like this dies.
   */
  fun verify(lines: List<String>, extractLink: (String) -> String?, stripLink: (String) -> String): Verdict {
    var previous = GENESIS
    var unlinked: Int? = null
    lines.forEachIndexed { index, line ->
      if (line.isBlank()) return@forEachIndexed
      val carried = extractLink(line)
      if (carried == null) {
        if (unlinked == null) unlinked = index + 1
        // An unlinked line still participates: the chain continues over it rather than restarting,
        // so a link added later still depends on everything written before it.
        previous = link(previous, stripLink(line))
        return@forEachIndexed
      }
      val expected = link(previous, stripLink(line))
      if (carried != expected) return Verdict(checked = index + 1, brokenAtLine = index + 1, unlinkedAtLine = unlinked)
      previous = carried
    }
    return Verdict(checked = lines.count { it.isNotBlank() }, unlinkedAtLine = unlinked)
  }
}
