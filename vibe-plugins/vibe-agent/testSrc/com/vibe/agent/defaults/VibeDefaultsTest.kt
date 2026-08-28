// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibeDefaultsTest {
  private fun tempProject() = Files.createTempDirectory("vibe-defaults-test").toString()

  @Test
  fun seedsFullEnvironmentIntoEmptyProject() {
    val base = tempProject()
    val report = VibeDefaults.seed(base)
    assertTrue(report.created >= 9, "created=${report.created}")
    val vibe = java.nio.file.Path.of(base, ".vibe")
    for (f in listOf("README.md", ".gitignore", "rules.md", "hooks.example.jsonc",
                     "pipelines.example.jsonc", "servers.example.jsonc", "providers.example.jsonc",
                     "design/components.md", "design/uiKit.md", ".seeded.json")) {
      assertTrue(Files.isRegularFile(vibe.resolve(f)), "missing $f")
    }
    // Runtime artifacts are git-ignored from the very first seed.
    val gitignore = Files.readString(vibe.resolve(".gitignore"))
    assertTrue(gitignore.contains("audit.jsonl") && gitignore.contains("checkpoints.jsonl"))
  }

  @Test
  fun secondSeedKeepsEverythingUntouched() {
    val base = tempProject()
    VibeDefaults.seed(base)
    val rules = java.nio.file.Path.of(base, ".vibe", "rules.md")
    Files.writeString(rules, "MY CUSTOM RULES")
    val second = VibeDefaults.seed(base)
    assertEquals(0, second.created)
    // A user's edit is never overwritten.
    assertEquals("MY CUSTOM RULES", Files.readString(rules))
  }

  @Test
  fun seedFillsOnlyMissingFiles() {
    val base = tempProject()
    val vibe = java.nio.file.Path.of(base, ".vibe")
    Files.createDirectories(vibe)
    Files.writeString(vibe.resolve("hooks.example.jsonc"), "pre-existing")
    val report = VibeDefaults.seed(base)
    assertTrue(report.created > 0)
    assertTrue(report.kept >= 1)
    assertEquals("pre-existing", Files.readString(vibe.resolve("hooks.example.jsonc")))
  }

  @Test
  fun journalRecordsSeededHashes() {
    val base = tempProject()
    VibeDefaults.seed(base)
    val journal = Files.readString(java.nio.file.Path.of(base, ".vibe", ".seeded.json"))
    assertTrue(journal.contains("rules.md"))
    assertTrue(journal.contains("hooks.example.jsonc"))
  }
}
