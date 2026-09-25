// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Where a provider's key goes on the request: one rule for every wire and for the model catalog, shared with VibeIDE
 *
 * An auth the file declares is sent as written: `bearer` is a literal `Authorization: Bearer` on any wire
 * An auth nobody declared takes the wire's own header:
 * `x-api-key` on Anthropic, `x-goog-api-key` on Gemini, Bearer on the OpenAI wires
 * Only this reading changes no entry of the shared set in either product, and a written value means what it says
 *
 * Pure on purpose: the HTTP client needs the IDE to be constructed, this does not
 */
object ProviderAuth {
  /** What to add to a request: headers and query parameters, both empty when nothing is sent */
  data class Placement(val headers: Map<String, String>, val query: Map<String, String>)

  private val NOTHING = Placement(emptyMap(), emptyMap())

  /** Anthropic's own key header: its API takes a key there and reads a Bearer as an OAuth token */
  const val ANTHROPIC_KEY_HEADER = "x-api-key"

  /** Gemini's own key header: it answers a Bearer carrying an API key with an OAuth error */
  const val GEMINI_KEY_HEADER = "x-goog-api-key"

  /** Default header name for `"header"` without `name` on the OpenAI wires */
  const val DEFAULT_KEY_HEADER = "x-api-key"

  /** Default parameter name for `"query"` without `name`: the one Gemini documents */
  const val DEFAULT_KEY_PARAM = "key"

  /** The wire's own key header, or null for a wire whose own way is Bearer */
  fun nativeHeader(wire: String): String? = when (wire) {
    ModelQuirks.WIRE_ANTHROPIC -> ANTHROPIC_KEY_HEADER
    "gemini" -> GEMINI_KEY_HEADER
    else -> null
  }

  /**
   * @param declared the auth some layer wrote ([ProviderEntry.declaredAuth]); null — nobody did, the wire decides
   * @param wire the protocol the request speaks
   */
  fun placement(declared: AuthSpec?, key: String?, wire: String): Placement {
    if (key == null) return NOTHING
    val native = nativeHeader(wire)
    if (declared == null) return if (native != null) header(native, key) else bearer(key)
    return when (declared.type) {
      AuthSpec.NONE -> NOTHING
      AuthSpec.QUERY -> Placement(emptyMap(), mapOf((declared.name ?: DEFAULT_KEY_PARAM) to key))
      AuthSpec.HEADER -> header(declared.name ?: native ?: DEFAULT_KEY_HEADER, key)
      // `bearer` and anything unrecognised (warned about at load)
      else -> bearer(key)
    }
  }

  private fun header(name: String, key: String) = Placement(mapOf(name to key), emptyMap())

  private fun bearer(key: String) = Placement(mapOf("Authorization" to "Bearer $key"), emptyMap())

  /** [url] with [params] appended, after its own query string when it already has one */
  fun withQuery(url: String, params: Map<String, String>): String {
    if (params.isEmpty()) return url
    return url + (if ('?' in url) "&" else "?") + params.entries.joinToString("&") {
      URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8)
    }
  }
}
