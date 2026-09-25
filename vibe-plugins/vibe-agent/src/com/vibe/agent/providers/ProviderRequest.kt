// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * The address and headers of any request to a provider: the chat on every wire and the model catalog
 *
 * One place for what the file declares (`headers`, `query`), where the key goes ([ProviderAuth]) and what a wire
 * requires on every request; VibeIDE checks the same rule against the shared vectors (`testVectors/providerAuth.json`)
 *
 * Pure: the HTTP client needs the IDE to be constructed, this does not
 */
object ProviderRequest {
  data class Target(val url: String, val headers: Map<String, String>)

  /** Anthropic rejects any request without it, `/v1/models` included */
  const val ANTHROPIC_VERSION_HEADER = "anthropic-version"
  const val ANTHROPIC_VERSION = "2023-06-01"

  /** Where the model catalog is asked: `models.fetch` as a string is the exact address, otherwise `<baseURL>/models` */
  fun catalogUrl(baseUrl: String, fetchUrl: String?): String =
    fetchUrl?.takeIf { it.isNotBlank() } ?: (baseUrl.trimEnd('/') + "/models")

  /**
   * @param wire the protocol this request speaks, which is the model's when it names one
   */
  fun target(entry: ProviderEntry, key: String?, wire: String, url: String): Target {
    val auth = ProviderAuth.placement(entry.declaredAuth, key, wire)
    val headers = LinkedHashMap(entry.headers)
    headers.putAll(auth.headers)
    // A version the file declared is the one sent: a second header of the same name is a malformed request
    if (wire == ModelQuirks.WIRE_ANTHROPIC && headers.keys.none { it.equals(ANTHROPIC_VERSION_HEADER, ignoreCase = true) }) {
      headers[ANTHROPIC_VERSION_HEADER] = ANTHROPIC_VERSION
    }
    return Target(ProviderAuth.withQuery(url, entry.query + auth.query), headers)
  }
}
