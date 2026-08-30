// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `a missing adapter is reported as missing`() {
    val checks = LspDoctor.check(LspDoctor.DEBUG_ADAPTERS) { null }
    assertTrue(checks.all { !it.installed })
    assertFalse(checks.any { it.path != null })
  }
}
