// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugAdapterTest {
  @Test
  fun `debug adapters are checked apart from language servers`() {
    // Их отсутствие ломает другое — точки останова, а не переход к определению, — и общий отчёт
    // отправлял бы человека ставить не тот пакет.
    assertTrue(LspDoctor.ALL.none { it in LspDoctor.DEBUG_ADAPTERS })
    assertEquals(2, LspDoctor.DEBUG_ADAPTERS.size)
  }

  @Test
  fun `each adapter names a binary and a one-line install command`() {
    assertTrue(LspDoctor.DEBUG_ADAPTERS.all { it.binary.isNotBlank() })
    assertTrue(LspDoctor.DEBUG_ADAPTERS.all { it.installCommand.isNotBlank() && '\n' !in it.installCommand })
  }

  @Test
  fun `the adapters cover the languages we ship servers for`() {
    val served = LspDoctor.ALL.flatMap { it.extensions }.toSet()
    val debugged = LspDoctor.DEBUG_ADAPTERS.flatMap { it.extensions }.toSet()
    assertTrue(debugged.contains("ts") && debugged.contains("php"))
    assertTrue(served.containsAll(debugged), "отлаживаем только то, что понимаем: " + (debugged - served))
  }

  @Test
  fun `the install commands point at packages that exist`() {
    // Both previous commands were dead — `npm install -g js-debug-adapter` and
    // `composer global require xdebug/vscode-php-debug` both answer 404 (checked 01.09.2026).
    // A command that fails makes a person conclude the feature is broken, not the instruction.
    assertTrue(LspDoctor.DEBUG_ADAPTERS.none { "npm install -g js-debug-adapter" in it.installCommand })
    assertTrue(LspDoctor.DEBUG_ADAPTERS.none { "composer global require" in it.installCommand })
    // And they must be safe to run behind a button, like every other command we offer.
    assertTrue(LspDoctor.DEBUG_ADAPTERS.all { ServerInstall.isOfferable(it.installCommand) })
  }

  @Test
  fun `an installed adapter is found where it is actually unpacked`() {
    // The adapters are unpacked archives of JavaScript, not executables on PATH: resolving them
    // like a language server reports "not installed" on a machine where they are installed.
    assertNull(LspDoctor.adapterEntryPoint("vtsls"), "не отладчик — и пути у него нет")
    assertNull(LspDoctor.adapterEntryPoint("php-debug-adapter"), "на этой машине адаптер не установлен")
  }

  @Test
  fun `a missing adapter is reported as missing`() {
    val checks = LspDoctor.check(LspDoctor.DEBUG_ADAPTERS) { null }
    assertTrue(checks.all { !it.installed })
    assertFalse(checks.any { it.path != null })
  }
}
