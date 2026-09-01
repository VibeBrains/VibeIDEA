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
  fun load(projectBase: String?, onWarning: (String) -> Unit): List<ProviderEntry> {
    val globalVibeDir = Path.of(System.getProperty("user.home"), ".vibe")
    val projectVibeDir = projectBase?.let { Path.of(it, ".vibe") }
    // Read together with the registry: both answer the same question — how a request to this model
    // must be built — and loading them apart is how the two drift into disagreeing.
    ModelQuirksRegistry.install(projectBase, loadQuirks(globalVibeDir, projectVibeDir, onWarning))
    return loadFrom(globalVibeDir, projectVibeDir, onWarning)
  }

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

  /**
   * Reads `.vibe/modelQuirks.json` of both scopes and installs it as the quirk catalogue.
   *
   * Project over global, and both over the built-in rules: a repository pinned to a model that
   * misbehaves carries the workaround with it, and the person who cloned it does not have to
   * rediscover the same 400.
   *
   * Called on load and on every watcher event, so an edit takes effect without an IDE restart.
   */
  fun loadQuirks(
    globalVibeDir: Path = Path.of(System.getProperty("user.home"), ".vibe"),
    projectVibeDir: Path? = null,
    onWarning: (String) -> Unit,
  ): List<ModelQuirks.Rule> {
    val global = readQuirks(globalVibeDir, onWarning)
    val project = projectVibeDir?.let { readQuirks(it, onWarning) } ?: emptyList()
    // Project first: the first matching rule wins, so the nearer file has the last word.
    return ModelQuirksFile.rules(project + global)
  }

  private fun readQuirks(vibeDir: Path, onWarning: (String) -> Unit): List<ModelQuirksFile.Entry> {
    val file = vibeDir.resolve(ProvidersWatchPaths.QUIRKS_FILE)
    if (!Files.isRegularFile(file)) return emptyList()
    val text = runCatching { Files.readString(file) }.getOrElse { e ->
      onWarning(t("quirks.warn.unreadable", "source" to file, "reason" to e.message))
      return emptyList()
    }
    return ModelQuirksFile.parse(text, file.toString(), onWarning)
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

  /**
   * The protocol a REQUEST to this model must speak: the model's own when it names one, the
   * provider's otherwise, and «openai» when neither names anything recognised.
   *
   * Unknown names fall back rather than fail: a provider file written for a newer IDE must not
   * take the whole registry down, and the failure of a wrong protocol is loud anyway — the very
   * first request answers with an error naming the endpoint.
   */
  fun protocolFor(providerProtocol: String?, modelProtocol: String? = null): String =
    when (modelProtocol ?: providerProtocol) {
      "anthropic" -> "anthropic"
      "gemini" -> "gemini"
      else -> "openai"
    }

  fun resolve(entry: ProviderEntry, projectBase: String?, onWarning: (String) -> Unit): ResolvedProvider? {
    val base = entry.baseURL
    if (base.isNullOrBlank()) {
      onWarning(t("providers.warn.noBaseUrl", "id" to entry.id))
      return null
    }
    val protocol = protocolFor(entry.protocol)
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
