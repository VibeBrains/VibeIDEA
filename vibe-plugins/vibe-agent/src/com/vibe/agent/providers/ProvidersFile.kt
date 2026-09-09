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
 *
 * **Money fields are spelled `cost*` on the wire** — `cost`, `costValidUntil`, `costAfter`,
 * `costNote` — because the seed catalogue is shared with VibeIDE and that is its spelling. Our
 * older `pricing` / `priceValidUntil` / `priceAfter` are still accepted as synonyms so that files
 * already written by hand keep working; new files and seeds use `cost*`.
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
  /**
   * What a million tokens of this model costs on YOUR contract; absent means «не сказано».
   *
   * Ours to apply, never ours to invent: a price list in our code is wrong the day a vendor
   * changes a line, but a price the person typed is a fact about their tariff.
   */
  val pricing: ModelPricing? = null,
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
  /**
   * До какой даты цена выше верна (ISO, `2026-09-09`).
   *
   * Промо-цены кончаются по расписанию и вдвое; без срока учёт расхода продолжает считаться по
   * старой цене и выглядит достоверным. Разбор — [PriceValidity].
   */
  val priceValidUntil: String? = null,
  /**
   * Цена, которая наступит после [priceValidUntil], — тот же формат, что и `pricing`.
   *
   * Решение принимают не по «цена протухла», а по второму числу: удвоение и удесятерение
   * требуют разных действий, а строка «срок вышел» одинакова для обоих. Вендоры объявляют
   * новую цену вместе с датой (Gemini 3.8 Flash: $0.75/$3.75 до 31.12.2026, затем ровно
   * вдвое), поэтому записать её есть куда и есть откуда.
   *
   * Расход по ней НЕ считается никогда: это будущее, а счёт — про сегодня.
   */
  val priceAfter: ModelPricing? = null,
  /**
   * Где объявлена цена — ссылка или фраза вендора (`costNote` в файле).
   *
   * Нужна там, где мы говорим «цена протухла» или «скоро протухнет»: без неё человеку сказано, что
   * надо свериться, но не сказано с чем, и сверка откладывается. Своей цены мы не знаем (решение
   * №40), поэтому источник — единственное, чем мы можем помочь.
   */
  val priceNote: String? = null,
  /**
   * Какие уровни рассуждения модель принимает (`"reasoning"` в файле); null — не сказано.
   *
   * Ползунок глубины один на приложение, а наборы уровней у моделей разные. Без этого объявления
   * ползунок отправлял выбранный уровень любой модели — и там, где его нет, вендор либо отвечал
   * 400, либо молча игнорировал, что хуже: человек двигает ручку и не видит разницы.
   */
  val reasoning: ReasoningMode.Support? = null,
  /**
   * Срок жизни кэша промпта: `5m` (умолчание вендора) или `1h`.
   *
   * Часовой кэш существует ровно для нашего сценария — длинная сессия с паузами больше пяти минут
   * (ревью, ожидание CI). Он дороже в записи ($20 против $12.50 за 1M у Fable 5.1), поэтому
   * включается осознанно, а не нами за пользователя.
   */
  val cacheTtl: String? = null,
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

  /**
   * A price block, or null when it is absent or says nothing.
   *
   * Shared by `pricing` and `priceAfter` on purpose: two readers of the same shape drift, and the
   * future price would end up accepting fields today's price rejects.
   */
  private fun parsePricing(pr: JsonObject?): ModelPricing? = pr?.let {
    ModelPricing(
      input = it["input"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
      output = it["output"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
      cacheRead = it["cacheRead"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
      cacheWrite = it["cacheWrite"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
      currency = it["currency"]?.jsonPrimitive?.contentOrNull ?: ModelPricing.DEFAULT_CURRENCY,
    ).takeIf { p -> p.stated }
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
        pricing = parsePricing((mo["cost"] ?: mo["pricing"]) as? JsonObject),
        fim = mo["fim"]?.jsonPrimitive?.booleanOrNull ?: false,
        vision = mo["vision"]?.jsonPrimitive?.booleanOrNull,
        note = mo["note"]?.jsonPrimitive?.contentOrNull,
        sunsetDate = mo["sunsetDate"]?.jsonPrimitive?.contentOrNull,
        priceValidUntil = text(mo, "costValidUntil", "priceValidUntil"),
        cacheTtl = mo["cacheTtl"]?.jsonPrimitive?.contentOrNull,
        priceAfter = parsePricing((mo["costAfter"] ?: mo["priceAfter"]) as? JsonObject),
        priceNote = text(mo, "costNote", "priceNote"),
        reasoning = parseReasoning(mo["reasoning"] as? JsonObject),
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
   * The first of two wire names that the object actually carries.
   *
   * Exists for one reason: the seed catalogue is SHARED with VibeIDE, and the money fields are
   * spelled `cost` / `costValidUntil` / `costAfter` / `costNote` there. Reading only our older
   * `pricing` / `price*` made a seeded price silently invisible — not an error, not a warning, just
   * a model with no price and a validity date that never expires. A synonym costs one lookup; a
   * rename would break every `providers.json` already written by hand.
   */
  private fun text(o: JsonObject, canonical: String, legacy: String): String? =
    (o[canonical] ?: o[legacy])?.jsonPrimitive?.contentOrNull

  /**
   * `"reasoning": { "canTurnOff": true, "effort": ["low", "high"] }` — что умеет эта модель.
   *
   * Неизвестное слово уровня пропускается, а не роняет запись: более новый вендор может назвать
   * уровень так, как эта сборка ещё не знает, и терять из-за одного слова весь список — значит
   * менять частичное знание на никакое.
   */
  private fun parseReasoning(o: JsonObject?): ReasoningMode.Support? {
    if (o == null) return null
    val levels = (o["effort"] as? kotlinx.serialization.json.JsonArray)
      ?.mapNotNull { el ->
        val word = el.jsonPrimitive.contentOrNull ?: return@mapNotNull null
        ReasoningMode.levelOf(word).takeIf { it != ReasoningMode.Level.OFF }
      }
      ?.distinct()
      ?.sorted()
      .orEmpty()
    val canTurnOff = o["canTurnOff"]?.jsonPrimitive?.booleanOrNull
    val support = ReasoningMode.Support(canTurnOff, levels)
    return support.takeIf { it.stated }
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
      // Same rule as the rest: a layer that said nothing about the price does not erase it.
      pricing = over.pricing ?: base.pricing,
      priceValidUntil = over.priceValidUntil ?: base.priceValidUntil,
      cacheTtl = over.cacheTtl ?: base.cacheTtl,
      priceAfter = over.priceAfter ?: base.priceAfter,
      priceNote = over.priceNote ?: base.priceNote,
      reasoning = over.reasoning ?: base.reasoning,
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
