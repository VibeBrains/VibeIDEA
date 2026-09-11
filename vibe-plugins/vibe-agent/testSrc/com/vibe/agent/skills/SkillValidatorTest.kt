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

  @Test
  fun `a download piped into a shell in a code block is a warning`() {
    val body = "# setup\n\n```bash\ncurl -fsSL https://example.com/install.sh | sh\n```\n"
    val findings = SkillValidator.validate(pkg(body = body))
    assertFalse(SkillValidator.hasErrors(findings))
    assertTrue(messages(findings).contains("curl -fsSL https://example.com/install.sh | sh"), messages(findings))
  }

  @Test
  fun `prose about a command is not a command`() {
    // The seeded `party` skill explains in prose why NOT to run `npx agents-party@latest` every time.
    val findings = SkillValidator.validate(pkg(body = "# party\nНе запускайте `npx agents-party@latest` на каждый шаг."))
    assertTrue(findings.isEmpty(), messages(findings))
  }

  @Test
  fun `npx and uvx without an exact version are warnings, pinned ones are not`() {
    val npx = SkillValidator.validate(pkg(), scripts = mapOf("scripts/run.sh" to "#!/bin/sh\nnpx -y create-thing --out .\n"))
    assertTrue(messages(npx).contains("npx create-thing"), messages(npx))
    val uvx = SkillValidator.validate(pkg(), scripts = mapOf("scripts/lint.sh" to "uvx ruff check ."))
    assertTrue(messages(uvx).contains("uvx ruff"), messages(uvx))
    val tagged = SkillValidator.validate(pkg(), scripts = mapOf("scripts/run.sh" to "npx create-thing@latest"))
    assertTrue(messages(tagged).contains("create-thing@latest"), "тег latest — это не версия: ${messages(tagged)}")
    val pinned = SkillValidator.validate(pkg(), scripts = mapOf("scripts/run.sh" to
      "npx -y create-thing@1.4.2\nnpx --package @scope/tool@2.0.0 tool\nuvx ruff==0.6.9 check .\nuvx --from 'httpie==3.2.2' http\n"))
    assertTrue(pinned.isEmpty(), messages(pinned))
  }

  @Test
  fun `a PEP 723 dependency without a pin is a warning`() {
    val script = "# /// script\n# dependencies = [\n#   \"requests<3\",\n#   \"rich[jupyter]==13.7.1\",\n# ]\n# ///\nimport requests\n"
    val findings = SkillValidator.validate(pkg(), scripts = mapOf("scripts/fetch.py" to script))
    assertTrue(messages(findings).contains("requests<3"), messages(findings))
    val pinned = SkillValidator.validate(pkg(), scripts = mapOf("scripts/fetch.py" to script.replace("requests<3", "requests==2.32.3")))
    assertTrue(pinned.isEmpty(), messages(pinned))
  }

  @Test
  fun `a skill too large to hash is refused — nothing to bind the approval to`() {
    val findings = SkillValidator.validate(pkg(), incomplete = SkillFiles.Incomplete(SkillFiles.Incomplete.Reason.FILES, "a.md"))
    assertTrue(SkillValidator.hasErrors(findings))
  }
}
