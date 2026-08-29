// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillValidatorTest {
  private fun pkg(
    id: String = "grill",
    name: String? = "grill",
    description: String? = "когда применять",
    body: String = "# grill\nинструкции",
    keys: List<String> = listOf("name", "description"),
    frontmatter: Boolean = true,
  ) = SkillPackage(id, name, description, body, keys, frontmatter)

  private fun messages(findings: List<SkillValidator.Finding>) = findings.joinToString("; ") { it.message }

  @Test
  fun `a correct package has nothing to say`() {
    assertTrue(SkillValidator.validate(pkg()).isEmpty())
  }

  @Test
  fun `missing name or description are errors`() {
    assertTrue(SkillValidator.hasErrors(SkillValidator.validate(pkg(name = null))))
    assertTrue(SkillValidator.hasErrors(SkillValidator.validate(pkg(description = null))))
  }

  @Test
  fun `name must match the folder — otherwise the call never finds it`() {
    val findings = SkillValidator.validate(pkg(id = "grill", name = "grill-plan"))
    assertTrue(SkillValidator.hasErrors(findings))
    assertTrue(messages(findings).contains("/skill:grill"), messages(findings))
  }

  @Test
  fun `name shape is enforced`() {
    assertTrue(SkillValidator.hasErrors(SkillValidator.validate(pkg(id = "Grill Plan", name = "Grill Plan"))))
    assertTrue(SkillValidator.validate(pkg(id = "write-tech-spec", name = "write-tech-spec")).isEmpty())
  }

  @Test
  fun `an unknown top-level key is an error, not a nicety`() {
    // The reference validator rejects the whole package, so a skill that works here would be
    // refused everywhere else.
    val findings = SkillValidator.validate(pkg(keys = listOf("name", "description", "vibeVersion")))
    assertTrue(SkillValidator.hasErrors(findings))
    assertTrue(messages(findings).contains("metadata"), messages(findings))
  }

  @Test
  fun `every key of the reference set is accepted`() {
    val keys = listOf("name", "description", "license", "allowed-tools", "compatibility", "metadata")
    assertTrue(SkillValidator.validate(pkg(keys = keys)).isEmpty())
  }

  @Test
  fun `an empty body is an error, an oversized one only a warning`() {
    assertTrue(SkillValidator.hasErrors(SkillValidator.validate(pkg(body = "   "))))
    val big = SkillValidator.validate(pkg(body = "x".repeat(SkillValidator.MAX_BODY_CHARS + 1)))
    assertFalse(SkillValidator.hasErrors(big))
    assertTrue(big.any { it.level == SkillValidator.Level.WARNING })
  }

  @Test
  fun `an attachment escaping the skills tree is an error`() {
    val findings = SkillValidator.validate(pkg(), escapingAttachments = listOf("reference.md"))
    assertTrue(SkillValidator.hasErrors(findings))
  }

  @Test
  fun `scripts next to a skill earn a warning, not a refusal`() {
    val findings = SkillValidator.validate(pkg(), attachments = listOf("scripts/"))
    assertFalse(SkillValidator.hasErrors(findings))
    assertTrue(messages(findings).contains("scripts/"))
  }

  @Test
  fun `a long description is a warning — it is a hint, not a chapter`() {
    val findings = SkillValidator.validate(pkg(description = "д".repeat(SkillValidator.MAX_DESCRIPTION_CHARS + 1)))
    assertFalse(SkillValidator.hasErrors(findings))
  }
}
