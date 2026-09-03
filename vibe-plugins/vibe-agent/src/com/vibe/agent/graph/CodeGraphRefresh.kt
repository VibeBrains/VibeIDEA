// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * Brings `.vibe/codeGraph.json` up to date and returns the graph it now describes.
 *
 * Extracted from the export action because a second caller appeared: the MCP tools answer graph
 * questions for outside agents, and an answer of «сначала нажмите Tools → экспорт графа» is not an
 * answer — the agent asking has no hands. Both callers must refresh the same way, or the file an
 * agent reads and the file a person exports drift apart.
 *
 * Incremental by fingerprint: a repository that changed by three files must not cost a full parse
 * of five thousand.
 */
object CodeGraphRefresh {
  data class Result(val graph: CodeGraphIndex.Graph, val files: Int, val parsed: Int, val file: Path)

  /**
   * [onProgress] is told how much will be parsed before the parsing starts: `stale` of `total`
   * files, and whether this is the first run. The caller with a progress bar says it out loud —
   * a refresh that takes minutes in silence reads as a hang.
   */
  fun refresh(project: Project, onProgress: (stale: Int, total: Int, firstRun: Boolean) -> Unit = { _, _, _ -> }): Result? {
    val base = project.basePath ?: return null
    val out = Path.of(base, ".vibe", "codeGraph.json")
    val current = CodeGraphBuilder.scan(project)
    val previous = runCatching { if (Files.exists(out)) CodeGraphStore.decode(Files.readString(out)) else emptyList() }
      .getOrDefault(emptyList())
    val (reused, stale) = CodeGraphStore.plan(current, previous)
    onProgress(stale.size, current.size, previous.isEmpty())
    val parsed = CodeGraphBuilder.buildSome(project, stale)
    val nodes = (reused + parsed).sortedBy { it.path }
    val stored = nodes.mapNotNull { node -> current[node.path]?.let { CodeGraphStore.StoredNode(node, it) } }
    val graph = CodeGraphIndex.build(nodes)
    Files.createDirectories(out.parent)
    Files.writeString(out, CodeGraphStore.encode(stored, graph))
    return Result(graph = graph, files = nodes.size, parsed = parsed.size, file = out)
  }
}
