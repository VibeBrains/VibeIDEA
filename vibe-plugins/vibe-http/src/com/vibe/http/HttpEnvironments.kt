// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import java.nio.file.Files
import java.nio.file.Path

/**
 * Где лежат окружения и какое из них выбрано.
 *
 * Файлы ищутся рядом с запросом и выше по дереву до корня проекта — так же, как их кладут люди:
 * `http-client.env.json` обычно живёт в корне, но у большого проекта бывает свой в каждой папке.
 */
object HttpEnvironments {
  data class Found(val shared: Path?, val private: Path?)

  /** Ближайшая пара файлов окружений: от папки запроса вверх, но не выше корня проекта. */
  fun locate(from: Path?, projectRoot: Path?): Found {
    var dir = from
    while (dir != null) {
      val shared = dir.resolve(HttpVariables.SHARED_FILE).takeIf { Files.isRegularFile(it) }
      val private = dir.resolve(HttpVariables.PRIVATE_FILE).takeIf { Files.isRegularFile(it) }
      if (shared != null || private != null) return Found(shared, private)
      if (projectRoot != null && dir == projectRoot) break
      dir = dir.parent
    }
    return Found(null, null)
  }

  fun read(found: Found): Map<String, Map<String, String>> {
    fun text(path: Path?): String? = path?.let { runCatching { Files.readString(it) }.getOrNull() }
    return HttpVariables.merge(
      HttpVariables.environments(text(found.shared)),
      HttpVariables.environments(text(found.private)),
    )
  }

  /**
   * Какое окружение показать первым.
   *
   * Сохранённое, если оно ещё существует; иначе первое по алфавиту. Молча оставить выбранным
   * исчезнувшее окружение значит отправлять запросы в никуда с видом, что всё в порядке.
   */
  fun choose(available: Set<String>, stored: String?): String? =
    stored?.takeIf { it in available } ?: available.minOrNull()
}
