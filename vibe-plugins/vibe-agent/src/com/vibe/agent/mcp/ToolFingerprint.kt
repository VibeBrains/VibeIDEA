// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.providers.ToolSpec
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
   */
  fun of(spec: ToolSpec): String = sha256(listOf(spec.name, spec.description, spec.schema.toString()))

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
   */
  fun compare(approved: Map<String, String>, current: Map<String, String>): Drift = Drift(
    added = current.keys.filter { it !in approved }.sorted(),
    removed = approved.keys.filter { it !in current }.sorted(),
    changed = current.filter { (name, print) -> approved[name] != null && approved[name] != print }.keys.sorted(),
  )

  /** Per-tool fingerprints — what is stored together with an approval. */
  fun map(specs: List<ToolSpec>): Map<String, String> = specs.associate { it.name to of(it) }

  private fun sha256(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    // A separator that never occurs in names or descriptions: without it "ab"+"c" and "a"+"bc" would hash the same,
    // and text moved from one field to another would slip through unnoticed.
    parts.forEach { digest.update(it.toByteArray()); digest.update(0) }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
