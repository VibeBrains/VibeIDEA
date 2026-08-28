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
    assertTrue(report.created >= 23, "created=${report.created}")
    val vibe = java.nio.file.Path.of(base, ".vibe")
    for (f in listOf("README.md", ".gitignore", "rules.md", "hooks.example.jsonc",
                     "pipelines.example.jsonc", "servers.example.jsonc",
                     "design/components.md", "design/uiKit.md", ".seeded.json",
                     "providers/README.md", "providers/_template-openai-compatible.jsonc",
                     "providers/opencode-go.jsonc", "providers/opencode-zen.jsonc",
                     "providers/openrouter.jsonc", "providers/minimax.jsonc",
                     "providers/zai.jsonc", "providers/kimi.jsonc", "providers/deepseek.jsonc",
                     "providers/openai.jsonc", "providers/anthropic.jsonc",
                     "providers/alibaba-coding-plan.jsonc", "providers/meta-muse.jsonc",
                     "providers/muse-glimmer-local.jsonc", "providers/ollama.jsonc")) {
      assertTrue(Files.isRegularFile(vibe.resolve(f)), "missing $f")
    }
    // The provider catalog replaced the old commented-out example (decision №24).
    assertTrue(!Files.exists(vibe.resolve("providers.example.jsonc")))
    // Runtime artifacts are git-ignored from the very first seed.
    val gitignore = Files.readString(vibe.resolve(".gitignore"))
    assertTrue(gitignore.contains("audit.jsonl") && gitignore.contains("checkpoints.jsonl"))
  }

  @Test
  fun seededProviderCatalogIsValidAndActivatesTheBase() {
    val base = tempProject()
    VibeDefaults.seed(base)
    val warnings = mutableListOf<String>()
    val emptyGlobal = Files.createTempDirectory("vibe-empty-global")
    val providers = com.vibe.agent.providers.ProvidersService.loadFrom(
      emptyGlobal, java.nio.file.Path.of(base, ".vibe")) { warnings.add(it) }
    // The owner's base set is active out of the box; everything else stays dormant.
    assertEquals(
      setOf("opencode-go", "opencode-zen", "openrouter", "minimax", "zai", "kimi", "deepseek"),
      providers.map { it.id }.toSet())
    // Every seeded file parsed cleanly — a JSONC typo anywhere in the catalog shows up here.
    assertTrue(warnings.isEmpty(), "warnings=$warnings")
    // Keys never live in the files; every active provider names where its key comes from.
    for (p in providers) {
      assertTrue(p.apiKeyEnv != null || p.apiKeyRef != null, "provider '${p.id}' has no key source")
      assertTrue(!p.baseURL.isNullOrBlank(), "provider '${p.id}' has no baseURL")
    }
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
