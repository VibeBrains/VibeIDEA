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
      McpProtocol.TOOL_CORPUS_SEARCH -> corpusSearch(project, arguments)
      McpProtocol.TOOL_SYMBOL_USAGES -> symbolUsages(project, arguments)
      McpProtocol.TOOL_DECISIONS_SEARCH -> decisionsSearch(project, arguments)
      McpProtocol.TOOL_DECISIONS_RECORD -> decisionsRecord(project, arguments)
      else -> McpServer.Tools.Result("неизвестный инструмент: $name", isError = true)
    }
  }

  /**
   * Решения по теме — путями и вопросами, не текстом.
   *
   * Тем же поиском, что и у библиотекаря: два ранжирования одного корпуса разошлись бы, и агент
   * получал бы разные ответы на один вопрос в зависимости от того, каким путём спросил.
   */
  /**
   * Весь корпус одним запросом.
   *
   * Три источника (знания, решения, входящее) живут в разных папках, но вопрос у агента один:
   * «что здесь уже записано по теме». Три отдельных инструмента заставляли бы его звать их по
   * очереди и склеивать ответы — то есть делать нашу работу за наши же токены.
   *
   * Пометка источника обязательна: запись знаний говорит, как устроено, решение — что уже
   * отвергнуто, документ — чужой текст, который мы не писали. Смешать их значит потерять
   * единственное, чем они различаются.
   */
  private fun corpusSearch(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val query = string(arguments, "query") ?: return McpServer.Tools.Result("нужен аргумент query", isError = true)
    val sources = listOf(
      "знание" to com.vibe.agent.knowledge.KnowledgeIndex.getInstance(project).entries(),
      "решение" to com.vibe.agent.decisions.DecisionStore.getInstance(project).entries(),
      "документ" to com.vibe.agent.ingest.IngestStore.getInstance(project).entries(),
    )
    val lines = sources.flatMap { (kind, entries) ->
      com.vibe.agent.knowledge.Librarian.find(entries, query).map { hit ->
        "- [$kind] ${hit.entry.path} — ${hit.entry.description}"
      }
    }
    if (lines.isEmpty()) return McpServer.Tools.Result("По этой теме в корпусе проекта ничего не записано")
    return McpServer.Tools.Result(lines.joinToString("\n"))
  }

  private fun decisionsSearch(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val query = string(arguments, "query") ?: return McpServer.Tools.Result("нужен аргумент query", isError = true)
    val entries = com.vibe.agent.decisions.DecisionStore.getInstance(project).entries()
    if (entries.isEmpty()) return McpServer.Tools.Result("В проекте нет журнала решений")
    val hits = com.vibe.agent.knowledge.Librarian.find(entries, query)
    if (hits.isEmpty()) return McpServer.Tools.Result("По этой теме решений не записано")
    return McpServer.Tools.Result(hits.joinToString("\n") { "- ${it.entry.path} — ${it.entry.description}" })
  }

  private fun decisionsRecord(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val question = string(arguments, "question") ?: return McpServer.Tools.Result("нужен аргумент question", isError = true)
    val chosen = string(arguments, "chosen") ?: return McpServer.Tools.Result("нужен аргумент chosen", isError = true)
    val why = string(arguments, "why") ?: return McpServer.Tools.Result("нужен аргумент why", isError = true)
    val store = com.vibe.agent.decisions.DecisionStore.getInstance(project)
    val decision = com.vibe.agent.decisions.DecisionRecord.Decision(
      number = store.nextNumber(),
      question = question,
      chosen = chosen,
      rejected = string(arguments, "rejected").orEmpty(),
      supersedes = arguments["supersedes"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull(),
      why = why,
      date = java.time.LocalDate.now().toString(),
    )
    val path = store.write(decision, com.vibe.agent.i18n.VibeI18n.t("decisions.index.header"))
      ?: return McpServer.Tools.Result("не удалось записать решение в ${store.folder()}", isError = true)
    return McpServer.Tools.Result("Решение ${decision.number} записано: $path")
  }

  /**
   * Где встречается имя — по индексу слов IDE.
   *
   * Ради этого инструмента граф импортов и существовал наполовину: он отвечает «какие файлы
   * связаны», но не «где именно живёт эта функция». Без такого ответа агент делает единственное,
   * что ему остаётся, — читает файлы целиком и тащит их в контекст каждого следующего хода; это и
   * есть та статья расхода, которая потом удивляет в счёте.
   *
   * Индекс слов честно не разбирает синтаксис: совпадение в комментарии он не отличает от вызова.
   * Это сказано в описании инструмента, потому что инструмент, обещающий «вызовы» и отдающий
   * совпадения, хуже инструмента, обещающего совпадения.
   */
  private fun symbolUsages(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val name = string(arguments, "name")?.trim()
    if (name.isNullOrEmpty()) return McpServer.Tools.Result("нужен аргумент name", isError = true)
    val limit = (arguments["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_USAGE_LIMIT)
      .coerceIn(1, MAX_USAGE_LIMIT)
    val base = project.basePath
    val found = ArrayList<String>()
    // TextOccurenceProcessor, а не Processor: индекс слов отдаёт СОВПАДЕНИЕ (элемент плюс смещение
    // внутри него), и его собственный интерфейс — единственный, который helper принимает.
    val processor = com.intellij.psi.search.TextOccurenceProcessor { element, _ ->
      val file = element.containingFile?.virtualFile ?: return@TextOccurenceProcessor true
      val document = com.intellij.psi.PsiDocumentManager.getInstance(project)
        .getDocument(element.containingFile) ?: return@TextOccurenceProcessor true
      val line = document.getLineNumber(element.textOffset)
      val text = document.getText(
        com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
      val path = base?.let { com.intellij.openapi.util.io.FileUtil.getRelativePath(it, file.path, '/') } ?: file.path
      found.add("$path:${line + 1}: ${text.trim().take(LINE_CHARS)}")
      found.size < limit
    }
    com.intellij.openapi.application.ReadAction.run<RuntimeException> {
      com.intellij.psi.search.PsiSearchHelper.getInstance(project).processElementsWithWord(
        processor,
        com.intellij.psi.search.GlobalSearchScope.projectScope(project),
        name,
        com.intellij.psi.search.UsageSearchContext.ANY,
        true,
      )
    }
    if (found.isEmpty()) return McpServer.Tools.Result("нигде не встречается: $name")
    return McpServer.Tools.Result(found.joinToString("\n"))
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

  private companion object {
    const val DEFAULT_USAGE_LIMIT = 50

    /** Потолок: ответ инструмента уходит в контекст модели, и «все совпадения» там не нужны никому. */
    const val MAX_USAGE_LIMIT = 200

    /** Длинная строка в ответе — это минифицированный файл; смысла в ней нет, а токены есть. */
    const val LINE_CHARS = 200
  }
}
