// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

/**
 * История ответов: что этот запрос отвечал раньше.
 *
 * В Postman на неё смотрят по одной причине — «вчера работало». Ответ сам по себе говорит, что
 * происходит сейчас; сравнить его с прошлым нечем, и человек лезет в логи сервера, хотя различие
 * было у него на экране минуту назад.
 *
 * Храним в памяти процесса: история переживает переключение между запросами и файлами, но не
 * перезапуск IDE. Писать чужие ответы на диск без спроса нельзя — в них токены и персональные
 * данные; это осознанная граница, а не недоделка.
 *
 * Чистая: список внутрь, список наружу.
 */
object HttpHistory {
  /** Сколько ответов держим на запрос. Больше десяти никто не сравнивает, а память не резиновая. */
  const val PER_REQUEST = 10

  data class Entry(
    val requestTitle: String,
    val method: String,
    val target: String,
    val status: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val body: String,
    /** Момент времени приходит снаружи: иначе тест зависит от часов. */
    val atEpochMs: Long,
  )

  /**
   * Кладёт ответ и возвращает новый список.
   *
   * Ключ — заголовок вместе с методом и адресом: два запроса с именем «Список» в разных файлах это
   * разные запросы, и общая история показывала бы человеку чужие ответы.
   */
  fun add(history: List<Entry>, entry: Entry, perRequest: Int = PER_REQUEST): List<Entry> {
    val key = keyOf(entry)
    val others = history.filter { keyOf(it) != key }
    val mine = (listOf(entry) + history.filter { keyOf(it) == key }).take(perRequest)
    return mine + others
  }

  fun keyOf(entry: Entry): String = entry.requestTitle + " " + entry.method + " " + entry.target

  /** История одного запроса, новые первыми. */
  fun of(history: List<Entry>, title: String, method: String, target: String): List<Entry> {
    val key = title + " " + method + " " + target
    return history.filter { keyOf(it) == key }
  }

  /**
   * Что изменилось против прошлого раза — одной строкой, или null, если сравнивать не с чем.
   *
   * Сравниваем статус и тело, а не время: время скачет само по себе, и сообщение о нём на каждом
   * прогоне приучило бы не читать эту строку вовсе.
   */
  fun changeAgainstPrevious(entries: List<Entry>, labels: Labels): String? {
    if (entries.size < 2) return null
    val now = entries[0]
    val before = entries[1]
    return when {
      now.status != before.status -> labels.statusChanged(before.status, now.status)
      now.body != before.body -> labels.bodyChanged(entries.size - 1)
      else -> null
    }
  }

  interface Labels {
    fun statusChanged(before: Int, now: Int): String
    fun bodyChanged(comparedWith: Int): String
  }
}
