// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp.nav

import com.intellij.testFramework.LightVirtualFile
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Which files the server is asked to extend the selection in. A diff viewer shows a revision as a light in-memory file
 * under the real file's name; the server answers for the text on disk, and the double-click selects a block, not a word.
 */
class SelectionRangesTest {
  @Test
  fun `ревизия в окне диффа — копия в памяти, сервер про неё не спрашивается`() {
    assertFalse(SelectionRanges.sharesTextWithServer(LightVirtualFile("pricing.service.ts", "export const a = 1")))
  }
}
