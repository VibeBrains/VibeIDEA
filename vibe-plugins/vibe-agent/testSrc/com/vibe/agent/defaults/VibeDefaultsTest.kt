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
    assertTrue(report.created >= 62, "created=${report.created}")
    val vibe = java.nio.file.Path.of(base, ".vibe")
    // Spot checks across every section of the shared VibeBrains set — the full
    // resources↔manifest correspondence is guarded by the gate test above.
    for (f in listOf("README.md", ".gitignore", "rules.md", "hooks.example.jsonc",
                     "pipelines.example.jsonc", "servers.example.jsonc",
                     "design/components.md", "design/uiKit.md", ".seeded.json",
                     "agents.example.jsonc", "providers.example.jsonc",
                     "learning/MISSION.example.md", "prompts/pipeline.md",
                     "rules/verification.mdc", "skills/review-pr/SKILL.md",
                     "providers/README.md", "providers/_template-openai-compatible.jsonc",
                     "providers/opencode-go.jsonc", "providers/opencode-zen.jsonc",
                     "providers/openrouter.jsonc", "providers/minimax.jsonc",
                     "providers/zai.jsonc", "providers/kimi.jsonc", "providers/deepseek.jsonc",
                     "providers/openai.jsonc", "providers/anthropic.jsonc",
                     "providers/alibaba-coding-plan.jsonc", "providers/meta-muse.jsonc",
                     "providers/muse-glimmer-local.jsonc", "providers/ollama.jsonc")) {
      assertTrue(Files.isRegularFile(vibe.resolve(f)), "missing $f")
    }
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
  fun manifestMatchesEmbeddedResourcesExactly() {
    // The seed set will live in a shared submodule (VibeBrains): a file added there without a
    // manifest entry would silently never be seeded, and a manifest entry without a file would
    // silently seed nothing — both must fail loudly here.
    val resources = listEmbeddedResources()
    val manifest = VibeDefaults.manifestResourceNames().toSet()
    assertEquals(emptySet(), manifest - resources, "манифест ссылается на несуществующие ресурсы")
    assertEquals(emptySet(), resources - manifest, "ресурс без записи в манифесте — не будет засеян")
  }

  private fun listEmbeddedResources(): Set<String> {
    val url = VibeDefaults::class.java.getResource("/vibeDefaults") ?: error("нет /vibeDefaults в classpath")
    return when (url.protocol) {
      "file" -> {
        val root = java.nio.file.Path.of(url.toURI())
        java.nio.file.Files.walk(root).use { s ->
          s.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.toList()
        }.toSet()
      }
      "jar" -> {
        // IntelliJ's test classloader returns its own URLConnection — parse the jar path
        // out of the URL (jar:file:/path/to.jar!/vibeDefaults) instead of casting.
        val jarPath = java.net.URLDecoder.decode(
          url.toString().substringAfter("jar:file:").substringBefore("!"), Charsets.UTF_8)
        java.util.zip.ZipFile(jarPath).use { zip ->
          zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("vibeDefaults/") }
            .map { it.name.removePrefix("vibeDefaults/") }
            .toSet()
        }
      }
      else -> error("неожиданный протокол ресурсов: ${url.protocol}")
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
