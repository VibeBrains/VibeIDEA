// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.context

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YAML scalars in a rule header: quotes, escapes and block values must reach the model as text.
 *
 * The set writes a description with a colon in quotes and the party skill as a folded block.
 * A parser that keeps the quotes or the `>` shows the person a broken description.
 */
class ProjectRuleFrontmatterTest {
  private fun rule(header: String) = ProjectRules.parse("r", "---\n$header\n---\nbody")

  @Test
  fun `double quoted description loses quotes and expands escapes`() {
    val parsed = rule("description: \"say \\\"hi\\\" at C:\\\\tmp\\nnext\"\nalwaysApply: true")
    assertEquals("say \"hi\" at C:\\tmp\nnext", parsed.description)
    assertTrue(parsed.alwaysApply, "the key after the quoted line is still read")
  }

  @Test
  fun `single quoted description loses quotes and doubles the apostrophe`() {
    val parsed = rule("description: 'it''s a rule: really'")
    assertEquals("it's a rule: really", parsed.description)
  }

  @Test
  fun `folded block joins lines with a space and is not the marker itself`() {
    val parsed = rule("description: >\n  Первая строка\n  вторая строка\nalwaysApply: true")
    assertEquals("Первая строка вторая строка", parsed.description)
    assertTrue(parsed.alwaysApply, "the key after the block is still read")
  }

  @Test
  fun `literal block keeps the lines as written`() {
    val parsed = rule("description: |\n  Первая\n  Вторая\ntriggers: [a, b]")
    assertEquals("Первая\nВторая", parsed.description)
    assertEquals(listOf("a", "b"), parsed.triggers)
  }

  @Test
  fun `an empty description stays empty`() {
    assertNull(rule("description:\nalwaysApply: true").description)
  }
}
