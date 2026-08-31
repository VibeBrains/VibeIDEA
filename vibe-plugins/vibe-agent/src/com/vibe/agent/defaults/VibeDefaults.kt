// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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
  /**
   * Embedded default files: resource name under /vibeDefaults/ → target path inside `.vibe/`.
   * The set is the VibeBrains submodule (shared with VibeIDE) and is seeded IN FULL — the
   * environment must come out identical whichever product seeded it first. Kept in sync with
   * the submodule by the resources↔manifest gate test. The only special mapping is
   * `gitignore.seed` → `.gitignore` (a dotfile can't live in resources as-is).
   */
  private val MANIFEST = listOf(
    "README.md" to "README.md",
    "agents.example.jsonc" to "agents.example.jsonc",
    "design/components.md" to "design/components.md",
    "design/uiKit.md" to "design/uiKit.md",
    "gitignore.seed" to ".gitignore",
    "hooks.example.jsonc" to "hooks.example.jsonc",
    "learning/MISSION.example.md" to "learning/MISSION.example.md",
    "pipelines.example.jsonc" to "pipelines.example.jsonc",
    "prompts/CLAUDE-FABLE-5.md" to "prompts/CLAUDE-FABLE-5.md",
    "prompts/deep-review.md" to "prompts/deep-review.md",
    "prompts/example.md" to "prompts/example.md",
    "prompts/pipeline.md" to "prompts/pipeline.md",
    "providers.example.jsonc" to "providers.example.jsonc",
    // Provider catalog: auto-loaded by ProvidersService (unlike the *.example.jsonc seeds),
    // `active` is the toggle — owner's decision №24. One file per provider.
    "providers/README.md" to "providers/README.md",
    "providers/_template-openai-compatible.jsonc" to "providers/_template-openai-compatible.jsonc",
    "providers/alibaba-coding-plan.jsonc" to "providers/alibaba-coding-plan.jsonc",
    "providers/anthropic.jsonc" to "providers/anthropic.jsonc",
    "providers/deepseek.jsonc" to "providers/deepseek.jsonc",
    "providers/kimi.jsonc" to "providers/kimi.jsonc",
    "providers/meta-muse.jsonc" to "providers/meta-muse.jsonc",
    "providers/minimax.jsonc" to "providers/minimax.jsonc",
    "providers/muse-glimmer-local.jsonc" to "providers/muse-glimmer-local.jsonc",
    "providers/ollama.jsonc" to "providers/ollama.jsonc",
    "providers/openai.jsonc" to "providers/openai.jsonc",
    "providers/opencode-go.jsonc" to "providers/opencode-go.jsonc",
    "providers/opencode-zen.jsonc" to "providers/opencode-zen.jsonc",
    "providers/openrouter.jsonc" to "providers/openrouter.jsonc",
    "providers/zai.jsonc" to "providers/zai.jsonc",
    "rules.md" to "rules.md",
    "rules/clean-rules.mdc" to "rules/clean-rules.mdc",
    "rules/dev-engine.mdc" to "rules/dev-engine.mdc",
    "rules/knowledge.mdc" to "rules/knowledge.mdc",
    "rules/nginx.mdc" to "rules/nginx.mdc",
    "rules/pipeline.mdc" to "rules/pipeline.mdc",
    "rules/providers-json.mdc" to "rules/providers-json.mdc",
    "rules/release.mdc" to "rules/release.mdc",
    "rules/roadmap-autopilot.mdc" to "rules/roadmap-autopilot.mdc",
    "rules/roadmap.mdc" to "rules/roadmap.mdc",
    "rules/script-save.mdc" to "rules/script-save.mdc",
    "rules/spec-first.mdc" to "rules/spec-first.mdc",
    "rules/ui-kit.mdc" to "rules/ui-kit.mdc",
    "rules/verification.mdc" to "rules/verification.mdc",
    "rules/versioning.mdc" to "rules/versioning.mdc",
    "servers.example.jsonc" to "servers.example.jsonc",
    "skills/design-vocabulary/SKILL.md" to "skills/design-vocabulary/SKILL.md",
    "skills/example/SKILL.md" to "skills/example/SKILL.md",
    "skills/grill/SKILL.md" to "skills/grill/SKILL.md",
    "skills/implement-specs/SKILL.md" to "skills/implement-specs/SKILL.md",
    "skills/optimize-by-metric/SKILL.md" to "skills/optimize-by-metric/SKILL.md",
    "skills/party/SKILL.md" to "skills/party/SKILL.md",
    "skills/resolve-merge-conflicts/SKILL.md" to "skills/resolve-merge-conflicts/SKILL.md",
    "skills/resolve-merge-conflicts/scripts/extract_conflict_context.py" to "skills/resolve-merge-conflicts/scripts/extract_conflict_context.py",
    "skills/review-pr/SKILL.md" to "skills/review-pr/SKILL.md",
    "skills/roadmap-autopilot/SKILL.md" to "skills/roadmap-autopilot/SKILL.md",
    "skills/spec-driven-implementation/SKILL.md" to "skills/spec-driven-implementation/SKILL.md",
    "skills/teach/SKILL.md" to "skills/teach/SKILL.md",
    "skills/update-skill/SKILL.md" to "skills/update-skill/SKILL.md",
    "skills/update-skill/references/best-practices.md" to "skills/update-skill/references/best-practices.md",
    "skills/write-product-spec/SKILL.md" to "skills/write-product-spec/SKILL.md",
    "skills/write-product-spec/references/PRODUCT.skeleton.md" to "skills/write-product-spec/references/PRODUCT.skeleton.md",
    "skills/write-tech-spec/SKILL.md" to "skills/write-tech-spec/SKILL.md",
    "skills/write-tech-spec/references/TECH.skeleton.md" to "skills/write-tech-spec/references/TECH.skeleton.md",
  )

  private const val RESOURCE_ROOT = "/vibeDefaults/"
  private const val JOURNAL = ".seeded.json"
  private const val DEPRECATED = "deprecated.json"
  private const val VERSIONS = "versions.json"

  /** Resource names of the manifest — the resources↔manifest gate test compares them with the embedded set. */
  internal fun manifestResourceNames(): List<String> = MANIFEST.map { it.first }

  /** The set's revision registry, for anyone who needs to know which release a file came from. */
  fun versionsContent(): String? = readResource(VERSIONS)

  /** Set files that serve the set itself (registries, its bump script) and are never seeded. */
  internal val SET_METADATA = setOf(DEPRECATED, VERSIONS, "bump.mjs")

  /** What we recorded about a file we seeded: content, the revision it came from, and the
   *  revision the user last said «keep mine» about (so a conflict is not raised twice). */
  data class JournalEntry(val sha: String, val version: Int? = null, val reconciled: Int? = null)

  /** One file the release moved on while the user's copy differs — needs a human decision. */
  data class SeedConflict(val path: String, val fromVersion: Int?, val toVersion: Int)

  data class SeedReport(
    val created: Int,
    val kept: Int,
    /** Stale seeds deleted because their content matched a known historical version. */
    val removed: List<String> = emptyList(),
    /** Stale seeds LEFT in place: the user edited them, deleting would lose work. */
    val keptModified: List<String> = emptyList(),
    /** Untouched copies of older revisions, silently refreshed to the current one. */
    val updated: List<String> = emptyList(),
    /** Edited copies whose revision moved in the set — reported, never overwritten. */
    val conflicts: List<SeedConflict> = emptyList(),
    /** Set-discipline errors: content moved without a revision bump (bump.mjs not run). */
    val setDrift: List<String> = emptyList(),
  )

  /**
   * Seed `.vibe/` under [projectBase]. Idempotent; call OFF the EDT.
   *
   * Per file the set's revision registry decides (see [SeedRevisions]): missing → create,
   * untouched copy of an older revision → refresh silently, the user's own edit → never touch
   * (reported as a conflict when the set has moved on, so the caller can offer a comparison).
   */
  fun seed(projectBase: String): SeedReport {
    val vibeDir = Path.of(projectBase, ".vibe")
    var created = 0
    var kept = 0
    val updated = ArrayList<String>()
    val conflicts = ArrayList<SeedConflict>()
    val drift = ArrayList<String>()
    val journal = loadJournal(vibeDir).toMutableMap()
    val revisions = SeedRevisions.parse(readResource(VERSIONS))
    runCatching { Files.createDirectories(vibeDir) }.onFailure { return SeedReport(0, 0) }
    for ((resource, relative) in MANIFEST) {
      val target = vibeDir.resolve(relative)
      val content = readResource(resource) ?: continue
      val revision = revisions[resource]
      val recorded = journal[relative]
      val onDisk = if (Files.isRegularFile(target)) runCatching { Files.readAllBytes(target) }.getOrNull() else null
      val verdict = SeedRevisions.verdict(
        revision = revision,
        localSha = onDisk?.let { sha256(it) },
        localShaLf = onDisk?.let { sha256(String(it, Charsets.UTF_8).replace("\r\n", "\n")) },
        journalSha = recorded?.sha,
        journalVersion = recorded?.version,
        reconciledVersion = recorded?.reconciled,
      )
      when (verdict) {
        SeedVerdict.CREATE -> if (write(target, content)) {
          created++
          journal[relative] = JournalEntry(sha256(content), revision?.version)
        }
        SeedVerdict.UPDATE -> if (write(target, content)) {
          updated.add(relative)
          journal[relative] = JournalEntry(sha256(content), revision?.version)
        }
        SeedVerdict.SAME -> {
          kept++
          // Keep the recorded revision current so a later bump is measured from here.
          if (revision != null && recorded?.version != revision.version) {
            journal[relative] = JournalEntry(sha256(content), revision.version, recorded?.reconciled)
          }
        }
        SeedVerdict.CONFLICT -> {
          kept++
          conflicts.add(SeedConflict(relative, recorded?.version, revision?.version ?: 0))
        }
        SeedVerdict.SET_DRIFT -> { kept++; drift.add(relative) }
        SeedVerdict.USER_EDIT -> kept++
      }
    }
    val (removed, keptModified) = cleanupDeprecated(vibeDir, journal, readResource(DEPRECATED))
    if (created > 0 || updated.isNotEmpty() || removed.isNotEmpty() || journal != loadJournal(vibeDir)) {
      saveJournal(vibeDir, journal)
    }
    return SeedReport(created, kept, removed, keptModified, updated, conflicts, drift)
  }

  private fun write(target: Path, content: String): Boolean = runCatching {
    target.parent?.let { Files.createDirectories(it) }
    Files.writeString(target, content)
  }.isSuccess

  /** Records «keep mine» for [paths] at the set's current revision — the conflict stays quiet
   *  until the set moves again. Called from the notification action. */
  fun markReconciled(projectBase: String, paths: List<String>) {
    val vibeDir = Path.of(projectBase, ".vibe")
    val revisions = SeedRevisions.parse(readResource(VERSIONS))
    val byTarget = MANIFEST.associate { (resource, relative) -> relative to resource }
    val journal = loadJournal(vibeDir).toMutableMap()
    for (path in paths) {
      val version = byTarget[path]?.let { revisions[it]?.version } ?: continue
      val current = journal[path]
      journal[path] = JournalEntry(current?.sha ?: "", current?.version, reconciled = version)
    }
    saveJournal(vibeDir, journal)
  }

  /** The release's content for a seeded path (left side of the comparison). */
  /** Content shipped for a target path inside `.vibe/`; also accepts the resource name itself. */
  fun releaseContent(relativePath: String): String? =
    MANIFEST.firstOrNull { it.second == relativePath }?.let { readResource(it.first) }

  /**
   * Delete stale seeds (files dropped/renamed in the shared set) — but ONLY when the
   * on-disk copy byte-matches a known historical version from `deprecated.json`:
   * an edited file is the user's work and stays untouched (reported instead).
   * [spec] is a parameter (not read inline) so tests can drive both branches.
   */
  internal fun cleanupDeprecated(vibeDir: Path, journal: MutableMap<String, JournalEntry>, spec: String?): Pair<List<String>, List<String>> {
    val removed = ArrayList<String>()
    val keptModified = ArrayList<String>()
    if (spec == null) return removed to keptModified
    runCatching {
      val entries = Json.parseToJsonElement(spec).jsonObject["deprecated"]?.jsonArray ?: return removed to keptModified
      for (el in entries) {
        val o = el.jsonObject
        val rel = o["path"]?.jsonPrimitive?.contentOrNull ?: continue
        val target = vibeDir.resolve(rel)
        if (!Files.isRegularFile(target)) continue
        val known = o["sha256"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        // Reference hashes are taken from LF files; also try the LF-normalized digest so a
        // CRLF checkout (Windows, text=auto) does not masquerade as a user edit.
        val bytes = Files.readAllBytes(target)
        val actual = sha256(bytes)
        val actualLf = sha256(String(bytes, Charsets.UTF_8).replace("\r\n", "\n"))
        if (actual in known || actualLf in known) {
          if (runCatching { Files.delete(target) }.isSuccess) {
            journal.remove(rel)
            removed.add(rel)
          }
        }
        else {
          keptModified.add(rel)
        }
      }
    }
    return removed to keptModified
  }

  private fun readResource(name: String): String? =
    VibeDefaults::class.java.getResourceAsStream(RESOURCE_ROOT + name)?.use { it.readBytes().toString(Charsets.UTF_8) }

  /**
   * Reads `.seeded.json` in either shape: the original flat `path → sha` (projects seeded before
   * revisions existed — their entries still work, just without a revision) and the current
   * `{version, files: {path: {sha, rev, reconciled}}}`.
   */
  internal fun loadJournal(vibeDir: Path): Map<String, JournalEntry> {
    val file = vibeDir.resolve(JOURNAL)
    if (!Files.isRegularFile(file)) return emptyMap()
    return runCatching {
      val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
      val files = root["files"]?.jsonObject ?: root // legacy: the root IS the map
      files.mapNotNull { (path, el) ->
        when (el) {
          is kotlinx.serialization.json.JsonPrimitive -> el.contentOrNull?.let { path to JournalEntry(it) }
          is JsonObject -> el["sha"]?.jsonPrimitive?.contentOrNull?.let {
            path to JournalEntry(it, el["rev"]?.jsonPrimitive?.intOrNull, el["reconciled"]?.jsonPrimitive?.intOrNull)
          }
          else -> null
        }
      }.toMap()
    }.getOrDefault(emptyMap())
  }

  private fun saveJournal(vibeDir: Path, journal: Map<String, JournalEntry>) {
    runCatching {
      Files.writeString(vibeDir.resolve(JOURNAL), buildJsonObject {
        put("version", 2)
        put("files", buildJsonObject {
          journal.toSortedMap().forEach { (path, e) ->
            put(path, buildJsonObject {
              put("sha", e.sha)
              e.version?.let { put("rev", it) }
              e.reconciled?.let { put("reconciled", it) }
            })
          }
        })
      }.toString() + "\n")
    }
  }

  fun sha256(s: String): String = sha256(s.toByteArray(Charsets.UTF_8))

  fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
