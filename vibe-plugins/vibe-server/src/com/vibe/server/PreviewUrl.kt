// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.server

/**
 * Адрес превью сервиса — то, ради чего в контракте есть `previewPath`.
 *
 * Поле разбиралось с самого начала и не использовалось ничем: запись знала, какую страницу
 * показывать, а показать её было нечем. Встроенный браузер в IDE при этом есть — панель «Дизайн», —
 * и он ничего не знал про стек. Здесь стыкуются две половины: запись стека даёт адрес, панель его
 * открывает.
 *
 * Чистая: запись внутрь, адрес наружу. Ни сети, ни IDE.
 */
object PreviewUrl {
  /** Почему открывать нечего — кодом; фразу собирает интерфейс. */
  enum class Refusal {
    /** У записи нет порта: адрес пришлось бы угадывать, а угаданный ведёт в чужой сервис. */
    NO_PORT,
    /** Задача не страница: миграция и кодоген выполняются и заканчиваются, показывать нечего. */
    TASK_HAS_NO_PAGE,
  }

  sealed interface Address {
    data class Url(val text: String) : Address
    data class Refused(val refusal: Refusal) : Address
  }

  /**
   * Адрес записи.
   *
   * Хост всегда `localhost`: стек поднимается на этой машине, а адрес для телефона — отдельное
   * решение (`LanAddress`), и подменять им локальный адрес нельзя — он ведёт туда же не всегда.
   */
  fun of(entry: ServerEntry): Address {
    if (entry.kind == "task") return Address.Refused(Refusal.TASK_HAS_NO_PAGE)
    val port = entry.port ?: return Address.Refused(Refusal.NO_PORT)
    val path = normalizePath(entry.previewPath ?: entry.readyPath)
    return Address.Url("http://localhost:$port$path")
  }

  /**
   * Путь всегда начинается со слэша и никогда не пуст.
   *
   * `previewPath: "dashboard"` — то, что человек пишет чаще всего; без нормализации получился бы
   * `http://localhost:3000dashboard`, а ошибка выглядела бы как «превью не работает».
   */
  fun normalizePath(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "/") return "/"
    return if (text.startsWith("/")) text else "/$text"
  }
}
