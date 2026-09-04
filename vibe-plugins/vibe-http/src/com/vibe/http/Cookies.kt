// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

/**
 * Куки запросов: показ и сохранение между запусками.
 *
 * Хранит и подставляет их `java.net.CookieManager` — переписывать RFC 6265 своими руками незачем.
 * Здесь то, чего у него нет: показать человеку, что у него в банке, и (по явной настройке) пережить
 * перезапуск IDE.
 *
 * Почему это вообще нужно: вход в систему кладёт сессию в куку, и без неё второй запрос отвечает
 * 401 — а причина не видна нигде. Панель без вкладки «Cookies» превращает это в «у вас что-то с
 * авторизацией».
 *
 * Чистая: строки внутрь, строки наружу.
 */
object Cookies {
  /** Файл сохранённых кук — вне проекта: в проекте он попал бы в git вместе с чужой сессией. */
  const val FILE = "vibeHttpCookies.txt"

  data class Cookie(
    val domain: String,
    val path: String,
    val name: String,
    val value: String,
    val secure: Boolean,
    /** Момент истечения в миллисекундах эпохи, или null — кука сессии. */
    val expiresAtEpochMs: Long?,
  )

  /**
   * Значение куки в показе — сокращённо.
   *
   * Токен целиком не нужен, чтобы понять, что он есть; а панель с полным токеном рано или поздно
   * попадёт на скриншот в тикете.
   */
  fun shorten(value: String, keep: Int = 6): String =
    if (value.length <= keep * 2) value else value.take(keep) + "…" + value.takeLast(keep)

  /** Живая ли кука на этот момент: истёкшие показывать не надо, они уже не уедут ни в один запрос. */
  fun isAlive(cookie: Cookie, nowEpochMs: Long): Boolean =
    cookie.expiresAtEpochMs == null || cookie.expiresAtEpochMs > nowEpochMs

  /**
   * Сохраняемая строка одной куки.
   *
   * Свой формат, а не сериализация чужого класса: файл переживает обновление JDK, читается глазами
   * и правится руками, а сломанная строка стоит одной куки, а не всего файла.
   */
  fun encode(cookie: Cookie): String = listOf(
    cookie.domain, cookie.path, cookie.name, cookie.value,
    cookie.secure.toString(), cookie.expiresAtEpochMs?.toString() ?: "",
  ).joinToString("\t") { it.replace("\t", " ").replace("\n", " ") }

  fun decode(line: String): Cookie? {
    val parts = line.split("\t")
    if (parts.size < 6) return null
    val domain = parts[0].takeIf { it.isNotBlank() } ?: return null
    val name = parts[2].takeIf { it.isNotBlank() } ?: return null
    return Cookie(
      domain = domain,
      path = parts[1].ifBlank { "/" },
      name = name,
      value = parts[3],
      secure = parts[4].equals("true", ignoreCase = true),
      expiresAtEpochMs = parts[5].toLongOrNull(),
    )
  }

  /** Что сохраняем: куки сессии не сохраняются никогда — они на то и сессионные. */
  fun persistable(cookies: List<Cookie>, nowEpochMs: Long): List<Cookie> =
    cookies.filter { it.expiresAtEpochMs != null && isAlive(it, nowEpochMs) }

  fun render(text: String): List<Cookie> = text.lineSequence().mapNotNull { decode(it.trim()) }.toList()

  fun renderAll(cookies: List<Cookie>): String = cookies.joinToString("\n") { encode(it) }
}
