// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * The narrow, safe half of conflict resolution: when the ONLY thing the user changed in a seeded
 * provider file is which providers are switched on, their decision can be preserved without
 * keeping the stale file — it moves to `providers/zz-local-toggles.jsonc`, which sorts last in
 * the catalog and therefore overrides the seeds by id. The seeded file then goes back to being
 * release-owned and updates silently forever after.
 *
 * The toggles file is OURS (never seeded, rewritten wholesale) — deliberately not the user's
 * `providers.json`: rewriting that one would risk dropping fields we do not model.
 *
 * Anything beyond `active` (a changed baseURL, an added model, a rewritten comment) returns null:
 * that is a real edit and only a human can merge it.
 */
object SeedToggleMerge {
  /**
   * Providers whose `active` the user flipped, or null when the files differ in anything else.
   * An empty map means «structurally identical» (comments/formatting only) — refreshing is safe.
   */
  fun toggleDiff(release: String?, local: String?): Map<String, Boolean>? {
    if (release == null || local == null) return null
    val releaseEntries = runCatching { ProvidersFile.parse(release) { } }.getOrNull() ?: return null
    val localEntries = runCatching { ProvidersFile.parse(local) { } }.getOrNull() ?: return null
    if (releaseEntries.size != localEntries.size) return null
    val localById = localEntries.associateBy { it.id }
    if (localById.size != localEntries.size) return null // duplicate ids — do not guess
    val flipped = HashMap<String, Boolean>()
    for (r in releaseEntries) {
      val l = localById[r.id] ?: return null
      if (!sameExceptActive(r, l)) return null
      if (r.active != l.active) flipped[r.id] = l.active
    }
    return flipped
  }

  /** Every field but the provider's own `active` must match — models included, byte for byte. */
  private fun sameExceptActive(a: ProviderEntry, b: ProviderEntry): Boolean =
    a.copy(active = true, origin = null) == b.copy(active = true, origin = null)

  /**
   * Refresh the seeded file to the release revision and carry the user's off-switches into the
   * project overlay. Returns false when the merge is not applicable or IO fails — the caller then
   * leaves everything as it was.
   */
  fun apply(vibeDir: Path, relative: String): Boolean {
    val release = VibeDefaults.releaseContent(relative) ?: return false
    val local = runCatching { Files.readString(vibeDir.resolve(relative)) }.getOrNull() ?: return false
    val flipped = toggleDiff(release, local) ?: return false
    return runCatching {
      if (flipped.isNotEmpty()) writeLocalToggles(vibeDir, flipped)
      Files.writeString(vibeDir.resolve(relative), release)
      true
    }.getOrDefault(false)
  }

  /** Where preserved decisions live: last file of the catalog, so it wins over every seed. */
  internal const val TOGGLES_FILE = "providers/zz-local-toggles.jsonc"

  /** Merges `{id, active}` into our toggles file, keeping decisions recorded there earlier. */
  private fun writeLocalToggles(vibeDir: Path, flipped: Map<String, Boolean>) {
    val path = vibeDir.resolve(TOGGLES_FILE)
    val decisions = LinkedHashMap<String, Boolean>()
    runCatching { Files.readString(path) }.getOrNull()?.let { text ->
      runCatching { ProvidersFile.parse(text) { } }.getOrNull()?.forEach { decisions[it.id] = it.active }
    }
    decisions.putAll(flipped)
    val body = decisions.entries.joinToString(",\n") { (id, active) ->
      "    { \"id\": ${jsonString(id)}, \"active\": $active }"
    }
    val header = """
      |// Ваши тумблеры провайдеров, сохранённые при обновлении окружения из релиза.
      |// Файл сортируется последним в каталоге, поэтому перекрывает засеянные конфиги по id.
      |// Это ВАШ файл: правьте и удаляйте свободно — релиз его не трогает.
      |""".trimMargin()
    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(path, header + "{\n  \"version\": 1,\n  \"providers\": [\n" + body + "\n  ]\n}\n")
  }

  private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
