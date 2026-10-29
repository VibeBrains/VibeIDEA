// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import com.intellij.ide.util.PropertiesComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Values a provider declares with `params` and how they reach a request.
 *
 * The shared set writes the address with placeholders (`https://{region}-aiplatform.googleapis.com/…`):
 * a literal `{region}` in a request is not a wrong answer but no answer at all, and it is invisible
 * until someone reads the vendor's 400. Values are entered by the person in the provider card and live
 * in IDE settings under the provider id — the file itself stays free of anyone's account.
 *
 * Substitution covers exactly what the format promises: `baseURL`, the string form of `models.fetch`,
 * the values of `headers` and `query`. A whole header or query value that rests on an optional
 * parameter which nobody filled in is dropped rather than sent empty.
 */
object ProviderParams {
  private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_.-]+)}")
  private val WHOLE_PLACEHOLDER = Regex("^\\{([A-Za-z0-9_.-]+)}$")
  private val json = Json { ignoreUnknownKeys = true }

  /** The value in force: what the person entered, otherwise `default`; blank counts as nothing at either source. */
  fun valueOf(param: ProviderParam, entered: Map<String, String>): String? =
    entered[param.name]?.takeIf { it.isNotBlank() } ?: param.default?.takeIf { it.isNotBlank() }

  /** Parameters that have no value and no default and were not declared optional. */
  fun missing(entry: ProviderEntry, entered: Map<String, String>): List<ProviderParam> =
    entry.params.filter { !it.optional && valueOf(it, entered) == null }

  /**
   * The entry with every template filled in, or null when a required parameter has no value.
   *
   * Null means «not configured»: the provider stays in the catalogue and in the settings card,
   * where its fields can be filled, but no request is built for it.
   */
  fun prepare(entry: ProviderEntry, entered: Map<String, String>): ProviderEntry? {
    if (missing(entry, entered).isNotEmpty()) return null
    val values = entry.params.mapNotNull { p -> valueOf(p, entered)?.let { p.name to it } }.toMap()
    val headers = LinkedHashMap<String, String>()
    for ((name, template) in entry.headers) {
      expandOrDrop(template, values, entry.params)?.let { headers[name] = it }
    }
    for (param in entry.params.filter { it.headers }) {
      val raw = valueOf(param, entered) ?: continue
      headers.putAll(parseHeaders(raw))
    }
    val query = LinkedHashMap<String, String>()
    for ((name, template) in entry.query) {
      expandOrDrop(template, values, entry.params)?.let { query[name] = it }
    }
    return entry.copy(
      baseURL = entry.baseURL?.let { substitute(it, values, entry.params) },
      modelsFetch = entry.modelsFetch?.let { fetch ->
        fetch.url?.let { fetch.copy(url = substitute(it, values, entry.params)) } ?: fetch
      },
      headers = headers,
      query = query,
    )
  }

  /**
   * A template with every known placeholder replaced.
   *
   * A placeholder of an optional parameter with no value becomes the empty string — that is what
   * «optional» means inside a longer value. A name that is not declared is left as written: braces
   * may belong to the vendor's own URL, and eating them is worse than sending them.
   */
  fun substitute(template: String, values: Map<String, String>, params: List<ProviderParam>): String =
    PLACEHOLDER.replace(template) { match ->
      val name = match.groupValues[1]
      val param = params.find { it.name == name } ?: return@replace match.value
      values[name] ?: if (param.optional) "" else match.value
    }

  /** A header or query value: null when the whole value is one optional placeholder nobody filled. */
  private fun expandOrDrop(template: String, values: Map<String, String>, params: List<ProviderParam>): String? {
    val whole = WHOLE_PLACEHOLDER.matchEntire(template.trim())
    if (whole != null) {
      val name = whole.groupValues[1]
      val param = params.find { it.name == name } ?: return substitute(template, values, params)
      if (values.containsKey(name)) return values[name]
      return if (param.optional) null else substitute(template, values, params)
    }
    return substitute(template, values, params)
  }

  /** `as: "headers"` value: a JSON object of headers. A broken one contributes nothing. */
  private fun parseHeaders(raw: String): Map<String, String> = runCatching {
    json.parseToJsonElement(raw).jsonObject.mapNotNull { (k, v) ->
      val text = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
      k.trim().takeIf { it.isNotEmpty() }?.let { it to text }
    }.toMap()
  }.getOrDefault(emptyMap())
}

/**
 * Where the values entered in the provider card live: IDE settings, per provider id and parameter name.
 *
 * Not in `providers.json`: the file is shared and committed, while a region or a project is a fact
 * about one person's account. Not in the keychain either: none of these is a secret.
 */
object ProviderParamStore {
  private const val PREFIX = "vibe.providers.params."

  fun key(providerId: String, name: String): String = PREFIX + providerId + "." + name

  /** Everything entered for this provider, keyed by parameter name; empty when the IDE has no settings store. */
  fun values(entry: ProviderEntry): Map<String, String> {
    // No declared fields — no reason to touch the platform at all: every seeded provider except the
    // account-bound ones lands here, and so does every unit test that resolves a provider.
    if (entry.params.isEmpty()) return emptyMap()
    return runCatching {
      val properties = PropertiesComponent.getInstance()
      entry.params.mapNotNull { p -> properties.getValue(key(entry.id, p.name))?.let { p.name to it } }.toMap()
    }.getOrDefault(emptyMap())
  }

  fun store(providerId: String, name: String, value: String?) {
    runCatching {
      val properties = PropertiesComponent.getInstance()
      if (value.isNullOrBlank()) properties.unsetValue(key(providerId, name))
      else properties.setValue(key(providerId, name), value)
    }
  }
}
