// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * После смены ветки трогаем только то, что работает.
 *
 * Перезапуск сервера на большом проекте стоит десятки секунд без подсказок и переходов. Сервер,
 * который сейчас не запущен, ничего не помнит и соберёт модель сам при первом открытии файла —
 * перезапускать там нечего, и потраченное на это время было бы отнято ни за что.
 */
class BranchSwitchRefreshTest {
  @Test
  fun `перезапускаются только запущенные`() {
    val targets = BranchSwitchRefresh.toRestart(setOf("vibeVtsls", "vibePhp"))
    assertEquals(listOf("vibeVtsls", "vibePhp"), targets)
  }

  @Test
  fun `ничего не запущено — ничего не трогаем`() {
    assertTrue(BranchSwitchRefresh.toRestart(emptySet()).isEmpty())
  }

  @Test
  fun `чужие серверы не наше дело`() {
    val targets = BranchSwitchRefresh.toRestart(setOf("someoneElsesServer", "vibeCss"))
    assertEquals(listOf("vibeCss"), targets, "в список попал сервер, которым мы не управляем")
  }

  /**
   * Список идентификаторов обязан совпадать с регистрацией у LSP4IJ: опечатка здесь не падает
   * ничем, она просто молча исключает сервер из обновления.
   */
  @Test
  fun `список совпадает с регистрацией серверов`() {
    // Ресурс берётся ИЗ CLASSPATH, а не с диска: путь на диске в песочнице сборки не существует,
    // и замер, читающий файл, там молча пропускался бы — проверено подложенной опечаткой, он её
    // не заметил. Гейт, который выглядит строгим и таковым не является, хуже отсутствующего.
    val xml = checkNotNull(javaClass.getResourceAsStream("/META-INF/vibe-lsp4ij-integration.xml")) {
      "регистрация серверов не найдена в classpath — замер бесполезен, чинить его, а не отключать"
    }.use { it.readBytes().decodeToString() }
    val registered = Regex("""<server id="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
    assertEquals(registered, BranchSwitchRefresh.SERVER_IDS.toSet(),
                 "список обновления разошёлся с регистрацией серверов")
  }
}
