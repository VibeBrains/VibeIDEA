// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A server file runs the same way at launch and in the settings page "Check".
 *
 * A check that runs the file differently from the launch tests something else: a bundled `.js` entry started directly
 * fails with "permission denied", and a working server gets reported as broken.
 */
class ServerRunRuleTest {
  @Test
  fun `исполняемый файл запускается сам`() {
    assertEquals(listOf("/usr/local/bin/vtsls", "--version"), ServerBinaries.runCommand("/usr/local/bin/vtsls", "--version"))
  }

  @Test
  fun `скрипт js запускается нодой`() {
    val command = ServerBinaries.runCommand("/opt/servers/vtsls.js", "--version")
    assertTrue(command.first().substringAfterLast('/').startsWith("node"), "скрипт запущен не нодой: $command")
    assertEquals(listOf("/opt/servers/vtsls.js", "--version"), command.drop(1))
  }

  @Test
  fun `phar запускается интерпретатором PHP`() {
    val command = ServerBinaries.runCommand("/opt/servers/phpactor.phar", "--version")
    assertTrue(command.first().substringAfterLast('/').startsWith("php"), "phar запущен не PHP: $command")
    assertEquals(listOf("/opt/servers/phpactor.phar", "--version"), command.drop(1))
  }
}
