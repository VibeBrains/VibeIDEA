// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Seeds the workspace `.vibe/` environment from defaults embedded in plugin
 * resources — the VibeIDE approach, owner's decision 2026-08-28: VibeIDEA is an
 * agent-first IDE, every project gets its `.vibe/` unconditionally.
 *
 * Rules: create-if-missing ONLY (a user's file is never overwritten); every file
 * we seed is recorded in `.vibe/.seeded.json` as `path → sha256` so a future
 * release-update can tell an untouched seed from a user-customized file (the
 * VibeIDE three-way model; the journal is the groundwork, the update flow is a
 * separate roadmap item). Seeding is best-effort: an IO failure skips the file,
 * never breaks project open.
 */
object VibeDefaults {
  /** Embedded default files: resource name under /vibeDefaults/ → target path inside `.vibe/`. */
  private val MANIFEST = listOf(
    "README.md" to "README.md",
    "gitignore.seed" to ".gitignore", // dotfile can't live in resources as-is
    "rules.md" to "rules.md",
    "hooks.example.jsonc" to "hooks.example.jsonc",
    "pipelines.example.jsonc" to "pipelines.example.jsonc",
    "servers.example.jsonc" to "servers.example.jsonc",
    "design/components.md" to "design/components.md",
    "design/uiKit.md" to "design/uiKit.md",
    // Provider catalog: auto-loaded by ProvidersService (unlike the *.example.jsonc seeds
    // above), `active` is the toggle — owner's decision №24. One file per provider.
    "providers/README.md" to "providers/README.md",
    "providers/_template-openai-compatible.jsonc" to "providers/_template-openai-compatible.jsonc",
    "providers/opencode-go.jsonc" to "providers/opencode-go.jsonc",
    "providers/opencode-zen.jsonc" to "providers/opencode-zen.jsonc",
    "providers/openrouter.jsonc" to "providers/openrouter.jsonc",
    "providers/minimax.jsonc" to "providers/minimax.jsonc",
    "providers/zai.jsonc" to "providers/zai.jsonc",
    "providers/kimi.jsonc" to "providers/kimi.jsonc",
    "providers/deepseek.jsonc" to "providers/deepseek.jsonc",
    "providers/openai.jsonc" to "providers/openai.jsonc",
    "providers/anthropic.jsonc" to "providers/anthropic.jsonc",
    "providers/alibaba-coding-plan.jsonc" to "providers/alibaba-coding-plan.jsonc",
    "providers/meta-muse.jsonc" to "providers/meta-muse.jsonc",
    "providers/muse-glimmer-local.jsonc" to "providers/muse-glimmer-local.jsonc",
    "providers/ollama.jsonc" to "providers/ollama.jsonc",
  )

  private const val RESOURCE_ROOT = "/vibeDefaults/"
  private const val JOURNAL = ".seeded.json"

  /** Resource names of the manifest — the resources↔manifest gate test compares them with the embedded set. */
  internal fun manifestResourceNames(): List<String> = MANIFEST.map { it.first }

  data class SeedReport(val created: Int, val kept: Int)

  /** Seed `.vibe/` under [projectBase]. Idempotent; call OFF the EDT. */
  fun seed(projectBase: String): SeedReport {
    val vibeDir = Path.of(projectBase, ".vibe")
    var created = 0
    var kept = 0
    val journal = loadJournal(vibeDir).toMutableMap()
    runCatching { Files.createDirectories(vibeDir) }.onFailure { return SeedReport(0, 0) }
    for ((resource, relative) in MANIFEST) {
      val target = vibeDir.resolve(relative)
      if (Files.exists(target)) { kept++; continue }
      val content = readResource(resource) ?: continue
      val ok = runCatching {
        target.parent?.let { Files.createDirectories(it) }
        Files.writeString(target, content)
      }.isSuccess
      if (ok) {
        created++
        journal[relative] = sha256(content)
      }
    }
    if (created > 0) saveJournal(vibeDir, journal)
    return SeedReport(created, kept)
  }

  private fun readResource(name: String): String? =
    VibeDefaults::class.java.getResourceAsStream(RESOURCE_ROOT + name)?.use { it.readBytes().toString(Charsets.UTF_8) }

  private fun loadJournal(vibeDir: Path): Map<String, String> {
    val file = vibeDir.resolve(JOURNAL)
    if (!Files.isRegularFile(file)) return emptyMap()
    return runCatching {
      Json.parseToJsonElement(Files.readString(file)).jsonObject
        .mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())
  }

  private fun saveJournal(vibeDir: Path, journal: Map<String, String>) {
    runCatching {
      Files.writeString(vibeDir.resolve(JOURNAL), buildJsonObject {
        journal.forEach { (k, v) -> put(k, v) }
      }.toString() + "\n")
    }
  }

  fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
