// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.edits

/**
 * Refuses to let an agent write over changes it never saw.
 *
 * The situation is ordinary and the damage is silent: the agent reads a file, thinks for a minute
 * while the human edits the same file in the editor, and then writes the whole content it composed
 * from the OLD text. Nothing errors. The human's work simply disappears, and it disappears in a way
 * that looks like the agent's edit, so it is found much later — if at all.
 *
 * The check is a comparison of what the agent read against what is on disk NOW. Content, not
 * timestamps: a save that changes nothing (formatting on save, another tool rewriting the same
 * bytes) must not raise a conflict, and a change made within the same millisecond must.
 */
object WriteGuard {
  enum class Verdict {
    /** The file is as the agent last saw it — or the agent is creating it. */
    SAFE,
    /** Changed since the agent read it: writing now would destroy the change. */
    CONFLICT,
    /** The agent never read this file, so there is nothing to compare — a blind write. */
    UNSEEN,
  }

  /** What the agent saw, keyed by absolute path. Fingerprints only: the text itself would be a copy. */
  class Seen {
    private val fingerprints = HashMap<String, Int>()

    @Synchronized
    fun remember(path: String, content: String) {
      fingerprints[path] = fingerprintOf(content)
    }

    @Synchronized
    fun forget(path: String) {
      fingerprints.remove(path)
    }

    @Synchronized
    fun fingerprint(path: String): Int? = fingerprints[path]

    @Synchronized
    fun size(): Int = fingerprints.size
  }

  fun fingerprintOf(content: String): Int = content.hashCode()

  /**
   * [currentContent] is null when the file does not exist yet — creating a file cannot destroy
   * anything, and treating it as an unseen write would make every new file ask a question.
   */
  fun check(path: String, currentContent: String?, seen: Seen): Verdict {
    if (currentContent == null) return Verdict.SAFE
    val remembered = seen.fingerprint(path) ?: return Verdict.UNSEEN
    return if (remembered == fingerprintOf(currentContent)) Verdict.SAFE else Verdict.CONFLICT
  }
}
