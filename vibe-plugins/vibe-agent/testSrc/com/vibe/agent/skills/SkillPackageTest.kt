// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillPackageTest {
  private val full = """
    ---
    name: grill
    description: "Стресс-тест плана до устойчивости."
    metadata:
      vibeVersion: "1.0.0"
      depends:
        - other-skill
    ---

    # grill

    Тело навыка.
  """.trimIndent()

  @Test
  fun `frontmatter and body are separated`() {
    val pkg = SkillPackage.parse("grill", full)
    assertEquals("grill", pkg.name)
    assertEquals("Стресс-тест плана до устойчивости.", pkg.description)
    assertTrue(pkg.body.startsWith("# grill"))
    assertTrue(pkg.hasFrontmatter)
  }

  @Test
  fun `nested keys belong to their parent, not to the top level`() {
    val pkg = SkillPackage.parse("grill", full)
    assertEquals(listOf("name", "description", "metadata"), pkg.topLevelKeys)
    assertFalse(pkg.topLevelKeys.contains("vibeVersion"), "vibeVersion лежит под metadata и верхним уровнем не считается")
  }

  @Test
  fun `a file without frontmatter is all body`() {
    val pkg = SkillPackage.parse("plain", "# Просто заметка\n\nтекст")
    assertFalse(pkg.hasFrontmatter)
    assertNull(pkg.name)
    assertEquals("# Просто заметка\n\nтекст", pkg.body)
  }

  @Test
  fun `an unterminated header is not eaten as a header`() {
    // Otherwise a missing closing --- would silently swallow the whole skill.
    val pkg = SkillPackage.parse("broken", "---\nname: broken\n\n# тело осталось")
    assertFalse(pkg.hasFrontmatter)
    assertTrue(pkg.body.contains("# тело осталось"))
  }

  @Test
  fun `quotes and comments in the header are handled`() {
    val pkg = SkillPackage.parse("x", "---\n# комментарий\nname: 'x'\ndescription: \"описание\"\n---\nтело")
    assertEquals("x", pkg.name)
    assertEquals("описание", pkg.description)
    assertEquals(listOf("name", "description"), pkg.topLevelKeys)
  }

  @Test
  fun `allowed-tools and compatibility are read for the approval dialog, in every YAML shape`() {
    val scalar = SkillPackage.parse("x", "---\nname: x\ndescription: d\nallowed-tools: Bash(git:*) Read\ncompatibility: \"Python 3.11+\"\n---\nтело")
    assertEquals("Bash(git:*) Read", scalar.field("allowed-tools"))
    assertEquals("Python 3.11+", scalar.field("compatibility"))
    assertNull(scalar.field("license"), "нет ключа — нет строки в диалоге")
    val block = SkillPackage.parse("x", "---\nname: x\nallowed-tools:\n  - Bash\n  - \"Read\"\n---\nтело")
    assertEquals("Bash, Read", block.field("allowed-tools"))
    val flow = SkillPackage.parse("x", "---\nname: x\nallowed-tools: [Bash, 'Read']\n---\nтело")
    assertEquals("Bash, Read", flow.field("allowed-tools"))
  }
}
