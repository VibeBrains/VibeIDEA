// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.i18n.VibeI18n.t

import java.nio.file.Files
import java.nio.file.Path

data class ResolvedProvider(
  val entry: ProviderEntry,
  val protocol: String,
  val baseUrl: String,
  val apiKey: String?,
  val isLocal: Boolean,
)

/**
 * Loads and merges the provider registry from four layers, weakest first:
 * seeded catalog (`*.jsonc` files of the `providers` dir; global, then project;
 * alphabetical within a dir, a later file overrides an earlier one by id) →
 * global `providers.json` → project `providers.json`. The catalog plays the role
 * VibeIDE gave to built-in providers, so like built-ins it stays UNDER both user
 * files — a seeded entry can never silence or rewrite a user's `providers.json`
 * (review of decision №24). Same-id entries patch field-by-field; every file is
 * parsed independently — one broken file never disables providers from the others.
 * `extends` resolves once, over the fully merged registry; `active` is filtered
 * last, so `extends`/patches work against inactive catalog entries. Locality is
 * determined by the endpoint host, not by a hardcoded vendor list.
 */
object ProvidersService {
  fun load(projectBase: String?, onWarning: (String) -> Unit): List<ProviderEntry> =
    loadFrom(
      globalVibeDir = Path.of(System.getProperty("user.home"), ".vibe"),
      projectVibeDir = projectBase?.let { Path.of(it, ".vibe") },
      onWarning = onWarning,
    )

  /** Same as [load], with explicit scope directories — the seam unit tests drive. */
  fun loadFrom(globalVibeDir: Path, projectVibeDir: Path?, onWarning: (String) -> Unit): List<ProviderEntry> {
    val globalCatalog = loadCatalog(globalVibeDir, onWarning)
    val projectCatalog = projectVibeDir?.let { loadCatalog(it, onWarning) } ?: emptyList()
    val globalJson = loadFile(globalVibeDir.resolve("providers.json"), "providers.json", onWarning)
    val projectJson = projectVibeDir?.let { loadFile(it.resolve("providers.json"), "providers.json", onWarning) }
                      ?: emptyList()
    val merged = ProvidersFile.merge(
      ProvidersFile.merge(ProvidersFile.merge(globalCatalog, projectCatalog), globalJson),
      projectJson,
    )
    // Origin describes which scope contributed to the entry (for the settings hint).
    val globalIds = HashSet<String>().apply { globalCatalog.mapTo(this) { it.id }; globalJson.mapTo(this) { it.id } }
    val projectIds = HashSet<String>().apply { projectCatalog.mapTo(this) { it.id }; projectJson.mapTo(this) { it.id } }
    return ProvidersFile.resolveExtends(merged, onWarning).filter { it.active }.map {
      it.copy(origin = when {
        it.id in projectIds && it.id in globalIds -> ProviderOrigin.OVERRIDDEN
        it.id in projectIds -> ProviderOrigin.PROJECT
        else -> ProviderOrigin.GLOBAL
      })
    }
  }

  private fun loadCatalog(vibeDir: Path, onWarning: (String) -> Unit): List<ProviderEntry> {
    var acc = emptyList<ProviderEntry>()
    for (file in catalogFiles(vibeDir.resolve("providers"), onWarning)) {
      acc = ProvidersFile.merge(acc, loadFile(file, "providers/${file.fileName}", onWarning))
    }
    return acc
  }

  private fun catalogFiles(dir: Path, onWarning: (String) -> Unit): List<Path> {
    if (!Files.isDirectory(dir)) return emptyList()
    return runCatching {
      Files.newDirectoryStream(dir).use { stream ->
        stream.filter { p ->
          val name = p.fileName.toString()
          Files.isRegularFile(p) && (name.endsWith(".jsonc") || name.endsWith(".json"))
        }.sortedBy { it.fileName.toString() }
      }
    }.getOrElse { e ->
      onWarning(t("providers.warn.dirUnreadable", "dir" to dir, "reason" to e.message))
      emptyList()
    }
  }

  private fun loadFile(path: Path, source: String, onWarning: (String) -> Unit): List<ProviderEntry> {
    if (!Files.isRegularFile(path)) return emptyList()
    return try {
      ProvidersFile.parse(Files.readString(path), source = source, onWarning = onWarning)
    }
    catch (e: Exception) {
      onWarning(t("providers.warn.fileUnparsed", "path" to path, "reason" to e.message))
      emptyList()
    }
  }

  fun resolve(entry: ProviderEntry, projectBase: String?, onWarning: (String) -> Unit): ResolvedProvider? {
    val base = entry.baseURL
    if (base.isNullOrBlank()) {
      onWarning(t("providers.warn.noBaseUrl", "id" to entry.id))
      return null
    }
    val protocol = when (entry.protocol) {
      "anthropic" -> "anthropic"
      "gemini" -> "gemini"
      else -> "openai"
    }
    val host = runCatching { java.net.URI(base).host }.getOrNull() ?: ""
    val key = ApiKeyResolver.resolve(entry, projectBase)
    return ResolvedProvider(
      entry = entry,
      protocol = protocol,
      baseUrl = base,
      apiKey = key,
      isLocal = host == "localhost" || host == "127.0.0.1" || host == "::1",
    )
  }
}
