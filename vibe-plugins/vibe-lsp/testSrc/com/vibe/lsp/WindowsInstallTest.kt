// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsInstallTest {
  @Test
  fun `unix one-liners are not offered on Windows`() {
    // `mkdir -p`, `unzip` и `~/` там не существуют. Команда, которая гарантированно падает, хуже
    // отсутствующей: человек делает вывод, что сломана возможность, а не инструкция.
    for (spec in LspDoctor.DEBUG_ADAPTERS + LspDoctor.PHPACTOR) {
      val windows = LspDoctor.installCommandFor(spec, windows = true)
      assertFalse(windows.contains("mkdir -p"), "${spec.id}: $windows")
      assertFalse(windows.contains("unzip "), "${spec.id}: $windows")
      assertFalse(windows.contains("~/"), "${spec.id}: $windows")
    }
  }

  @Test
  fun `npm commands work as they are, and stay the same`() {
    // `npm install -g` одинаково работает везде: подменять его ради симметрии значило бы завести
    // вторую строку, которая разойдётся с первой.
    assertEquals(LspDoctor.VTSLS.installCommand, LspDoctor.installCommandFor(LspDoctor.VTSLS, windows = true))
    assertEquals(LspDoctor.CSS.installCommand, LspDoctor.installCommandFor(LspDoctor.CSS, windows = true))
  }

  @Test
  fun `every offered command is still safe to run behind a button`() {
    for (spec in LspDoctor.ALL + LspDoctor.DEBUG_ADAPTERS) {
      assertTrue(ServerInstall.isOfferable(LspDoctor.installCommandFor(spec, windows = true)), spec.id)
      assertTrue(ServerInstall.isOfferable(LspDoctor.installCommandFor(spec, windows = false)), spec.id)
    }
  }

  @Test
  fun `on unix nothing changed`() {
    for (spec in LspDoctor.ALL + LspDoctor.DEBUG_ADAPTERS) {
      assertEquals(spec.installCommand, LspDoctor.installCommandFor(spec, windows = false))
    }
  }
}
