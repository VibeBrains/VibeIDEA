// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HunkNavigationTest {
  @Test
  fun `a place that disappeared falls back to the last hunk, an empty list has no place`() {
    assertEquals(2, HunkNavigation.clamp(2, 5))
    assertEquals(4, HunkNavigation.clamp(9, 5))
    assertEquals(0, HunkNavigation.clamp(null, 5))
    assertNull(HunkNavigation.clamp(3, 0))
    assertNull(HunkNavigation.clamp(null, 0))
  }

  @Test
  fun `steps go round the end of the list in both directions`() {
    assertEquals(1, HunkNavigation.step(0, 3, forward = true))
    assertEquals(0, HunkNavigation.step(2, 3, forward = true))
    assertEquals(2, HunkNavigation.step(0, 3, forward = false))
    assertEquals(0, HunkNavigation.step(null, 3, forward = true))
    assertEquals(2, HunkNavigation.step(null, 3, forward = false))
    assertNull(HunkNavigation.step(0, 0, forward = true))
  }

  @Test
  fun `a hunk in view does not move the screen, one outside or half outside does`() {
    assertFalse(HunkNavigation.needsScroll(top = 100, bottom = 160, viewTop = 0, viewBottom = 600))
    assertTrue(HunkNavigation.needsScroll(top = 700, bottom = 760, viewTop = 0, viewBottom = 600))
    assertTrue(HunkNavigation.needsScroll(top = -80, bottom = -20, viewTop = 0, viewBottom = 600))
    assertTrue(HunkNavigation.needsScroll(top = 560, bottom = 640, viewTop = 0, viewBottom = 600))
    assertTrue(HunkNavigation.needsScroll(top = -10, bottom = 40, viewTop = 0, viewBottom = 600))
  }

  @Test
  fun `a hunk taller than the screen is in view once its top is`() {
    assertFalse(HunkNavigation.needsScroll(top = 20, bottom = 2000, viewTop = 0, viewBottom = 600))
    assertTrue(HunkNavigation.needsScroll(top = -20, bottom = 2000, viewTop = 0, viewBottom = 600))
    assertTrue(HunkNavigation.needsScroll(top = 600, bottom = 2600, viewTop = 0, viewBottom = 600))
  }

  @Test
  fun `a file that was resolved hands the place to the next file the same way`() {
    // Files use the same rule as hunks: the follower takes the place, after the last one the one before it
    assertEquals(1, HunkNavigation.afterResolve(1, 3))
    assertEquals(2, HunkNavigation.afterResolve(3, 3))
    assertNull(HunkNavigation.afterResolve(0, 0))
  }
}
