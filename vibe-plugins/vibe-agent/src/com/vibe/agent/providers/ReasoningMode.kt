// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reasoning depth, spoken in each provider's own dialect.
 *
 * Every vendor added «think harder» in its own shape: Anthropic takes a token budget, OpenAI takes
 * an effort word, Gemini takes a budget too but under another name. A single slider in the chat is
 * only honest if it is translated per provider — sending `reasoning_effort` to Anthropic does
 * nothing at all, silently, and the user concludes the slider is decorative.
 *
 * Pure, because the mapping is the whole feature and it is exactly the part that ages: providers
 * rename these fields, and a test that pins the shape is what makes the rename visible.
 */
object ReasoningMode {
  enum class Level { OFF, LOW, MEDIUM, HIGH }

  /**
   * Что умеет КОНКРЕТНАЯ модель — объявление из `providers.json` (`"reasoning"`).
   *
   * Ползунок один на всё приложение, а модели разные: GLM даёт low/high и не даёт medium,
   * рассуждающие модели OpenAI не выключаются вовсе. Отправить такой модели `medium` значит либо
   * получить 400, либо — хуже — молчаливое игнорирование: человек двигает ползунок и не видит
   * разницы, потому что её нет.
   *
   * Пусто (поля нет в файле) означает «не сказано», и тогда уровень идёт как есть: придумывать за
   * вендора список уровней мы не вправе, а отказ отправлять что-либо сломал бы всех, кто ничего
   * не объявлял.
   */
  data class Support(
    /** Можно ли выключить рассуждение совсем; null — не сказано. */
    val canTurnOff: Boolean? = null,
    /** Уровни, которые модель принимает, в порядке возрастания; пусто — не сказано. */
    val levels: List<Level> = emptyList(),
  ) {
    val stated: Boolean get() = canTurnOff != null || levels.isNotEmpty()
  }

  fun levelOf(name: String?): Level = when (name?.trim()?.lowercase()) {
    "low", "низкий" -> Level.LOW
    "medium", "средний" -> Level.MEDIUM
    "high", "высокий", "max" -> Level.HIGH
    else -> Level.OFF
  }

  /**
   * Уровень, приведённый к тому, что модель действительно принимает.
   *
   * Правила ровно два, и оба про честность, а не про удобство:
   * - просили выключить, а модель не выключается → берём САМЫЙ НИЗКИЙ объявленный уровень
   *   (ближе к просьбе, чем любой другой, и это единственное, что модель умеет);
   * - просили уровень, которого у модели нет → берём ближайший СНИЗУ, а если ниже ничего нет —
   *   самый низкий объявленный. Вниз, а не вверх: «подумай поменьше» дешевле ошибиться, чем
   *   «подумай побольше», и человек, двигавший ползунок влево, просил экономии.
   */
  fun clamp(level: Level, support: Support?): Level {
    if (support == null || !support.stated) return level
    val offered = support.levels.sorted()
    if (level == Level.OFF) {
      if (support.canTurnOff != false) return Level.OFF
      return offered.firstOrNull() ?: Level.LOW
    }
    if (offered.isEmpty() || level in offered) return level
    return offered.lastOrNull { it < level } ?: offered.first()
  }

  /** Token budgets for providers that take one. Round numbers: this is a dial, not a measurement. */
  fun budgetTokens(level: Level): Int? = when (level) {
    Level.OFF -> null
    Level.LOW -> 2_000
    Level.MEDIUM -> 8_000
    Level.HIGH -> 24_000
  }

  fun effortWord(level: Level): String? = when (level) {
    Level.OFF -> null
    Level.LOW -> "low"
    Level.MEDIUM -> "medium"
    Level.HIGH -> "high"
  }

  /**
   * The fields to add to the request body for [protocol], or an empty object when this provider
   * has nothing to say about thinking. An empty object rather than null: the caller merges, and a
   * merge with nothing is simpler to read than a null check at every call site.
   */
  fun bodyFields(protocol: String, level: Level, maxOutputTokens: Int?): JsonObject {
    if (level == Level.OFF) return JsonObject(emptyMap())
    return when (protocol.lowercase()) {
      "anthropic" -> buildJsonObject {
        // The budget must leave room for the answer itself; a budget at or above max_tokens is
        // rejected by the API, and «модель молчит» is how that shows up.
        val budget = budgetTokens(level)!!.coerceAtMost(((maxOutputTokens ?: 0) - MIN_ANSWER_TOKENS).coerceAtLeast(1_024))
        put("thinking", buildJsonObject {
          put("type", "enabled")
          put("budget_tokens", budget)
        })
      }
      "gemini" -> buildJsonObject {
        put("generationConfig", buildJsonObject {
          put("thinkingConfig", buildJsonObject { put("thinkingBudget", budgetTokens(level)!!) })
        })
      }
      else -> buildJsonObject { put("reasoning_effort", effortWord(level)!!) }
    }
  }

  /** Room the answer needs after the thinking budget is taken out of max_tokens. */
  const val MIN_ANSWER_TOKENS = 2_048
}
