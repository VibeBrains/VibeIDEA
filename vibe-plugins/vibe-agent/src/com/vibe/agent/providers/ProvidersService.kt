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
  /** The address is this machine ([LocalAddress]): a question about the network — the key, «not running» */
  val localAddress: Boolean,
) {
  /**
   * The models run on this machine: the entry's `runsLocally`, the address when it says nothing
   * Everything about the model reads this — the offline mode, local budgets, the privacy label
   * Apart from [localAddress] on purpose: a localhost proxy to a cloud model is local by address and remote by model
   */
  val runsLocally: Boolean get() = entry.runsLocally ?: localAddress

  /**
   * The server needs a key: an address elsewhere without `"auth": "none"`
   * The address decides and not [runsLocally]: the key is a question of who is asked, not of where the model runs
   */
  val needsKey: Boolean get() = !localAddress && entry.auth.type != AuthSpec.NONE

  /**
   * No key where one is needed: asking would earn a predictable 401, so callers skip the provider
   * A local endpoint passes undeclared; `"auth": "none"` passes on any address — a vLLM in the local network included
   */
  val missingKey: Boolean get() = apiKey == null && needsKey
}

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
 * determined by the endpoint host, not by a hardcoded vendor list; whether the
 * models run here, an entry may declare with `runsLocally` ([ResolvedProvider]).
 */
object ProvidersService {
  fun load(projectBase: String?, onWarning: (String) -> Unit): List<ProviderEntry> {
    val globalVibeDir = Path.of(System.getProperty("user.home"), ".vibe")
    val projectVibeDir = projectBase?.let { Path.of(it, ".vibe") }
    // Read together with the registry: both answer the same question — how a request to this model
    // must be built — and loading them apart is how the two drift into disagreeing.
    ModelQuirksRegistry.install(projectBase, loadQuirks(globalVibeDir, projectVibeDir, onWarning))
    // Logical names come from the same files in the same layer order as the providers ([layers]):
    // A seeded name never outranks a user's file, and a declared null closes the name for good ([ModelRoutes])
    ModelRoutesRegistry.install(projectBase, loadRoutes(globalVibeDir, projectVibeDir, onWarning))
    return loadFrom(globalVibeDir, projectVibeDir, onWarning)
  }

  /** Same as [load], with explicit scope directories — the seam unit tests drive. */
  fun loadFrom(globalVibeDir: Path, projectVibeDir: Path?, onWarning: (String) -> Unit): List<ProviderEntry> {
    val (globalCatalog, projectCatalog, globalJson, projectJson) = layers(globalVibeDir, projectVibeDir, onWarning).map { layer ->
      layer.fold(emptyList<ProviderEntry>()) { acc, file -> ProvidersFile.merge(acc, loadFile(file.path, file.source, onWarning)) }
    }
    val merged = ProvidersFile.merge(
      ProvidersFile.merge(ProvidersFile.merge(globalCatalog, projectCatalog), globalJson),
      projectJson,
    )
    // Origin describes which scope contributed to the entry (for the settings hint).
    val globalIds = HashSet<String>().apply { globalCatalog.mapTo(this) { it.id }; globalJson.mapTo(this) { it.id } }
    val projectIds = HashSet<String>().apply { projectCatalog.mapTo(this) { it.id }; projectJson.mapTo(this) { it.id } }
    val resolved = ProvidersFile.resolveExtends(merged, onWarning)
    // Checked after the merge: the contradiction usually spans layers, a seeded `none` under a user's own key variable
    resolved.filter { it.active && it.auth.type == AuthSpec.NONE && (it.apiKeyEnv != null || it.apiKeyRef != null) }.forEach {
      onWarning(t("providers.warn.authNoneWithKey", "id" to it.id, "sources" to ApiKeyResolver.sourceNames(it)))
    }
    // A written bearer is sent as written ([ProviderAuth]), and Gemini answers a Bearer carrying an API key with an error
    // The one combination the rule makes certainly wrong is named at load rather than at the first request
    resolved.filter { it.active && it.declaredAuth?.type == AuthSpec.BEARER && speaksGemini(it) }.forEach {
      onWarning(t("providers.warn.bearerOnGemini", "id" to it.id))
    }
    return resolved.filter { it.active }.map {
      it.copy(origin = when {
        it.id in projectIds && it.id in globalIds -> ProviderOrigin.OVERRIDDEN
        it.id in projectIds -> ProviderOrigin.PROJECT
        else -> ProviderOrigin.GLOBAL
      })
    }
  }

  private fun speaksGemini(entry: ProviderEntry): Boolean =
    protocolFor(entry.protocol) == "gemini" || entry.models.any { it.protocol != null && protocolFor(entry.protocol, it.protocol) == "gemini" }

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

  /** Таблицы `routes` всех файлов обоих слоёв, сложенные в порядке чтения. */
  internal fun loadRoutes(globalVibeDir: Path, projectVibeDir: Path?, onWarning: (String) -> Unit): Map<String, String?> =
    ModelRoutes.merge(layers(globalVibeDir, projectVibeDir, onWarning).flatten().map { routesOf(it.path, it.source, onWarning) })

  /** One file of the registry and the name it is warned under */
  private data class LayerFile(val path: Path, val source: String)

  /**
   * The files of the registry by layer, weakest first: global catalog, project catalog, global and project providers.json
   * Inside a catalog the files go by name, so a later file overrides an earlier one
   * One list for the providers and for their routes: two orders of the same files let a seeded route outrank a user's file
   */
  private fun layers(globalVibeDir: Path, projectVibeDir: Path?, onWarning: (String) -> Unit): List<List<LayerFile>> {
    fun catalog(dir: Path?): List<LayerFile> =
      dir?.let { catalogFiles(it.resolve(CATALOG_DIR), onWarning) }.orEmpty().map { LayerFile(it, "$CATALOG_DIR/${it.fileName}") }
    fun userFile(dir: Path?): List<LayerFile> = listOfNotNull(dir?.let { LayerFile(it.resolve(USER_FILE), USER_FILE) })
    return listOf(catalog(globalVibeDir), catalog(projectVibeDir), userFile(globalVibeDir), userFile(projectVibeDir))
  }

  private const val CATALOG_DIR = "providers"
  private const val USER_FILE = "providers.json"

  private fun routesOf(path: Path, source: String, onWarning: (String) -> Unit): Map<String, String?> {
    if (!Files.isRegularFile(path)) return emptyMap()
    return runCatching { ProvidersFile.parseRoutes(Files.readString(path), source, onWarning) }.getOrDefault(emptyMap())
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
    (modelProtocol ?: providerProtocol)?.takeIf { it in PROTOCOLS } ?: ModelQuirks.WIRE_OPENAI

  /** Every wire a request can speak; the schema of providers.json offers exactly these, and a test holds them together */
  val PROTOCOLS: List<String> = listOf(ModelQuirks.WIRE_OPENAI, ModelQuirks.WIRE_OPENAI_RESPONSES, ModelQuirks.WIRE_ANTHROPIC, "gemini")

  /**
   * @param quiet фоновый вызов: ключ берётся без обращения к связке ключей ([ApiKeyResolver.resolveQuietly]),
   *   и провайдер без известного ключа возвращается с `apiKey = null` — фон его пропустит, а диалога
   *   с паролем человек не увидит. Настоящий запрос человека идёт с `quiet = false`.
   */
  fun resolve(entry: ProviderEntry, projectBase: String?, quiet: Boolean = false, onWarning: (String) -> Unit): ResolvedProvider? {
    val base = entry.baseURL
    if (base.isNullOrBlank()) {
      onWarning(t("providers.warn.noBaseUrl", "id" to entry.id))
      return null
    }
    val protocol = protocolFor(entry.protocol)
    val key = if (quiet) ApiKeyResolver.resolveQuietly(entry, projectBase) else ApiKeyResolver.resolve(entry, projectBase)
    return ResolvedProvider(
      entry = entry,
      protocol = protocol,
      baseUrl = base,
      apiKey = key,
      localAddress = LocalAddress.isLocal(base),
    )
  }
}
