// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.i18n.VibeI18n.t

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * providers.json — the VibeIDE contract, faithfully:
 * root {version, providers[]}; JSONC (line comments, trailing commas);
 * a malformed entry is skipped with a warning and never kills the registry;
 * `extends` clones another entry, same-id entries patch each other;
 * workspace file overrides the global one field-by-field, models.static merges by model id.
 * Keys are NEVER stored in this file: apiKeyRef (secure storage) / apiKeyEnv (.vibe/.env, OS env).
 */
data class ModelEntry(
  val id: String,
  val name: String = id,
  /** An entry without `active` counts as ON — deliberately NOT tri-state: a user's own
   *  entry layered over an inactive seeded one must come out alive (see the spec). */
  val active: Boolean = true,
  val default: Boolean = false,
  val pinned: Boolean = false,
  val contextWindow: Int? = null,
  val maxOutputTokens: Int? = null,
  val temperature: Double? = null,
  val topP: Double? = null,
  val topK: Int? = null,
  val extraBody: JsonObject? = null,
  /**
   * The wire protocol for THIS model, when it differs from the provider's.
   *
   * One key, three formats: OpenCode Go serves MiniMax and Qwen over an Anthropic-compatible
   * `/v1/messages` and GLM/Kimi/DeepSeek over `/v1/chat/completions` — under one provider, one
   * base URL and one key. While `protocol` lived only on the provider, such a provider could not
   * be described honestly at all: whichever value you chose, half its models were called wrong.
   */
  val protocol: String? = null,
  val fim: Boolean = false,
  /** Accepts images: null = unknown (attachments allowed), false = composer blocks image sends. */
  val vision: Boolean? = null,
  val note: String? = null,
  /**
   * The day access to this model ends, ISO (`2026-11-12`).
   *
   * Written down because such dates are ANNOUNCED in advance, and an announcement nobody recorded
   * is an announcement that turns into a surprise on the day.
   */
  val sunsetDate: String? = null,
)

data class AuthSpec(val type: String = "bearer", val name: String? = null)

/** Which providers.json the entry ultimately came from (set after merge, not parsed). */
enum class ProviderOrigin { GLOBAL, PROJECT, OVERRIDDEN }

/** `models.fetch` decoded: absent entry = null on the field (defaults to enabled). */
data class ModelsFetch(val enabled: Boolean, val url: String? = null)

data class ProviderEntry(
  val id: String,
  val name: String = id,
  /** An entry without `active` counts as ON — deliberately NOT tri-state: a user's own
   *  entry layered over an inactive seeded one must come out alive (see the spec). A patch
   *  that must NOT activate its target repeats `"active": false` explicitly. */
  val active: Boolean = true,
  val order: Int? = null,
  val protocol: String? = null,
  val baseURL: String? = null,
  val auth: AuthSpec = AuthSpec(),
  val apiKeyEnv: String? = null,
  val apiKeyRef: String? = null,
  val headers: Map<String, String> = emptyMap(),
  val query: Map<String, String> = emptyMap(),
  val timeoutMs: Long? = null,
  val extendsId: String? = null,
  /** models.fetch: absent = null (counts as enabled, `<baseURL>/models`); explicit true/false/URL survive overlays. */
  val modelsFetch: ModelsFetch? = null,
  val models: List<ModelEntry> = emptyList(),
  val note: String? = null,
  val origin: ProviderOrigin? = null,
)

object ProvidersFile {
  private val json = Json { ignoreUnknownKeys = true }

  /** Strip JSONC: `//` line comments (outside strings) and trailing commas. */
  fun stripJsonc(text: String): String {
    val sb = StringBuilder(text.length)
    var inString = false
    var escaped = false
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        escaped -> { sb.append(c); escaped = false }
        inString && c == '\\' -> { sb.append(c); escaped = true }
        c == '"' -> { sb.append(c); inString = !inString }
        !inString && c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
          while (i < text.length && text[i] != '\n') i++
          continue
        }
        else -> sb.append(c)
      }
      i++
    }
    // trailing commas: `,` directly before `]` or `}` (whitespace between allowed)
    return Regex(",(\\s*[}\\]])").replace(sb.toString(), "$1")
  }

  fun parse(text: String, source: String = "providers.json", onWarning: (String) -> Unit): List<ProviderEntry> {
    val root = json.parseToJsonElement(stripJsonc(text)).jsonObject
    val providers = root["providers"]?.jsonArray ?: run {
      onWarning(t("providers.warn.noArray", "source" to source))
      return emptyList()
    }
    val result = ArrayList<ProviderEntry>()
    for (el in providers) {
      try {
        val o = el.jsonObject
        val id = o["id"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank()) { onWarning(t("providers.warn.noId", "source" to source)); continue }
        result.add(parseProvider(id, o))
      }
      catch (e: Exception) {
        onWarning(t("providers.warn.entrySkipped", "source" to source, "reason" to e.message))
      }
    }
    return result
  }

  private fun parseProvider(id: String, o: JsonObject): ProviderEntry {
    val auth = when (val a = o["auth"]) {
      null -> AuthSpec()
      else -> if (a is kotlinx.serialization.json.JsonPrimitive) AuthSpec(type = a.content)
              else AuthSpec(
                type = a.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "bearer",
                name = a.jsonObject["name"]?.jsonPrimitive?.contentOrNull,
              )
    }
    val modelsObj = o["models"]?.jsonObject
    val fetchEl = modelsObj?.get("fetch")
    val modelsFetch: ModelsFetch? = when {
      fetchEl == null -> null
      fetchEl is kotlinx.serialization.json.JsonPrimitive && fetchEl.booleanOrNull == false -> ModelsFetch(enabled = false)
      fetchEl is kotlinx.serialization.json.JsonPrimitive && fetchEl.booleanOrNull == true -> ModelsFetch(enabled = true)
      else -> ModelsFetch(enabled = true, url = fetchEl.jsonPrimitive.contentOrNull)
    }
    val models = modelsObj?.get("static")?.jsonArray?.mapNotNull { m ->
      val mo = m.jsonObject
      val mid = mo["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      ModelEntry(
        id = mid,
        name = mo["name"]?.jsonPrimitive?.contentOrNull ?: mid,
        active = mo["active"]?.jsonPrimitive?.booleanOrNull ?: true,
        default = mo["default"]?.jsonPrimitive?.booleanOrNull ?: false,
        pinned = mo["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
        contextWindow = mo["contextWindow"]?.jsonPrimitive?.intOrNull,
        maxOutputTokens = mo["maxOutputTokens"]?.jsonPrimitive?.intOrNull,
        temperature = mo["temperature"]?.jsonPrimitive?.doubleOrNull,
        topP = mo["topP"]?.jsonPrimitive?.doubleOrNull,
        topK = mo["topK"]?.jsonPrimitive?.intOrNull,
        extraBody = mo["extraBody"] as? JsonObject,
        protocol = mo["protocol"]?.jsonPrimitive?.contentOrNull,
        fim = mo["fim"]?.jsonPrimitive?.booleanOrNull ?: false,
        vision = mo["vision"]?.jsonPrimitive?.booleanOrNull,
        note = mo["note"]?.jsonPrimitive?.contentOrNull,
        sunsetDate = mo["sunsetDate"]?.jsonPrimitive?.contentOrNull,
      )
    } ?: emptyList()
    return ProviderEntry(
      id = id,
      name = o["name"]?.jsonPrimitive?.contentOrNull ?: id,
      active = o["active"]?.jsonPrimitive?.booleanOrNull ?: true,
      order = o["order"]?.jsonPrimitive?.intOrNull,
      protocol = o["protocol"]?.jsonPrimitive?.contentOrNull,
      baseURL = o["baseURL"]?.jsonPrimitive?.contentOrNull,
      auth = auth,
      apiKeyEnv = o["apiKeyEnv"]?.jsonPrimitive?.contentOrNull,
      apiKeyRef = o["apiKeyRef"]?.jsonPrimitive?.contentOrNull,
      headers = o["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
      query = o["query"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
      timeoutMs = o["timeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
      extendsId = o["extends"]?.jsonPrimitive?.contentOrNull,
      modelsFetch = modelsFetch,
      models = models,
      note = o["note"]?.jsonPrimitive?.contentOrNull,
    )
  }

  /**
   * Resolve `extends` over the fully merged registry — a single strict pass, so the
   * semantics are uniform: the base is always the final (post-merge) entry, whichever
   * file or scope it came from, active or not. Single-level (no chains), as specced.
   */
  fun resolveExtends(entries: List<ProviderEntry>, onWarning: (String) -> Unit): List<ProviderEntry> {
    val byId = entries.associateBy { it.id }
    return entries.map { e ->
      val base = e.extendsId?.let { byId[it] }
      if (e.extendsId != null && base == null) {
        onWarning(t("providers.warn.extendsMissing", "parent" to e.extendsId, "id" to e.id))
      }
      if (base == null || base === e) e.copy(extendsId = null)
      else overlay(base, e).copy(id = e.id, extendsId = null)
    }
  }

  /** Workspace overrides global field-by-field; models.static merge by model id; workspace-only appended. */
  fun merge(global: List<ProviderEntry>, workspace: List<ProviderEntry>): List<ProviderEntry> {
    val result = ArrayList<ProviderEntry>()
    val wsById = workspace.associateBy { it.id }
    for (g in global) {
      val w = wsById[g.id]
      result.add(if (w == null) g else overlay(g, w))
    }
    val globalIds = global.map { it.id }.toSet()
    workspace.filter { it.id !in globalIds }.forEach { result.add(it) }
    return result.sortedWith(compareBy({ it.order ?: Int.MAX_VALUE }, { it.name }))
  }

  /** Same-id model: the override wins; tri-state `vision` falls back to the base when unknown. */
  private fun overlayModel(base: ModelEntry?, over: ModelEntry): ModelEntry =
    if (base == null) over else over.copy(
      vision = over.vision ?: base.vision,
      // Same rule as every other optional field: silence inherits, a written value overrides.
      protocol = over.protocol ?: base.protocol,
    )

  private fun overlay(base: ProviderEntry, over: ProviderEntry): ProviderEntry {
    val mergedModels = LinkedHashMap<String, ModelEntry>()
    base.models.forEach { mergedModels[it.id] = it }
    over.models.forEach { m -> mergedModels[m.id] = overlayModel(mergedModels[m.id], m) }
    return ProviderEntry(
      id = base.id,
      name = if (over.name != over.id) over.name else base.name,
      // Deliberately the override alone: an entry without `active` is ON, so a user's own
      // entry over an inactive seed comes out alive; a patch that must stay inactive
      // repeats `"active": false` (documented in the spec).
      active = over.active,
      order = over.order ?: base.order,
      protocol = over.protocol ?: base.protocol,
      baseURL = over.baseURL ?: base.baseURL,
      auth = if (over.auth != AuthSpec()) over.auth else base.auth,
      apiKeyEnv = over.apiKeyEnv ?: base.apiKeyEnv,
      apiKeyRef = over.apiKeyRef ?: base.apiKeyRef,
      headers = base.headers + over.headers,
      query = base.query + over.query,
      timeoutMs = over.timeoutMs ?: base.timeoutMs,
      // An unresolved `extends` must survive layer merges: the single strict pass runs
      // over the fully merged registry, so the base may live in another file or scope.
      extendsId = over.extendsId ?: base.extendsId,
      modelsFetch = over.modelsFetch ?: base.modelsFetch,
      models = mergedModels.values.toList(),
      note = over.note ?: base.note,
    )
  }
}
