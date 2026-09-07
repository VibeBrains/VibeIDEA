// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Размер окна, который провайдер сообщает о своей модели, — и почему ему нельзя верить молча.
 *
 * Разобрано 07.09.2026 на MiniMax-M3: совместимый `/anthropic`-слой отдаёт `context_window: 200000`
 * («likely inheriting Claude Sonnet's value»), сторонний каталог models.dev — 512 000, клиент
 * показывает третье, а фактическое окно — 1 000 000. Три источника, три числа, ни одно не совпало
 * с правдой.
 *
 * Мы на эту ловушку не ловимся по построению: бюджет сессии берётся из настройки, а окно модели —
 * из `.vibe/providers.json`, то есть от человека. Но это же и означает, что расхождение между его
 * записью и словами провайдера **никто не замечает**: если провайдер занижает окно, человек
 * недоиспользует модель, за которую платит; если завышает — упирается в отказ на середине разговора.
 *
 * Поэтому расхождение не исправляется автоматически (мы не знаем, кто прав), а называется вслух.
 *
 * Чистая: JSON каталога внутрь, числа наружу.
 */
object ClaimedContext {
  /**
   * Как это поле называют вендоры. Порядок — по частоте, первое совпадение выигрывает.
   *
   * Список именно такой длины потому, что общего имени у поля нет: OpenRouter пишет
   * `context_length`, OpenAI-совместимые шлюзы — `context_window`, Gemini — `inputTokenLimit`.
   */
  private val FIELDS = listOf(
    "context_length", "context_window", "max_context_length", "max_input_tokens", "inputTokenLimit",
  )

  /** Расхождение между конфигом и словами провайдера. */
  enum class Verdict {
    /** Сравнивать не с чем: провайдер молчит или в конфиге окно не задано. */
    UNKNOWN,
    /** Совпало (с точностью до допуска). */
    AGREE,
    /** В конфиге больше: провайдер занижает — модель используется не на полную. */
    CONFIG_LARGER,
    /** В конфиге меньше: разговор упрётся в отказ раньше, чем ждёт человек. */
    CONFIG_SMALLER,
  }

  /** Допуск: вендоры округляют по-разному (131 072 против 128 000), и это не расхождение. */
  const val TOLERANCE = 0.05

  /** Окно модели, как его называет ответ каталога, или null. */
  fun of(model: JsonObject): Long? {
    for (field in FIELDS) {
      model[field]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { return it }
    }
    // OpenRouter прячет настоящее окно ещё и внутри `top_provider`: верхнее поле там бывает общим
    // по всем маршрутам, а конкретный провайдер отдаёт своё.
    (model["top_provider"] as? JsonObject)?.let { nested ->
      for (field in FIELDS) {
        nested[field]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { return it }
      }
    }
    return null
  }

  /** Идентификатор → заявленное окно, по ответу эндпоинта моделей. */
  fun parse(root: JsonObject): Map<String, Long> {
    val array = (root["data"] ?: root["models"]) as? kotlinx.serialization.json.JsonArray ?: return emptyMap()
    val result = LinkedHashMap<String, Long>()
    for (element in array) {
      val model = element as? JsonObject ?: continue
      val id = model["id"]?.jsonPrimitive?.contentOrNull
        ?: model["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
        ?: continue
      of(model)?.let { result[id] = it }
    }
    return result
  }

  fun compare(configured: Int?, claimed: Long?): Verdict {
    if (configured == null || configured <= 0 || claimed == null || claimed <= 0) return Verdict.UNKNOWN
    val difference = kotlin.math.abs(configured - claimed).toDouble() / maxOf(configured.toLong(), claimed)
    return when {
      difference <= TOLERANCE -> Verdict.AGREE
      configured > claimed -> Verdict.CONFIG_LARGER
      else -> Verdict.CONFIG_SMALLER
    }
  }

  data class Notice(val providerId: String, val modelId: String, val configured: Int, val claimed: Long, val verdict: Verdict)

  /**
   * Расхождения по всем моделям, у которых есть обе цифры.
   *
   * Занижение провайдера ставится первым: оно дороже — человек платит за окно, которым не
   * пользуется, и узнаёт об этом только по чужому issue.
   */
  fun notices(providers: List<ProviderEntry>, claims: Map<String, Map<String, Long>>): List<Notice> =
    providers.flatMap { provider ->
      val perProvider = claims[provider.id].orEmpty()
      provider.models.mapNotNull { model ->
        val claimed = perProvider[model.id] ?: return@mapNotNull null
        val verdict = compare(model.contextWindow, claimed)
        if (verdict == Verdict.AGREE || verdict == Verdict.UNKNOWN) return@mapNotNull null
        Notice(provider.id, model.id, model.contextWindow ?: 0, claimed, verdict)
      }
    }.sortedBy { if (it.verdict == Verdict.CONFIG_LARGER) 0 else 1 }
}
