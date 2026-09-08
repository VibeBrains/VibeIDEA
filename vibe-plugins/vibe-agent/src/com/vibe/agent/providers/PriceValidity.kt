// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.time.LocalDate

/**
 * Срок годности цены, которую человек записал сам.
 *
 * Своего прайс-листа у нас нет и не будет — цена приходит из `.vibe/providers.json`, потому что это
 * факт о договоре пользователя, а таблица в нашем коде врёт в день, когда вендор поменял строку.
 * Ровно поэтому цена имеет свойство протухать молча: она остаётся в файле, а весь учёт расхода
 * продолжает считаться по ней и выглядит достоверным.
 *
 * Повод разобран 07.09.2026 на живом календаре Z.AI: промо GLM-5.3-Flash ($0.075/$0.25 против
 * обычных $0.15/$0.50) кончается 09.09.2026 в 24:00 UTC+8 — вдвое, за один день, по расписанию,
 * известному заранее. При этом у соседней кампании того же вендора даты окончания не названо вовсе,
 * то есть надёжного календаря промо-условий нет в принципе, и единственное место, где срок можно
 * записать, — файл пользователя.
 *
 * Разбор и предупреждение устроены как у [ModelSunset] намеренно: это одна и та же задача — «дата в
 * файле, о которой надо напомнить до того, как она наступит», и второй способ её решать разошёлся
 * бы с первым.
 *
 * Чистая и без часов: сегодняшний день — аргумент, иначе правило нельзя проверить на послезавтра.
 */
object PriceValidity {
  /** За сколько дней начинать предупреждать: неделя — успеть свериться с вендором, не примелькавшись. */
  const val WARN_DAYS = 7L

  enum class State {
    /** Срок не указан или далеко. */
    NONE,
    /** Срок в пределах [WARN_DAYS]. */
    SOON,
    /** Срок прошёл: цена больше не про сегодняшний счёт. */
    EXPIRED,
  }

  fun state(date: String?, today: LocalDate): State {
    val day = ModelSunset.parse(date) ?: return State.NONE
    return when {
      // Сам день ещё считается действующим: цена меняется в конце суток, и объявлять её
      // просроченной с утра значит пугать человека тем, чего пока не случилось.
      day.isBefore(today) -> State.EXPIRED
      day.toEpochDay() - today.toEpochDay() <= WARN_DAYS -> State.SOON
      else -> State.NONE
    }
  }

  fun daysLeft(date: String?, today: LocalDate): Long? = ModelSunset.daysLeft(date, today)

  /**
   * Цена просрочена — но НЕ выбрасывается.
   *
   * Считать по устаревшей цене честнее, чем не считать вовсе: порядок величины она даёт верный, а
   * молчащая колонка расхода не учит ничему. Ответственность за расхождение снимает пометка, а не
   * отказ считать.
   */
  fun isExpired(model: ModelEntry, today: LocalDate): Boolean = state(model.priceValidUntil, today) == State.EXPIRED

  data class Notice(
    val providerId: String,
    val modelId: String,
    val state: State,
    val daysLeft: Long,
    /** Цена после срока, если человек её записал: решение принимают по ней, а не по факту срока. */
    val after: ModelPricing? = null,
  )

  /**
   * Во сколько раз дорожает вход, когда срок кончится, — по объявленной цене «после».
   *
   * Множитель, а не разность: подорожание вдвое и вдесятеро требуют разных решений, а разность
   * в долларах за миллион токенов ни о чём не говорит человеку, не помнящему исходную цену.
   * Считается по входу: именно он растёт в агентном цикле, где контекст перечитывается.
   * Null — цены «после» нет либо сравнивать не с чем (одна из двух цен не названа).
   */
  fun inputFactor(now: ModelPricing?, after: ModelPricing?): Double? {
    val from = now?.input ?: return null
    val to = after?.input ?: return null
    if (from <= 0.0 || to <= 0.0) return null
    return to / from
  }

  /** Модели, чью цену пора перепроверить, — ближайшие первыми. */
  fun notices(providers: List<ProviderEntry>, today: LocalDate): List<Notice> =
    providers.flatMap { provider ->
      provider.models.mapNotNull { model ->
        // Срок без цены — описка в файле, а не находка: предупреждать не о чем.
        if (model.pricing?.stated != true) return@mapNotNull null
        val state = state(model.priceValidUntil, today)
        if (state == State.NONE) return@mapNotNull null
        Notice(provider.id, model.id, state, daysLeft(model.priceValidUntil, today) ?: 0, model.priceAfter)
      }
    }.sortedBy { it.daysLeft }
}
