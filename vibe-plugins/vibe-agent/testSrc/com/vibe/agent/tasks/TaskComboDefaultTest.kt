// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.tasks

import kotlin.test.Test
import kotlin.test.assertEquals

/** The default is applied once: otherwise a combo switched off by hand would come back on its own. */
class TaskComboDefaultTest {
  @Test
  fun `on a fresh profile the combo is switched on`() {
    assertEquals(TaskComboDefault.Action.ENABLE, TaskComboDefault.decide(alreadyApplied = false, alwaysDisplayAlready = false))
  }

  @Test
  fun `a combo switched off by hand stays off`() {
    assertEquals(TaskComboDefault.Action.LEAVE_ALONE, TaskComboDefault.decide(alreadyApplied = true, alwaysDisplayAlready = false))
  }

  @Test
  fun `a combo already on is not touched`() {
    assertEquals(TaskComboDefault.Action.LEAVE_ALONE, TaskComboDefault.decide(alreadyApplied = false, alwaysDisplayAlready = true))
    assertEquals(TaskComboDefault.Action.LEAVE_ALONE, TaskComboDefault.decide(alreadyApplied = true, alwaysDisplayAlready = true))
  }
}
