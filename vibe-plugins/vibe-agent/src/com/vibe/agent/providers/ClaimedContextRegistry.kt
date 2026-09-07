// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * Что провайдеры сказали о размере окна — в памяти процесса, до перезапуска.
 *
 * На диск не пишем намеренно: это не настройка и не факт о договоре, а слепок чужого ответа,
 * который завтра изменится (у MiniMax он уже менялся, и не в ту сторону). Сохранённый на диск, он
 * пережил бы исправление на стороне вендора и продолжил бы обвинять его в том, что уже починено.
 *
 * Заполняется тем же обходом каталога, который и так делает IDE, — отдельного похода в сеть ради
 * этой проверки нет.
 */
object ClaimedContextRegistry {
  private val claims = java.util.concurrent.ConcurrentHashMap<String, Map<String, Long>>()

  fun record(providerId: String, windows: Map<String, Long>) {
    if (windows.isEmpty()) claims.remove(providerId) else claims[providerId] = windows
  }

  fun all(): Map<String, Map<String, Long>> = claims.toMap()

  /** Спрашивали ли мы вообще: пусто — значит «не проверялось», а не «расхождений нет». */
  fun isEmpty(): Boolean = claims.isEmpty()
}
