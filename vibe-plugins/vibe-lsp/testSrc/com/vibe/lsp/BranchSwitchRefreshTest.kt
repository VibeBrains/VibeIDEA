// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After a branch switch only running servers are touched.
 *
 * Restarting a server on a large project costs tens of seconds without completion or navigation. A server that is not
 * running remembers nothing and builds its model on the first file open, so restarting it would waste that time.
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
   * The id list must match the LSP4IJ registration: a typo here fails nothing, it silently drops the server from the
   * refresh.
   */
  @Test
  fun `список совпадает с регистрацией серверов`() {
    // The resource is read FROM THE CLASSPATH, not from disk: the on-disk path does not exist in the build sandbox, and
    // a check reading the file there would be skipped silently. A check that looks strict and is not is worse than none.
    val xml = checkNotNull(javaClass.getResourceAsStream("/META-INF/vibe-lsp4ij-integration.xml")) {
      "регистрация серверов не найдена в classpath — замер бесполезен, чинить его, а не отключать"
    }.use { it.readBytes().decodeToString() }
    val registered = Regex("""<server id="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
    assertEquals(registered, BranchSwitchRefresh.SERVER_IDS.toSet(),
                 "список обновления разошёлся с регистрацией серверов")
  }
}
