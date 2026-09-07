// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

/**
 * Почему ход прекратился — и что с этим делать дальше.
 *
 * До сих пор все остановки выглядели одинаково: ход кончился, лента замолчала. Между тем решения
 * противоположные. Агент, ходящий по кругу, прекращён нами намеренно — возобновлять его значит
 * запускать тот же круг заново, за те же деньги. Агент, у которого отобрали инструмент или увели
 * модель, не сделал ничего плохого: его работу надо продолжить, когда провайдер вернётся.
 *
 * Повод разобран 07.09.2026 (гейтированный выпуск моделей с ограничением доступа к инструментам):
 * длинный прогон обязан переживать внезапную недоступность, а не начинаться сначала.
 *
 * Чистая: причина внутрь, решение наружу.
 */
object StopCause {
  enum class Cause {
    /** Человек нажал «Стоп». Возобновление — его же решение, не наше. */
    USER,
    /** Предохранитель: повтор, петля, потолок расхода. Возобновлять нельзя — вернётся тот же круг. */
    BREAKER,
    /** Провайдер, модель или инструмент стали недоступны. Работа не закончена, её можно продолжить. */
    UNAVAILABLE,
    /** Ход завершился сам. */
    DONE,
    /** Причина неизвестна — считаем незавершённым, но не обещаем возобновления. */
    UNKNOWN,
  }

  /**
   * Можно ли предлагать возобновление.
   *
   * Только внешняя недоступность: во всех остальных случаях повтор либо не нужен, либо вреден.
   */
  fun resumable(cause: Cause): Boolean = cause == Cause.UNAVAILABLE

  /** Незакрытая работа: план стоит сохранить и напомнить о нём при следующем открытии проекта. */
  fun unfinished(cause: Cause): Boolean = cause != Cause.DONE

  /**
   * Причина по тому, что известно о ходе.
   *
   * Порядок проверок — это порядок достоверности: наше собственное решение мы знаем точно, отказ
   * провайдера читаем из его же ответа, а всё, что осталось, — догадка, и она называется догадкой.
   */
  fun of(stoppedByUser: Boolean, breakerTripped: Boolean, failureMessage: String?, finishedCleanly: Boolean): Cause = when {
    stoppedByUser -> Cause.USER
    breakerTripped -> Cause.BREAKER
    failureMessage != null && looksUnavailable(failureMessage) -> Cause.UNAVAILABLE
    finishedCleanly -> Cause.DONE
    failureMessage != null -> Cause.UNKNOWN
    else -> Cause.UNKNOWN
  }

  /**
   * Признаки временной недоступности в ответе провайдера.
   *
   * Коды, а не слова: формулировки вендоры меняют, а 429/5xx и «connection refused» — нет.
   * Отказ авторизации сюда НЕ входит: ключ сам не починится, и предлагать возобновление означало бы
   * обещать то, чего не будет.
   */
  fun looksUnavailable(message: String): Boolean {
    val text = message.lowercase()
    if (Regex("\\b(401|403)\\b").containsMatchIn(text)) return false
    return Regex("\\b(429|500|502|503|504)\\b").containsMatchIn(text) ||
           "timeout" in text || "timed out" in text ||
           "connection refused" in text || "connection reset" in text ||
           "unavailable" in text || "overloaded" in text
  }
}
