// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.vibe.agent.graph.CodeGraphIndex
import com.vibe.agent.graph.CodeGraphRefresh
import com.vibe.agent.http.VibeAgentGateway
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The IDE side of the MCP tools: everything [McpServer] deliberately does not know.
 *
 * The project is the most recently opened one, the same rule the HTTP API already uses for a run
 * without a session — a caller from outside cannot know which window is in front, and inventing a
 * choice it did not make would be worse than a stated one.
 */
class VibeMcpTools(private val projectProvider: () -> Project? = { ProjectManager.getInstance().openProjects.lastOrNull() }) :
  McpServer.Tools {

  override fun call(name: String, arguments: JsonObject): McpServer.Tools.Result {
    val project = projectProvider()
      ?: return McpServer.Tools.Result("В IDE нет открытого проекта", isError = true)
    return when (name) {
      McpProtocol.TOOL_IMPORTERS -> edges(project, arguments, importers = true)
      McpProtocol.TOOL_IMPORTS -> edges(project, arguments, importers = false)
      McpProtocol.TOOL_PATH -> path(project, arguments)
      McpProtocol.TOOL_PROJECT -> projectInfo(project)
      McpProtocol.TOOL_RUN -> run(arguments)
      else -> McpServer.Tools.Result("неизвестный инструмент: $name", isError = true)
    }
  }

  private fun string(arguments: JsonObject, key: String): String? =
    arguments[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

  private fun graph(project: Project): CodeGraphIndex.Graph? = CodeGraphRefresh.refresh(project)?.graph

  /**
   * Provenance is carried into the answer, not flattened away: an import matched against a declared
   * qualified name is a fact, one matched only by a last segment is a guess, and an agent handed a
   * mixed pile treats both as read.
   */
  private fun edges(project: Project, arguments: JsonObject, importers: Boolean): McpServer.Tools.Result {
    val path = string(arguments, "path") ?: return McpServer.Tools.Result("нужен аргумент path", isError = true)
    val graph = graph(project) ?: return McpServer.Tools.Result("не удалось построить граф проекта", isError = true)
    val found = if (importers) graph.importersOf(path) else graph.importsOf(path)
    if (found.isEmpty()) {
      val known = graph.nodes.any { it.path == path }
      // «File not in the graph» and «file is there, no edges» are different news, and one answer
      // for both lies about each of them.
      return McpServer.Tools.Result(
        if (known) "Связей не найдено: $path"
        else "Файла нет в графе проекта: $path (в графе ${graph.nodes.size} файлов)"
      )
    }
    val lines = found.map { edge ->
      val other = if (importers) edge.from else edge.to
      val mark = if (edge.provenance == CodeGraphIndex.Provenance.FACT) "факт" else "догадка"
      "$other  [$mark]  ${edge.symbol}"
    }
    return McpServer.Tools.Result(lines.joinToString("\n"))
  }

  private fun path(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val from = string(arguments, "from") ?: return McpServer.Tools.Result("нужен аргумент from", isError = true)
    val to = string(arguments, "to") ?: return McpServer.Tools.Result("нужен аргумент to", isError = true)
    val graph = graph(project) ?: return McpServer.Tools.Result("не удалось построить граф проекта", isError = true)
    val chain = graph.path(from, to)
    // No path is an answer, not a failure: «they are not connected» is what was asked.
    return McpServer.Tools.Result(if (chain.isEmpty()) "Пути между файлами нет" else chain.joinToString("\n → "))
  }

  private fun projectInfo(project: Project): McpServer.Tools.Result {
    val graph = graph(project)
    val facts = graph?.edges?.count { it.provenance == CodeGraphIndex.Provenance.FACT } ?: 0
    return McpServer.Tools.Result(
      buildString {
        appendLine("Проект: ${project.name}")
        appendLine("Корень: ${project.basePath.orEmpty()}")
        if (graph == null) append("Граф импортов: построить не удалось")
        else append("Граф импортов: ${graph.nodes.size} файлов, ${graph.edges.size} рёбер (${facts} фактов)")
      }
    )
  }

  private fun run(arguments: JsonObject): McpServer.Tools.Result {
    val task = string(arguments, "task") ?: return McpServer.Tools.Result("нужен аргумент task", isError = true)
    // Never waits for the turn to finish: an MCP tool hanging for minutes reads as stuck and the
    // client drops the connection, while the turn keeps running with nobody left to receive it.
    return runCatching { VibeAgentGateway.getInstance().run(task, null, false) }
      .fold(
        onSuccess = { McpServer.Tools.Result("Задача передана агенту, сессия: $it") },
        onFailure = { McpServer.Tools.Result(it.message ?: "не удалось передать задачу", isError = true) },
      )
  }
}
