// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.security

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForeignProjectNoticeTest {
  @Test
  fun `an unseen project earns the notice`() {
    assertTrue(ForeignProjectNotice.shouldWarn("/tmp/foreign", emptySet(), enabled = true))
  }

  @Test
  fun `a known project stays quiet`() {
    assertFalse(ForeignProjectNotice.shouldWarn("/tmp/mine", setOf("/tmp/mine"), enabled = true))
  }

  @Test
  fun `the switch wins over everything`() {
    assertFalse(ForeignProjectNotice.shouldWarn("/tmp/foreign", emptySet(), enabled = false))
  }

  @Test
  fun `a project without a path is not a project`() {
    assertFalse(ForeignProjectNotice.shouldWarn(null, emptySet(), enabled = true))
    assertFalse(ForeignProjectNotice.shouldWarn("  ", emptySet(), enabled = true))
  }
}
