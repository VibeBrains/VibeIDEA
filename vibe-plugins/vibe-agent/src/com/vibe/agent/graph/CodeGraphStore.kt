// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Reading and writing `.vibe/codeGraph.json`, and the rule that makes a rebuild cheap.
 *
 * A full export parses up to five thousand files; on a repository that has changed by three of
 * them, that is minutes spent to learn nothing new. Each node therefore carries a fingerprint
 * (size and modification time), and an unchanged file is taken from the previous graph instead of
 * being parsed again. Deliberately size+mtime rather than a content hash: hashing every file costs
 * the very reading the fingerprint exists to avoid.
 *
 * Pure — the caller supplies fingerprints and does the IO.
 */
object CodeGraphStore {
  const val FORMAT_VERSION = 2

  data class Fingerprint(val size: Long, val modifiedAtMs: Long)

  data class StoredNode(val node: GraphNode, val fingerprint: Fingerprint)

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Splits the current files into "reuse the parsed node" and "parse again".
   *
   * A file missing from the previous graph, changed in size or touched later is parsed; everything
   * else is carried over. Files that disappeared simply do not appear in the result — an export
   * must not resurrect deleted code.
   */
  fun plan(current: Map<String, Fingerprint>, previous: List<StoredNode>): Pair<List<GraphNode>, List<String>> {
    val known = previous.associateBy { it.node.path }
    val reused = ArrayList<GraphNode>()
    val stale = ArrayList<String>()
    for ((path, fingerprint) in current) {
      val old = known[path]
      if (old != null && old.fingerprint == fingerprint) reused.add(old.node) else stale.add(path)
    }
    return reused to stale
  }

  fun encode(stored: List<StoredNode>, graph: CodeGraphIndex.Graph): String {
    val importers = stored.associate { it.node.path to graph.importersOf(it.node.path) }
    return buildJsonObject {
      put("version", FORMAT_VERSION)
      put("generatedBy", "VibeIDEA code_graph: файлы, символы, импорты (UAST), TODO, обратные рёбра с происхождением")
      put("nodes", JsonArray(stored.map { item ->
        val n = item.node
        buildJsonObject {
          put("path", n.path)
          put("size", item.fingerprint.size)
          put("modifiedAt", item.fingerprint.modifiedAtMs)
          if (n.symbols.isNotEmpty()) put("symbols", JsonArray(n.symbols.map { JsonPrimitive(it) }))
          if (n.imports.isNotEmpty()) put("imports", JsonArray(n.imports.map { JsonPrimitive(it) }))
          if (n.todos.isNotEmpty()) put("todos", JsonArray(n.todos.map { JsonPrimitive(it) }))
          val incoming = importers[n.path].orEmpty()
          if (incoming.isNotEmpty()) {
            put("importers", JsonArray(incoming.map { edge ->
              buildJsonObject {
                put("path", edge.from)
                put("symbol", edge.symbol)
                // The mark an agent needs to tell what was read from what was inferred.
                put("provenance", if (edge.provenance == CodeGraphIndex.Provenance.FACT) "факт" else "догадка")
              }
            }))
          }
        }
      }))
      put("edges", JsonArray(graph.edges.map { edge ->
        buildJsonObject {
          put("from", edge.from)
          put("to", edge.to)
          put("symbol", edge.symbol)
          put("provenance", if (edge.provenance == CodeGraphIndex.Provenance.FACT) "факт" else "догадка")
        }
      }))
    }.toString()
  }

  /** Reads a previous export; a file of an older format simply yields nothing to reuse. */
  fun decode(text: String): List<StoredNode> {
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
    if (root["version"]?.jsonPrimitive?.longOrNull != FORMAT_VERSION.toLong()) return emptyList()
    val nodes = runCatching { root["nodes"]!!.jsonArray }.getOrNull() ?: return emptyList()
    return nodes.mapNotNull { element ->
      val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
      val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      val size = obj["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
      val modified = obj["modifiedAt"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
      StoredNode(
        GraphNode(
          path = path,
          symbols = obj.strings("symbols"),
          imports = obj.strings("imports"),
          todos = obj.strings("todos"),
        ),
        Fingerprint(size, modified),
      )
    }
  }

  private fun JsonObject.strings(key: String): List<String> =
    runCatching { this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()
}
