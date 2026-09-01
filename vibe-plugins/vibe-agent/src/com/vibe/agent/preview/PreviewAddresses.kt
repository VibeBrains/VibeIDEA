// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.preview

/**
 * The addresses this project is previewed at, most recent first.
 *
 * What people actually asked for when they asked for tabs: a front end on :3000, an API on :8000
 * and the page currently under review are three addresses one moves between all day, and retyping
 * them is the whole friction. Real tabs would be a second and a third CEF browser — hundreds of
 * megabytes each — and would break the rule that this panel owns ONE browser, the one the design
 * gate measures; a second browser would quietly measure a different page.
 *
 * Pure: a list in, a list out. Persistence and the buttons live in the panel.
 */
object PreviewAddresses {
  /** Beyond this the list stops being a shortcut and becomes something to read. */
  const val MAX = 8

  /**
   * The address as it should be stored.
   *
   * `localhost:3000` is what people type and is not a URL; turning it into one here means the
   * stored list never contains two spellings of the same page.
   */
  fun normalize(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty() || text.contains(' ')) return null
    // The scheme is separated FIRST and the slashes are trimmed from the rest. The other order
    // turned «http://» into «http://http:» — trimEnd ate the separator, the leftover «http:» no
    // longer looked like a scheme, and a second one was pasted in front of it.
    val https = text.startsWith("https://")
    val body = if (https || text.startsWith("http://")) text.substringAfter("://") else text
    val trimmed = body.trimEnd('/')
    val host = trimmed.substringBefore('/')
    // A scheme and nothing else, or a colon with nothing on either side, is not an address.
    if (host.isEmpty() || host.startsWith(":") || host.endsWith(":")) return null
    return (if (https) "https://" else "http://") + trimmed
  }

  /**
   * The list after visiting [raw]: newest first, no duplicates, capped.
   *
   * A visit moves an address to the front rather than adding it twice — the list is a history of
   * places, not of clicks.
   */
  fun remember(existing: List<String>, raw: String, max: Int = MAX): List<String> {
    val address = normalize(raw) ?: return existing
    return (listOf(address) + existing.filterNot { it.equals(address, ignoreCase = true) }).take(max)
  }

  fun forget(existing: List<String>, address: String): List<String> =
    existing.filterNot { it.equals(address, ignoreCase = true) }

  /**
   * A short label for a button: the port is what distinguishes one local address from another, and
   * the scheme and the host are the same on all of them.
   */
  fun label(address: String): String {
    val rest = address.substringAfter("://")
    val host = rest.substringBefore('/')
    val path = rest.removePrefix(host)
    val short = when {
      host.startsWith("localhost:") -> ":" + host.substringAfter(':')
      host.startsWith("127.0.0.1:") -> ":" + host.substringAfter(':')
      else -> host
    }
    // The path matters when two entries differ only by it — /admin and / are different pages.
    return if (path.isEmpty() || path == "/") short else short + path.take(MAX_PATH_LABEL)
  }

  private const val MAX_PATH_LABEL = 16

  /** Restores a stored list, dropping anything that no longer parses. */
  fun parse(stored: String?): List<String> =
    stored?.split('\n').orEmpty().mapNotNull { normalize(it) }.distinct().take(MAX)

  fun store(addresses: List<String>): String = addresses.joinToString("\n")
}
