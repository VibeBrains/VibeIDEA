// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.i18n.VibeI18n
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** The approval dialog must show what gets approved — the header's claims and every file. */
class SkillApprovalTextTest {
  private val pkg = SkillPackage.parse(
    "deploy",
    "---\nname: deploy\ndescription: Выкатка на стенд\nallowed-tools: Bash(git:*) Read\ncompatibility: macOS, Linux\n---\n# deploy\nшаги",
  )
  private val files = SkillFiles(
    listOf(SkillFiles.Entry("SKILL.md", "h1", executable = false), SkillFiles.Entry("scripts/run.sh", "h2", executable = true)),
    escaping = emptyList(),
    incomplete = null,
    scripts = emptyMap(),
  )

  @Test
  fun `the dialog shows what the header claims and every file, runnable ones marked`() {
    val text = SkillApprovalText.render("deploy", pkg, files, SkillApproval.Verdict.NEW, approvedFiles = null)
    for (part in listOf("Выкатка на стенд", "Bash(git:*) Read", "macOS, Linux", "SKILL.md", "# deploy")) {
      assertTrue(part in text, "$part: $text")
    }
    assertTrue("scripts/run.sh — " + VibeI18n.t("skills.approve.executable") in text, text)
  }

  @Test
  fun `a changed skill names the files that changed`() {
    val approved = mapOf("SKILL.md" to "h1", "scripts/run.sh" to "old", "scripts/gone.sh" to "g")
    val text = SkillApprovalText.render("deploy", pkg, files, SkillApproval.Verdict.CHANGED, approved)
    assertTrue(VibeI18n.t("skills.approve.modified", "paths" to "scripts/run.sh") in text, text)
    assertTrue(VibeI18n.t("skills.approve.removed", "paths" to "scripts/gone.sh") in text, text)
  }

  @Test
  fun `an approval given under the old rule says why it is asked again`() {
    // The content may be the same — what the approval covers is not; «it changed» would be a lie.
    val text = SkillApprovalText.render("deploy", pkg, files, SkillApproval.Verdict.CHANGED, approvedFiles = null)
    assertTrue(text.startsWith(VibeI18n.t("skills.approve.rescoped", "id" to "deploy")), text)
  }
}
