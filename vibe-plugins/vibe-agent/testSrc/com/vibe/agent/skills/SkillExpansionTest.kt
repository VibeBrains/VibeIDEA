// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillExpansionTest {
  @Test
  fun `a mention at the start and after whitespace counts`() {
    assertEquals(listOf("grill"), SkillExpansion.mentioned("/skill:grill проверь план"))
    assertEquals(listOf("grill"), SkillExpansion.mentioned("сначала подумай, потом /skill:grill"))
  }

  @Test
  fun `a mention glued to other text does not count`() {
    // Same rule the popup uses to open, so what the user saw is what gets expanded.
    assertTrue(SkillExpansion.mentioned("см. http://x/skill:grill").isEmpty())
  }

  @Test
  fun `duplicates collapse and order is kept`() {
    assertEquals(listOf("a", "b"), SkillExpansion.mentioned("/skill:a /skill:b /skill:a"))
  }

  @Test
  fun `no mentions means no work`() {
    assertTrue(SkillExpansion.mentioned("обычное сообщение про /skills").isEmpty())
  }

  @Test
  fun `the wrapper names the skill so the model can tell recipe from data`() {
    assertEquals("<skill ref=\"grill\">\nтело\n</skill>", SkillExpansion.wrap("grill", "тело"))
  }
}
