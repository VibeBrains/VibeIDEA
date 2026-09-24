// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Where a provider's key goes on the request: one rule for every wire and for the model catalog
 *
 * The rule used to be written three times, and the copies drifted:
 * The Gemini wire sent the key under `"auth": "none"`, ignored the header name of `"header"`
 * And a `"query"` without a name sent the key nowhere at all
 *
 * Pure on purpose: the HTTP client needs the IDE to be constructed, this does not
 */
object ProviderAuth {
  /** What to add to a request: headers and query parameters, both empty when nothing is sent */
  data class Placement(val headers: Map<String, String>, val query: Map<String, String>)

  private val NOTHING = Placement(emptyMap(), emptyMap())

  /** Gemini's own key header: it answers a Bearer with an OAuth error, so its default is the native one */
  const val GEMINI_KEY_HEADER = "x-goog-api-key"

  /** Default header name for `"header"` without `name` on every other wire */
  const val DEFAULT_KEY_HEADER = "x-api-key"

  /** Default parameter name for `"query"` without `name`: the one Gemini documents */
  const val DEFAULT_KEY_PARAM = "key"

  /**
   * @param wire the protocol the request speaks (`gemini` changes the defaults, see [GEMINI_KEY_HEADER])
   */
  fun placement(auth: AuthSpec, key: String?, wire: String): Placement {
    if (key == null) return NOTHING
    val gemini = wire == "gemini"
    return when (auth.type) {
      AuthSpec.NONE -> NOTHING
      AuthSpec.QUERY -> Placement(emptyMap(), mapOf((auth.name ?: DEFAULT_KEY_PARAM) to key))
      AuthSpec.HEADER -> Placement(mapOf((auth.name ?: if (gemini) GEMINI_KEY_HEADER else DEFAULT_KEY_HEADER) to key), emptyMap())
      // `bearer` and anything unrecognised (warned about at load)
      else -> Placement(if (gemini) mapOf(GEMINI_KEY_HEADER to key) else mapOf("Authorization" to "Bearer $key"), emptyMap())
    }
  }

  /** [url] with [params] appended, after its own query string when it already has one */
  fun withQuery(url: String, params: Map<String, String>): String {
    if (params.isEmpty()) return url
    return url + (if ('?' in url) "&" else "?") + params.entries.joinToString("&") {
      URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8)
    }
  }
}
