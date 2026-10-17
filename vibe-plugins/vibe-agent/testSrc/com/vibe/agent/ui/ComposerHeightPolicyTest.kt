// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposerHeightPolicyTest {
  private fun height(total: Int, preferred: Int, fraction: Float? = null) =
    ComposerHeightPolicy.height(total, preferred, minComposer = 100, minFeed = 120, fraction = fraction)

  @Test
  fun `a short composer takes what it asks for`() {
    assertEquals(150, height(total = 800, preferred = 150))
  }

  @Test
  fun `a long paste is capped at a share of the panel`() {
    assertEquals(320, height(total = 800, preferred = 600))
  }

  @Test
  fun `the person's fraction replaces the automatic height both ways`() {
    assertEquals(560, height(total = 800, preferred = 150, fraction = 0.7f))
    assertEquals(100, height(total = 800, preferred = 400, fraction = 0.05f))
  }

  @Test
  fun `the feed keeps its floor even against a large fraction`() {
    assertEquals(680, height(total = 800, preferred = 150, fraction = 0.95f))
  }

  @Test
  fun `in a panel too small for both the composer keeps its minimum`() {
    assertEquals(100, height(total = 180, preferred = 400))
    assertEquals(100, height(total = 0, preferred = 400))
  }

  @Test
  fun `a dragged height becomes a fraction inside the allowed range`() {
    assertEquals(0.5f, ComposerHeightPolicy.fractionOf(400, 800))
    assertEquals(ComposerHeightPolicy.MAX_FRACTION, ComposerHeightPolicy.fractionOf(2000, 800))
    assertEquals(ComposerHeightPolicy.MIN_FRACTION, ComposerHeightPolicy.fractionOf(-30, 800))
    assertNull(ComposerHeightPolicy.fractionOf(100, 0))
  }
}
