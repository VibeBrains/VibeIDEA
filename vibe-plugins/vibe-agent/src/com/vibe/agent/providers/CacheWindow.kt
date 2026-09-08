// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * Сколько ещё живёт промпт-кэш и во что обойдётся пауза.
 *
 * У нас была вся механика кэша (маркер, TTL, бета-заголовок, цена чтения) и не было **времени** —
 * а именно оно решает. Кэш протухает по часам, а не по действиям: ушёл за кофе на шесть минут —
 * и следующий ход перечитывает весь контекст заново по полной цене входа. Снаружи это выглядит
 * как «лимиты кончились сами».
 *
 * Числа, на которых держится расчёт (цены Anthropic, проверены по прайсу 08.09.2026):
 * запись в пятиминутный кэш стоит 1.25 от входа, в часовой — 2.0; чтение — 0.1. Отсюда и
 * окупаемость: пятиминутная запись отбивается со ВТОРОГО запроса, часовая — с ТРЕТЬЕГО.
 *
 * Чистый и без часов: «сейчас» приходит аргументом, иначе правило нельзя проверить на послезавтра.
 */
object CacheWindow {
  enum class State {
    /** Кэш заведомо жив: следующий ход прочитает контекст по дешёвой ставке. */
    WARM,

    /** Осталось меньше [WARN_LEFT_MS]: сообщение сейчас ещё попадёт в кэш, через минуту — нет. */
    EXPIRING,

    /** Кэш истёк: следующий ход оплатит весь контекст как новый. */
    COLD,

    /** Кэша нет вовсе — первый ход разговора или модель без кэширования. */
    NONE,
  }

  /** Пять минут и час — единственные сроки, которые вендор даёт. */
  const val TTL_5M_MS = 5 * 60 * 1000L
  const val TTL_1H_MS = 60 * 60 * 1000L

  /** За минуту до конца предупреждаем: меньше уже не успеть дописать сообщение. */
  const val WARN_LEFT_MS = 60 * 1000L

  fun ttlMs(raw: String?): Long = if (PromptCache.ttlOf(raw) == PromptCache.TTL_1H) TTL_1H_MS else TTL_5M_MS

  /**
   * Сколько миллисекунд кэшу осталось жить.
   *
   * Отсчёт идёт от НАЧАЛА прошлого хода, а не от его конца: запись в кэш происходит, когда запрос
   * уходит, и таймер тикает, пока модель думает. Ход, который отвечал четыре минуты, оставляет от
   * пятиминутного кэша одну — об этом и предупреждение.
   */
  fun leftMs(lastTurnStartedAtMs: Long, ttl: String?, nowMs: Long): Long {
    if (lastTurnStartedAtMs <= 0) return 0
    return (lastTurnStartedAtMs + ttlMs(ttl) - nowMs).coerceAtLeast(0)
  }

  fun state(lastTurnStartedAtMs: Long, ttl: String?, nowMs: Long): State {
    if (lastTurnStartedAtMs <= 0) return State.NONE
    val left = leftMs(lastTurnStartedAtMs, ttl, nowMs)
    return when {
      left <= 0 -> State.COLD
      left <= WARN_LEFT_MS -> State.EXPIRING
      else -> State.WARM
    }
  }

  /**
   * С какого по счёту запроса окупается запись кэша.
   *
   * Считается по ЦЕНАМ модели, если человек их назвал, и только иначе — по ставкам вендора. Своей
   * таблицы цен у нас нет (решение №40), а «окупается с третьего» без цен — это вера, а не счёт.
   *
   * Формула честная: запись стоит `(write − input)` сверх обычного входа, каждое попадание
   * экономит `(input − read)`. Окупаемость — первый запрос, на котором накопленная экономия
   * перекрывает надбавку, плюс сам запрос записи.
   */
  fun paysOffFromRequest(pricing: ModelPricing?, ttl: String?): Int {
    val input = pricing?.input?.takeIf { it > 0 } ?: return defaultPayOff(ttl)
    val write = pricing.cacheWrite.takeIf { it > 0 } ?: (input * defaultWriteFactor(ttl))
    val read = pricing.cacheRead.takeIf { it > 0 } ?: (input * DEFAULT_READ_FACTOR)
    val overhead = (write - input).coerceAtLeast(0.0)
    val savedPerHit = (input - read).coerceAtLeast(0.0)
    if (savedPerHit <= 0) return Int.MAX_VALUE
    val hits = Math.ceil(overhead / savedPerHit).toInt().coerceAtLeast(1)
    return hits + 1
  }

  private fun defaultPayOff(ttl: String?): Int = if (PromptCache.ttlOf(ttl) == PromptCache.TTL_1H) 3 else 2

  private fun defaultWriteFactor(ttl: String?): Double =
    if (PromptCache.ttlOf(ttl) == PromptCache.TTL_1H) 2.0 else 1.25

  private const val DEFAULT_READ_FACTOR = 0.1
}
