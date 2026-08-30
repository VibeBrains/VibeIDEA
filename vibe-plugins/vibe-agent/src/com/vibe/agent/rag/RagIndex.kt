// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.rag

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.vibe.agent.providers.ProvidersService
import com.vibe.agent.settings.VibeAgentSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Search by MEANING over the project, built on the provider's own embeddings endpoint.
 *
 * Why this exists next to a text search and a code graph: the graph answers «что с чем связано»,
 * grep answers «где написано ровно это», and neither answers «где мы делаем то же самое другими
 * словами» — which is the question people actually have when they arrive in an unfamiliar project.
 *
 * Two decisions keep it honest. The index is INCREMENTAL by size and modification time, because
 * re-embedding a repository on every question costs real money. And it is stored per project in the
 * IDE system path, never in the repository: vectors are regenerable, huge and personal to whoever
 * paid for them.
 */
@Service(Service.Level.PROJECT)
class RagIndex(private val project: Project) {
  private val log = logger<RagIndex>()
  private val json = Json { ignoreUnknownKeys = true }
  private val http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

  @Volatile private var entries: MutableList<VectorIndex.Entry> = ArrayList()
  @Volatile private var fingerprints: MutableMap<String, String> = HashMap()
  @Volatile private var loaded = false

  data class Progress(val indexed: Int, val total: Int, val skipped: Int)

  /** Embeds the project, skipping files whose fingerprint has not changed. */
  fun rebuild(onProgress: (Progress) -> Unit): Result<Progress> = runCatching {
    ensureLoaded()
    val base = project.basePath ?: error(NO_PROJECT)
    val ignore = com.vibe.agent.context.ProjectContextService.getInstance(project).ignore()
    val root = Path.of(base)
    val files = Files.walk(root).use { stream ->
      stream.filter { Files.isRegularFile(it) }.toList()
    }.mapNotNull { path ->
      val relative = root.relativize(path).toString().replace('\\', '/')
      if (!Chunker.isIndexable(relative) || ignore.isIgnored(relative)) null else relative to path
    }

    var indexed = 0
    var skipped = 0
    val fresh = ArrayList<VectorIndex.Entry>()
    val newFingerprints = HashMap<String, String>()
    for ((relative, path) in files) {
      val fingerprint = fingerprintOf(path)
      newFingerprints[relative] = fingerprint
      if (fingerprints[relative] == fingerprint) {
        // Unchanged file: its vectors are already good, and re-embedding them is money for nothing.
        fresh.addAll(entries.filter { it.chunk.path == relative })
        skipped++
        continue
      }
      val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
      val chunks = Chunker.chunk(relative, text)
      if (chunks.isEmpty()) continue
      val vectors = embed(chunks.map { it.text })
      chunks.forEachIndexed { index, chunk ->
        vectors.getOrNull(index)?.let { fresh.add(VectorIndex.Entry(chunk, it)) }
      }
      indexed++
      onProgress(Progress(indexed, files.size, skipped))
    }
    entries = fresh
    fingerprints = newFingerprints
    save()
    Progress(indexed, files.size, skipped)
  }

  fun search(query: String, limit: Int = VectorIndex.DEFAULT_LIMIT): Result<List<VectorIndex.Hit>> = runCatching {
    ensureLoaded()
    if (entries.isEmpty()) error(NOT_INDEXED)
    val vector = embed(listOf(query)).firstOrNull() ?: error(NO_EMBEDDING)
    VectorIndex.spreadAcrossFiles(VectorIndex.search(entries, vector, limit * 2)).take(limit)
  }

  fun size(): Int {
    ensureLoaded()
    return entries.size
  }

  // --- embeddings ---

  private fun embed(texts: List<String>): List<FloatArray> {
    if (texts.isEmpty()) return emptyList()
    val spec = VibeAgentSettings.embeddingModel.trim()
    check(spec.isNotEmpty()) { NOT_CONFIGURED }
    val providerId = spec.substringBefore('/')
    val modelId = spec.substringAfter('/')
    val provider = ProvidersService.load(project.basePath) { }.firstOrNull { it.id == providerId }
      ?: error(NO_PROVIDER)
    val resolved = ProvidersService.resolve(provider, project.basePath) { } ?: error(NO_PROVIDER)
    val body = buildJsonObject {
      put("model", modelId)
      put("input", JsonArray(texts.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
    val builder = HttpRequest.newBuilder(URI.create(resolved.baseUrl.trimEnd('/') + "/embeddings"))
      .timeout(Duration.ofSeconds(60))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
    resolved.apiKey?.let { builder.header("Authorization", "Bearer " + it) }
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) { "HTTP " + response.statusCode() + ": " + response.body().take(200) }
    return parseEmbeddings(response.body())
  }

  /** `{"data":[{"embedding":[…]}]}` — the openai shape every provider copies. */
  fun parseEmbeddings(body: String): List<FloatArray> {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
    val data = root["data"] as? JsonArray ?: return emptyList()
    return data.mapNotNull { element ->
      val vector = (element as? JsonObject)?.get("embedding") as? JsonArray ?: return@mapNotNull null
      FloatArray(vector.size) { i -> vector[i].jsonPrimitive.floatOrNull ?: 0f }
    }
  }

  // --- storage ---

  private fun fingerprintOf(path: Path): String =
    Files.size(path).toString() + ":" + Files.getLastModifiedTime(path).toMillis()

  private fun file(): Path =
    Path.of(PathManager.getSystemPath(), "vibe", "rag", (project.locationHash) + ".json")

  @Synchronized
  private fun ensureLoaded() {
    if (loaded) return
    loaded = true
    val path = file()
    if (!Files.exists(path)) return
    runCatching {
      val root = json.parseToJsonElement(Files.readString(path)).jsonObject
      val loadedEntries = ArrayList<VectorIndex.Entry>()
      (root["chunks"] as? JsonArray)?.forEach { element ->
        val entry = element as? JsonObject ?: return@forEach
        val chunk = Chunker.Chunk(
          path = entry["path"]?.jsonPrimitive?.contentOrNull ?: return@forEach,
          fromLine = entry["from"]?.jsonPrimitive?.intOrNull ?: 1,
          toLine = entry["to"]?.jsonPrimitive?.intOrNull ?: 1,
          text = entry["text"]?.jsonPrimitive?.contentOrNull ?: "",
        )
        val vector = entry["vector"] as? JsonArray ?: return@forEach
        loadedEntries.add(VectorIndex.Entry(chunk, FloatArray(vector.size) { i -> vector[i].jsonPrimitive.floatOrNull ?: 0f }))
      }
      entries = loadedEntries
      fingerprints = HashMap((root["files"] as? JsonObject)?.mapValues { (_, v) -> v.jsonPrimitive.content }.orEmpty())
    }.onFailure { log.warn("rag index could not be read: " + it.message) }
  }

  @Synchronized
  private fun save() {
    runCatching {
      val path = file()
      Files.createDirectories(path.parent)
      val root = buildJsonObject {
        put("version", VERSION)
        put("files", JsonObject(fingerprints.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) }))
        put("chunks", JsonArray(entries.map { entry ->
          buildJsonObject {
            put("path", entry.chunk.path)
            put("from", entry.chunk.fromLine)
            put("to", entry.chunk.toLine)
            put("text", entry.chunk.text)
            put("vector", JsonArray(entry.vector.map { kotlinx.serialization.json.JsonPrimitive(it) }))
          }
        }))
      }
      Files.writeString(path, root.toString())
    }.onFailure { log.warn("rag index could not be written: " + it.message) }
  }

  companion object {
    private const val VERSION = 1
    const val NOT_CONFIGURED = "not-configured"
    const val NOT_INDEXED = "not-indexed"
    const val NO_PROVIDER = "no-provider"
    const val NO_EMBEDDING = "no-embedding"
    const val NO_PROJECT = "no-project"

    fun getInstance(project: Project): RagIndex = project.getService(RagIndex::class.java)
  }
}
