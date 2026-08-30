// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.i18n.VibeI18n.t

/**
 * One line about a whole round of catalog polling instead of a line per provider.
 *
 * A registry seeded with a dozen providers has, on a fresh machine, keys for one or two of them —
 * so the honest per-provider log was nine red-looking lines at every start, and the two providers
 * that actually worked drowned in it. The states are deliberately distinguished, because they call
 * for different actions from the person:
 *
 * - [keyless] — no key at all: nothing failed, there is simply nothing to ask with (→ Провайдеры);
 * - [rejected] — a key exists and the provider refused it (401/403): the key is wrong or expired;
 * - [localDown] — a local endpoint (ollama, vLLM) is not running: start it or ignore;
 * - [failed] — anything else (timeout, DNS, 5xx): the network or the provider.
 *
 * Pure by design: no I/O, no Swing — this is the piece worth testing.
 */
data class CatalogReport(
  val updated: List<String> = emptyList(),
  val keyless: List<String> = emptyList(),
  val rejected: List<String> = emptyList(),
  val localDown: List<String> = emptyList(),
  val failed: List<Pair<String, String>> = emptyList(),
) {
  val isEmpty: Boolean
    get() = updated.isEmpty() && keyless.isEmpty() && rejected.isEmpty() && localDown.isEmpty() && failed.isEmpty()

  /** The whole round in one line; empty string when there is nothing to say. */
  fun summary(): String {
    if (isEmpty) return ""
    val parts = buildList {
      if (updated.isNotEmpty()) add(t("catalog.updated", "count" to updated.size, "list" to updated.joinToString(", ")))
      if (keyless.isNotEmpty()) {
        add(t("catalog.keyless", "count" to keyless.size, "list" to keyless.joinToString(", ")))
      }
      if (rejected.isNotEmpty()) add(t("catalog.rejected", "list" to rejected.joinToString(", ")))
      if (localDown.isNotEmpty()) add(t("catalog.localDown", "list" to localDown.joinToString(", ")))
      if (failed.isNotEmpty()) add(t("catalog.failed", "list" to failed.joinToString(", ") { "${it.first} (${it.second})" }))
    }
    return t("catalog.summaryPrefix") + " " + parts.joinToString("; ")
  }

  companion object {
    /** HTTP 401/403 from a provider that was given a key: the key itself is the problem. */
    fun isRejectedKey(message: String?): Boolean =
      message != null && (message.contains("HTTP 401") || message.contains("HTTP 403"))

    /**
     * `ConnectException` arrives with a null message, and «каталог не получен (null)» tells the
     * user nothing — fall back to the exception's own name.
     */
    fun reason(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
  }
}
