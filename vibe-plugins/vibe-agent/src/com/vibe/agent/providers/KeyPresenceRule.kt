// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * How to answer "is there a stored key" — a pure rule, apart from any storage.
 *
 * Extracted so it can be tested: the non-macOS branch is unreachable on a developer's Mac, and that branch is exactly
 * the one that used to be wrong. Folding "could not ask" into "no key" meant that outside macOS, where the keychain
 * probe always answers "could not ask", every stored key was reported missing.
 *
 * The rule in full: a password dialog comes only from reading a VALUE, and only from the macOS keychain. So where
 * there is no such keychain the value is read freely, and where there is one the probe goes first.
 */
object KeyPresenceRule {
  /** Whether the value has to be read to answer. */
  fun mustReadValue(onMac: Boolean, knownInThisRun: Boolean): Boolean = !knownInThisRun && !onMac

  /**
   * The answer, from what could be learned.
   *
   * @param probe the keychain probe's answer; not asked at all outside macOS
   * @param valueFound whether the value was read (where reading is allowed)
   */
  fun decide(
    onMac: Boolean,
    knownInThisRun: Boolean,
    probe: KeychainProbe.State,
    valueFound: Boolean,
  ): ApiKeyResolver.Presence = when {
    knownInThisRun -> ApiKeyResolver.Presence.PRESENT
    !onMac -> if (valueFound) ApiKeyResolver.Presence.PRESENT else ApiKeyResolver.Presence.ABSENT
    probe == KeychainProbe.State.PRESENT -> ApiKeyResolver.Presence.PRESENT
    probe == KeychainProbe.State.ABSENT -> ApiKeyResolver.Presence.ABSENT
    // The keychain did not answer. Reading the value here would bring back the password dialogs the probe exists to
    // avoid, so the answer is "unknown" rather than "no": told "no key", a person goes to re-enter a key that may be fine.
    else -> ApiKeyResolver.Presence.UNKNOWN
  }
}
