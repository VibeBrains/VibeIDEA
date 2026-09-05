// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.features

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Структура списка возможностей.
 *
 * Существование упомянутых действий проверяет гейт `checkVibeDocs.sh`: описания плагинов лежат в
 * соседних модулях, и на classpath теста их нет — тест «проверял» бы пустое множество и молчал.
 */
class FeatureTourTest {
  private val tour: String by lazy {
    requireNotNull(FeatureTour.read()) { "не найден ресурс ${FeatureTour.RESOURCE}" }
  }

  @Test
  fun `список остаётся списком, а не абзацем`() {
    assertTrue(FeatureTour.sections(tour).size >= 5, "разделов должно быть несколько")
    assertTrue(tour.lines().count { it.startsWith("- ") } >= 15, "возможностей в списке подозрительно мало")
  }

  @Test
  fun `интерфейсные переключатели названы - ради них список и заводили`() {
    assertTrue("Compact Mode" in tour, "плотный интерфейс — то, о чём владелец не знал в родной IDE")
    assertTrue("Настройки" in tour || "Settings" in tour)
  }
}
