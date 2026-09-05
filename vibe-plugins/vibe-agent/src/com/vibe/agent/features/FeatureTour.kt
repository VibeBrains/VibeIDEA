// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.features

/**
 * Список возможностей, который видно из самой IDE.
 *
 * Решение владельца 05.09.2026: «даже в родном я не знал, что это переключается — а у нас пусть все
 * знают». Возможность, о которой не знают, не существует; каталог `functional.md` лежит в
 * репозитории продукта и до пользователя не доезжает.
 *
 * Документ — ресурс, а не строка в коде: он длинный, его правят чаще кода, и он обязан читаться
 * глазами в диффе. Здесь — только разбор: из текста достаются упомянутые идентификаторы действий,
 * чтобы тест мог проверить, что список не зовёт к кнопке, которой давно нет. Список, отправляющий
 * человека к несуществующему действию, хуже отсутствующего: он выглядит как поломка IDE.
 *
 * Чистая: текст внутрь, идентификаторы наружу.
 */
object FeatureTour {
  const val RESOURCE = "/features/tour.md"

  /** Идентификаторы действий, упомянутые в тексте (`Vibe.Doctor` и подобные). */
  fun actionIds(markdown: String): Set<String> =
    Regex("`(Vibe\\.[A-Za-z0-9_]+)`").findAll(markdown).map { it.groupValues[1] }.toSet()

  /** Заголовки разделов — по ним видно, что список не выродился в один абзац. */
  fun sections(markdown: String): List<String> =
    markdown.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }

  fun read(): String? =
    FeatureTour::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.readText()
}
