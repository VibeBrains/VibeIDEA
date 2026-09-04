// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

/**
 * Прогон всех запросов файла подряд — «дымовой» проход по API.
 *
 * Зачем: файл `.http` рядом с кодом естественно превращается в набор проверок «живо ли всё после
 * правки». По одному их гоняют ровно до третьего запроса, дальше перестают. Один прогон и сводка
 * «сколько ответило, что упало» — это то, ради чего люди пишут отдельные скрипты на curl.
 *
 * Чистая: складывает исходы в сводку. Отправку делает панель, здесь только счёт и правила.
 */
object HttpRunAll {
  /** Исход одного запроса в прогоне. */
  sealed interface Outcome {
    data class Answered(val title: String, val status: Int, val durationMs: Long) : Outcome
    /** Запрос не ушёл: неподставленная переменная, битый адрес, отсутствующий файл тела. */
    data class Refused(val title: String, val detail: String) : Outcome
    data class Failed(val title: String, val message: String) : Outcome
  }

  data class Summary(
    val total: Int,
    val ok: Int,
    val failed: List<String>,
    val totalMs: Long,
  ) {
    val allOk: Boolean get() = failed.isEmpty() && total > 0
  }

  /**
   * Сводка прогона.
   *
   * Успехом считается только ответ класса 2xx: 404 и 500 — это ответы, но не «работает», а
   * зелёная сводка при пяти пятисотках хуже, чем её отсутствие. Перенаправления тоже не успех:
   * запрос, ушедший не туда, куда написано, стоит увидеть.
   */
  fun summarize(outcomes: List<Outcome>): Summary {
    val failed = ArrayList<String>()
    var ok = 0
    var total = 0L
    for (outcome in outcomes) {
      when (outcome) {
        is Outcome.Answered -> {
          total += outcome.durationMs
          if (HttpExchange.outcome(outcome.status) == HttpExchange.Outcome.SUCCESS) ok++
          else failed.add(outcome.title + " — " + outcome.status)
        }
        is Outcome.Refused -> failed.add(outcome.title + " — " + outcome.detail)
        is Outcome.Failed -> failed.add(outcome.title + " — " + outcome.message)
      }
    }
    return Summary(outcomes.size, ok, failed, total)
  }

  /**
   * Стоит ли вообще запускать прогон и что предупредить.
   *
   * Файл с изменяющими запросами прогонять целиком опасно: `POST /users` пять раз это пять
   * пользователей. Предупреждаем, но не запрещаем — файл писал человек, и он знает, что там.
   */
  fun changingRequests(requests: List<HttpRequestFile.Request>): List<HttpRequestFile.Request> =
    requests.filter { it.method !in SAFE_METHODS }

  /** Методы, которые по спецификации ничего не меняют. */
  private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
}
