// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.vibe.agent.context.AccessPolicy
import com.vibe.agent.context.AgentPaths
import com.vibe.agent.context.ProjectContextService
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
    val verdict = McpAccess.verdict(
      McpProtocol.riskOf(name),
      trusted = TrustedProjects.isProjectTrusted(project),
      allowWrite = com.vibe.agent.settings.VibeAgentSettings.mcpAllowWrite,
      allowExecute = com.vibe.agent.settings.VibeAgentSettings.mcpAllowExecute,
    )
    McpAccess.refusal(verdict)?.let { return McpServer.Tools.Result(it, isError = true) }
    return dispatch(project, name, arguments)
  }

  /**
   * Runs a tool with no access check of its own: the caller has already decided.
   *
   * The HTTP API checks the switches in [call]; the direct chat asks the person for every writing call and
   * does not depend on switches meant for outside clients.
   */
  fun dispatch(project: Project, name: String, arguments: JsonObject): McpServer.Tools.Result {
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
      McpProtocol.TOOL_OPEN_FILE -> openFile(project)
      McpProtocol.TOOL_READ_FILE -> readFile(project, arguments)
      McpProtocol.TOOL_IDE_INFO -> ideInfo(project)
      McpProtocol.TOOL_PROBLEMS -> problems(project, arguments)
      McpProtocol.TOOL_TRACE -> traceSymbol(project, arguments)
      McpProtocol.TOOL_DOCS_SEARCH -> docsSearch(arguments)
      McpProtocol.TOOL_WRITE_FILE -> writeFile(project, arguments)
      McpProtocol.TOOL_REPLACE_IN_FILE -> replaceInFile(project, arguments)
      McpProtocol.TOOL_RUN_COMMAND -> runCommand(project, arguments)
      McpProtocol.TOOL_COMMAND_OUTPUT -> commandOutput(project, arguments)
      McpProtocol.TOOL_COMMAND_STOP -> commandStop(project, arguments)
      McpProtocol.TOOL_TEXT_SLOP -> textSlop(project, arguments)
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
    val roots = ProjectContextService.getInstance(project).roots()
    // One verdict per file, not per occurrence: resolving a path touches the disk, and a common name
    // occurs hundreds of times in the same few files.
    val verdicts = HashMap<com.intellij.openapi.vfs.VirtualFile, Boolean>()
    val found = ArrayList<String>()
    // TextOccurenceProcessor, а не Processor: индекс слов отдаёт СОВПАДЕНИЕ (элемент плюс смещение
    // внутри него), и его собственный интерфейс — единственный, который helper принимает.
    val processor = com.intellij.psi.search.TextOccurenceProcessor { element, offsetInElement ->
      val file = element.containingFile?.virtualFile ?: return@TextOccurenceProcessor true
      // A line returned is a line read. A file that is not on the local disk has no path the rule
      // can speak for, and is skipped rather than guessed at.
      val readable = verdicts.getOrPut(file) {
        file.fileSystem.getNioPath(file)?.let { readable(it.toString(), roots) } == true
      }
      if (!readable) return@TextOccurenceProcessor true
      val document = com.intellij.psi.PsiDocumentManager.getInstance(project)
        .getDocument(element.containingFile) ?: return@TextOccurenceProcessor true
      // Смещение внутри элемента обязательно: комментарий или многострочный литерал — ОДИН
      // элемент, и без него совпадение из его середины уехало бы с номером первой строки, где
      // искомого имени нет вовсе. Инструмент прямо обещает совпадения в комментариях и строках.
      val line = document.getLineNumber(element.textRange.startOffset + offsetInElement)
      val text = document.getText(
        com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
      val path = base?.let { com.intellij.openapi.util.io.FileUtil.getRelativePath(it, file.path, '/') } ?: file.path
      found.add("$path:${line + 1}: ${text.trim().take(LINE_CHARS)}")
      found.size < limit
    }
    // Индекс слов существует не всегда: во время индексации поиск бросает IndexNotReadyException,
    // и агент получил бы вместо ответа текст исключения — вывод «инструмент сломан» он делает один
    // раз и навсегда. Состояние называется словами, а повтор предлагается явно.
    if (com.intellij.openapi.project.DumbService.isDumb(project)) {
      return McpServer.Tools.Result("IDE индексирует проект — поиск по имени будет доступен, когда индексация закончится", isError = true)
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

  /**
   * Граф импортов — ТОЛЬКО готовый, из `.vibe/codeGraph.json`.
   *
   * Раньше здесь стоял `refresh`, который сканирует весь проект и разбирает устаревшие файлы. На монорепозитории это
   * минуты, а вызов идёт внутри хода: человек видел значок вызова инструмента и больше ничего — ход выглядел
   * зависшим (поймано у владельца 18.09.2026 на `vibe_project_info`, на всех проводах одинаково).
   *
   * Нет готового графа — отвечаем об этом словами и запускаем построение в ФОНЕ, один раз: следующий вопрос
   * получит ответ, а этот ход не будет ждать минуты молча.
   */
  private fun graph(project: Project): CodeGraphIndex.Graph? {
    CodeGraphRefresh.cached(project)?.let { return it }
    startBackgroundRefresh(project)
    return null
  }

  /** Построение графа в фоне: один прогон на весь процесс, чтобы десять вопросов не запустили десять сканирований. */
  private fun startBackgroundRefresh(project: Project) {
    if (!refreshing.compareAndSet(false, true)) return
    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
      try { CodeGraphRefresh.refresh(project) }
      catch (e: Exception) { com.intellij.openapi.diagnostic.Logger.getInstance(VibeMcpTools::class.java).warn(e) }
      finally { refreshing.set(false) }
    }
  }

  /**
   * Provenance is carried into the answer, not flattened away: an import matched against a declared
   * qualified name is a fact, one matched only by a last segment is a guess, and an agent handed a
   * mixed pile treats both as read.
   */
  private fun edges(project: Project, arguments: JsonObject, importers: Boolean): McpServer.Tools.Result {
    val path = string(arguments, "path") ?: return McpServer.Tools.Result("нужен аргумент path", isError = true)
    val graph = graph(project) ?: return McpServer.Tools.Result(GRAPH_BUILDING, isError = true)
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
    val graph = graph(project) ?: return McpServer.Tools.Result(GRAPH_BUILDING, isError = true)
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
        // Имя и корень — это ответ сам по себе: инструмент не молчит из-за того, что графа ещё нет.
        if (graph == null) append(GRAPH_BUILDING)
        else append("Граф импортов: ${graph.nodes.size} файлов, ${graph.edges.size} рёбер (${facts} фактов)")
      }
    )
  }

  /**
   * Файл, открытый в редакторе, — вместе с тем, что человек в нём выделил.
   *
   * «Посмотри открытый файл» — обычная просьба, и до 18.09.2026 она была неисполнима: инструментов
   * было девять, и ни один не умел ни открытый файл, ни файл вообще. Модель честно отвечала, что
   * такого инструмента у неё нет, и просила назвать путь — тот самый, который человек уже назвал
   * тем, что открыл файл.
   *
   * Выделение отдаётся отдельно от текста: «посмотри вот это» чаще всего означает выделенное, а не
   * весь файл на три тысячи строк.
   */
  private fun openFile(project: Project): McpServer.Tools.Result {
    val state = IdeEditorFacts.selected(project)
      ?: return McpServer.Tools.Result("в редакторе IDE сейчас ничего не открыто")
    if (!readable(state.path, ProjectContextService.getInstance(project).roots())) {
      return McpServer.Tools.Result("открытый файл читать нельзя: " + state.path, isError = true)
    }
    return McpServer.Tools.Result(buildString {
      appendLine("Открыт: " + state.path)
      state.selection?.let { appendLine("Выделено (строки " + it.fromLine + "-" + it.toLine + "):\n" + it.text) }
      if (state.otherTabs.isNotEmpty()) appendLine("Ещё открыты вкладки: " + state.otherTabs.joinToString())
      append(clip(state.text, DEFAULT_FILE_CHARS))
    })
  }

  /**
   * Текст файла по пути.
   *
   * Права берутся у той же политики, что у поиска по индексу слов: два канала чтения с разными
   * правилами означали бы, что запрет `.vibe/ignore` обходится сменой инструмента.
   */
  private fun readFile(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val raw = string(arguments, "path") ?: return McpServer.Tools.Result("нужен аргумент path", isError = true)
    val limit = (arguments["maxChars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_FILE_CHARS)
      .coerceIn(1, MAX_FILE_CHARS)
    val base = project.basePath
    val resolved = if (java.io.File(raw).isAbsolute || base == null) raw else java.io.File(base, raw).path
    if (!readable(resolved, ProjectContextService.getInstance(project).roots())) {
      return McpServer.Tools.Result("читать нельзя: " + raw, isError = true)
    }
    val file = java.io.File(resolved)
    if (!file.isFile) return McpServer.Tools.Result("файла нет: " + raw, isError = true)
    val text = try {
      file.readText()
    }
    catch (e: Exception) {
      return McpServer.Tools.Result("не удалось прочитать " + raw + ": " + (e.message ?: ""), isError = true)
    }
    return McpServer.Tools.Result(raw + ":\n" + clip(text, limit))
  }

  /**
   * The text-slop report exactly as the turn gate would see it.
   *
   * The text comes from the argument or from a file under the same access rules as reading a file: a check must not
   * become a way round what may not be read.
   */
  private fun textSlop(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val inline = arguments["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val text = inline ?: run {
      val raw = string(arguments, "path") ?: return McpServer.Tools.Result("нужен аргумент text или path", isError = true)
      val base = project.basePath
      val resolved = if (java.io.File(raw).isAbsolute || base == null) raw else java.io.File(base, raw).path
      if (!readable(resolved, ProjectContextService.getInstance(project).roots())) {
        return McpServer.Tools.Result("читать нельзя: " + raw, isError = true)
      }
      val file = java.io.File(resolved)
      if (!file.isFile) return McpServer.Tools.Result("файла нет: " + raw, isError = true)
      try {
        file.readText()
      }
      catch (e: Exception) {
        return McpServer.Tools.Result("не удалось прочитать " + raw + ": " + (e.message ?: ""), isError = true)
      }
    }
    val warnings = ArrayList<String>()
    val budget = com.vibe.agent.settings.VibeAgentSettings.slopBudget()
    val report = com.vibe.agent.slop.SlopCheck.check(text, project.basePath, budget) { warnings.add(it) }
      ?: return McpServer.Tools.Result("каталог нейрослопа в сборке не читается: " +
                                       com.vibe.agent.slop.SlopCheck.builtInWarnings.joinToString("; "), isError = true)
    val body = com.vibe.agent.slop.SlopRender.render(report, SLOP_LABELS)
    val notes = if (warnings.isEmpty()) "" else "\n.vibe/slop.json: " + warnings.joinToString("; ")
    return McpServer.Tools.Result(body + notes)
  }

  /** A report for the model: like every answer of the protocol, its language is fixed, not the interface's. */
  private val SLOP_LABELS = object : com.vibe.agent.slop.SlopRender.Labels {
    override fun verdict(score: String, passScore: String, passed: Boolean, findings: Int): String =
      "Нейрослоп: $score/100 (проход — от $passScore), " + (if (passed) "проходит" else "не проходит") + ", находок: $findings"

    override fun finding(finding: com.vibe.agent.slop.SlopFinding): String {
      val head = "- строка ${finding.line}:${finding.column} [${finding.rule}] ${finding.name} (${finding.severity.id}): "
      val density = finding.density ?: return head + "«${finding.match}» → ${finding.fix}"
      return head + "${density.count} раз, ${com.vibe.agent.slop.SlopRender.number(density.perThousand)} на 1000 слов, " +
             "строки ${density.lines.joinToString(", ")} → ${finding.fix}"
    }

    override fun blocking(rules: List<String>): String = "Не пропускают: " + rules.joinToString(", ")

    override fun more(count: Int): String = "…и ещё $count"

    override fun skipped(rules: List<String>): String =
      "Правила не уложились в предел времени и пропущены: ${rules.joinToString(", ")} — оценка посчитана без них"
  }

  private fun writeFile(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val path = string(arguments, "path") ?: return McpServer.Tools.Result("нужен аргумент path", isError = true)
    val content = arguments["content"]?.jsonPrimitive?.contentOrNull
      ?: return McpServer.Tools.Result("нужен аргумент content", isError = true)
    val answer = IdeWorkTools.write(project, path, content)
    return McpServer.Tools.Result(answer, isError = answer.startsWith("писать нельзя"))
  }

  private fun replaceInFile(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val path = string(arguments, "path") ?: return McpServer.Tools.Result("нужен аргумент path", isError = true)
    val old = arguments["old"]?.jsonPrimitive?.contentOrNull
      ?: return McpServer.Tools.Result("нужен аргумент old", isError = true)
    val new = arguments["new"]?.jsonPrimitive?.contentOrNull
      ?: return McpServer.Tools.Result("нужен аргумент new", isError = true)
    val answer = IdeWorkTools.replace(project, path, old, new)
    return McpServer.Tools.Result(answer, isError = !answer.startsWith("правка внесена"))
  }

  private fun runCommand(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val command = string(arguments, "command") ?: return McpServer.Tools.Result("нужен аргумент command", isError = true)
    val background = arguments["background"]?.jsonPrimitive?.contentOrNull == "true"
    if (!background) {
      val seconds = arguments["timeoutSeconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
      return McpServer.Tools.Result(
        if (seconds != null) IdeWorkTools.run(project, command, seconds) else IdeWorkTools.run(project, command))
    }
    val commands = AgentCommands.getInstance(project)
    return commands.start(command, IdeWorkTools.loginEnvironment()).fold(
      onSuccess = { id ->
        McpServer.Tools.Result("запущено в фоне под именем " + id + ": " + command +
                               "\nвывод — vibe_command_output, остановить — vibe_command_stop")
      },
      onFailure = {
        McpServer.Tools.Result("уже запущено " + AgentCommands.MAX_LIVE + " фоновых команд — остановите лишние " +
                               "(vibe_command_stop), прежде чем запускать новые", isError = true)
      })
  }

  /** Вывод фоновой команды, или список всего запущенного, если имя не названо. */
  private fun commandOutput(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val commands = AgentCommands.getInstance(project)
    val id = string(arguments, "id")
      ?: return McpServer.Tools.Result(commands.running().entries
        .joinToString("\n") { (name, command) -> name + ": " + command }
        .ifEmpty { "фоновых команд сейчас нет" })
    val snapshot = commands.snapshot(id)
      ?: return McpServer.Tools.Result("нет фоновой команды с именем " + id, isError = true)
    val state = if (snapshot.finished) "кончилась, код выхода " + (snapshot.exitCode ?: "неизвестен")
                else "ещё идёт"
    val tail = if (snapshot.truncated) "\n… начало вывода обрезано по потолку" else ""
    return McpServer.Tools.Result(id + " (" + commands.commandOf(id).orEmpty() + ") — " + state + ":\n" +
                                  snapshot.output.ifBlank { "(вывода нет)" } + tail)
  }

  private fun commandStop(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val id = string(arguments, "id") ?: return McpServer.Tools.Result("нужен аргумент id", isError = true)
    val stopped = AgentCommands.getInstance(project).stop(id)
    return McpServer.Tools.Result(
      if (stopped) "остановлено: " + id else "не нашлось живой команды с именем " + id, isError = !stopped)
  }

  /**
   * Откуда пришло имя — цепочкой по файлам.
   *
   * Шаг «импорт» ведёт к следующему файлу: путь модуля разрешается относительно текущего файла, а
   * если так не нашлось — по графу импортов проекта, который знает, куда на самом деле ведёт
   * короткое имя. Цепочка обрывается на потолке шагов и говорит об этом: молча оборванная выглядит
   * как законченная.
   */
  private fun traceSymbol(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val name = string(arguments, "name") ?: return McpServer.Tools.Result("нужен аргумент name", isError = true)
    val startRaw = string(arguments, "path")
    val start = if (startRaw != null) IdeWorkTools.resolve(project, startRaw) else IdeEditorFacts.selected(project)?.path
      ?: return McpServer.Tools.Result("в редакторе ничего не открыто — назовите путь файла", isError = true)
    val hops = (arguments["hops"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_TRACE_HOPS).coerceIn(1, MAX_TRACE_HOPS)
    val roots = ProjectContextService.getInstance(project).roots()
    if (!readable(start, roots)) return McpServer.Tools.Result("читать нельзя: " + start, isError = true)
    // The language server first: it resolves imports, re-exports and aliases as the compiler does. The text reading
    // below is the fallback, and its answer starts by saying why it had to be used.
    val note = when (val precise = VibeSymbolLookup.lookup(project, start, name)) {
      is VibeSymbolLookup.Result.Found ->
        return McpServer.Tools.Result(preciseTrace(precise) { readable(it, roots) }.joinToString("\n"))
      is VibeSymbolLookup.Result.Missed -> textTraceNote(precise.reason)
    }
    val lines = ArrayList<String>()
    var current: String? = start
    val seen = HashSet<String>()
    var hop = 0
    while (current != null && hop < hops) {
      if (!seen.add(current) || !readable(current, roots)) break
      val text = runCatching { java.io.File(current!!).readText() }.getOrNull() ?: break
      val steps = com.vibe.agent.graph.SymbolTrace.stepsIn(text, name)
      if (steps.isEmpty()) {
        lines += current + ": имя не встречается в объявлениях этого файла"
        break
      }
      steps.forEach { step -> lines += current + ":" + step.line + " [" + step.kind.name + "] " + step.text }
      val next = steps.lastOrNull { it.kind == com.vibe.agent.graph.SymbolTrace.Kind.IMPORT }?.from
      current = next?.let { resolveModule(project, current!!, it) }
      hop++
    }
    if (lines.isEmpty()) return McpServer.Tools.Result(note + "\nне нашлось, откуда приходит " + name)
    if (current != null && hop >= hops) lines += "… цепочка оборвана по потолку в " + hops + " файлов"
    return McpServer.Tools.Result((listOf(note) + lines).joinToString("\n"))
  }

  /** Путь модуля → файл: сперва относительно текущего, потом по графу импортов. */
  private fun resolveModule(project: Project, from: String, module: String): String? {
    val base = java.io.File(from).parentFile ?: return null
    if (module.startsWith(".")) {
      for (suffix in MODULE_SUFFIXES) {
        val candidate = java.io.File(base, module + suffix)
        if (candidate.isFile) return candidate.canonicalPath
      }
      return null
    }
    // Не относительный путь: чужой пакет или алиас проекта. Граф импортов знает, куда он ведёт,
    // если такой файл в проекте есть; иначе цепочка честно кончается.
    val graph = graph(project) ?: return null
    val tail = module.substringAfterLast('/')
    return graph.nodes.map { it.path }.firstOrNull { path ->
      val fileName = path.substringAfterLast('/')
      MODULE_SUFFIXES.any { fileName == tail + it }
    }?.let { path -> project.basePath?.let { java.io.File(it, path).path } ?: path }
  }

  /** Поиск по документации, вшитой в сборку: формат файла и порядок работы — оттуда, а не из догадок. */
  private fun docsSearch(arguments: JsonObject): McpServer.Tools.Result {
    val query = string(arguments, "query") ?: return McpServer.Tools.Result("нужен аргумент query", isError = true)
    val limit = arguments["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_DOCS_LIMIT
    return McpServer.Tools.Result(com.vibe.agent.help.HelpBundle.search(query, limit))
  }

  /**
   * Ошибки и предупреждения файла — те же, что подчёркнуты на экране.
   *
   * Путь не назван — берём открытый в редакторе: «почини ошибки» почти всегда про то, что человек
   * сейчас видит.
   */
  private fun problems(project: Project, arguments: JsonObject): McpServer.Tools.Result {
    val raw = string(arguments, "path")
    val path = if (raw != null) IdeWorkTools.resolve(project, raw) else IdeEditorFacts.selected(project)?.path
      ?: return McpServer.Tools.Result("в редакторе ничего не открыто — назовите путь файла", isError = true)
    if (!readable(path, ProjectContextService.getInstance(project).roots())) {
      return McpServer.Tools.Result("читать нельзя: " + path, isError = true)
    }
    return when (val outcome = IdeProblems.of(project, path)) {
      is IdeProblems.Result.NotOpen -> McpServer.Tools.Result(
        "файл не открыт в редакторе, поэтому разметки у него нет: откройте " + path +
        " и спросите снова. Это не значит, что ошибок нет.")
      is IdeProblems.Result.NotReady -> McpServer.Tools.Result(
        "анализ ещё считает " + path + " после последних правок: спросите снова через мгновение. " +
        "Это НЕ значит, что ошибок нет.")
      is IdeProblems.Result.Found ->
        if (outcome.problems.isEmpty()) McpServer.Tools.Result("IDE не нашла в " + path + " ни ошибок, ни предупреждений")
        else McpServer.Tools.Result(outcome.problems.joinToString("\n") { p ->
          p.line.toString() + ": [" + p.severity + "] " + p.message + "  |  " + p.text
        })
    }
  }

  /** Состояние самой IDE: наша часть и всё, что рассказали о себе соседние плагины. */
  private fun ideInfo(project: Project): McpServer.Tools.Result =
    McpServer.Tools.Result(VibeIdeFacts.collect(project).joinToString("\n"))

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

  internal companion object {
    /** Строится ли граф прямо сейчас: иначе каждый вопрос без графа запускал бы своё сканирование. */
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    internal const val GRAPH_BUILDING =
      "Граф импортов ещё не построен — я запустил построение в фоне. Спросите снова через минуту, " +
      "или постройте его сразу: Tools → «Vibe: экспорт графа проекта»."

    private const val DEFAULT_USAGE_LIMIT = 50

    /** Потолок: ответ инструмента уходит в контекст модели, и «все совпадения» там не нужны никому. */
    private const val MAX_USAGE_LIMIT = 200

    /** Длинная строка в ответе — это минифицированный файл; смысла в ней нет, а токены есть. */
    private const val LINE_CHARS = 200

    private const val DEFAULT_DOCS_LIMIT = 5

    /** Сколько файлов проходит цепочка «откуда пришло»: дальше четвёртого ответ перестаёт читаться. */
    private const val DEFAULT_TRACE_HOPS = 4
    private const val MAX_TRACE_HOPS = 10

    /** Чем достраивается путь модуля без расширения — в порядке, в котором их пробует сборщик. */
    private val MODULE_SUFFIXES = listOf(".ts", ".tsx", ".js", ".jsx", ".mts", ".php", "/index.ts", "/index.js", "")

    /** Сколько текста файла отдаём по умолчанию: длинный файл вытесняет из хода всё остальное. */
    private const val DEFAULT_FILE_CHARS = 60_000

    /** Потолок, выше которого не поднимает и явная просьба: за ним начинается обрыв хода по контексту. */
    private const val MAX_FILE_CHARS = 200_000

    /** Обрезка, которая НАЗЫВАЕТ себя: молча обрезанный файл модель считает файлом целиком. */
    internal fun clip(text: String, limit: Int): String =
      if (text.length <= limit) text
      else text.take(limit) + "\n… обрезано, показано " + limit + " из " + text.length + " символов"

    /**
     * May the agent read this file — the file channel's rule, on the same resolved path.
     *
     * The word index knows nothing of `.vibe/ignore` or of where a link leads; without this check a
     * search would hand out, line by line, a file the channel refuses to open. VibeIDE found the same
     * hole in its own search on 11.09.2026.
     */
    internal fun readable(path: String, roots: AccessPolicy.Roots): Boolean {
      val resolved = AgentPaths.resolve(path) as? AgentPaths.Result.Resolved ?: return false
      return AccessPolicy.mayRead(resolved.path.canonical.toString(), roots)
    }

    /**
     * The trace answered by a language server: where the name is declared, and its signature.
     *
     * A declaration in a file the agent may not read is named but not described: a hover over a constant shows its
     * value, and the access rule would leak through the signature.
     */
    internal fun preciseTrace(found: VibeSymbolLookup.Result.Found, readable: (String) -> Boolean): List<String> {
      val servers = found.places.map { it.server }.distinct().joinToString()
      val lines = arrayListOf("точно, по языковому серверу $servers: импорты, реэкспорты и псевдонимы уже пройдены")
      var closed = false
      for (place in found.places) {
        val open = readable(place.path)
        closed = closed || !open
        lines += place.path + ":" + place.line + " [LSP]" + if (open) "" else " файл закрыт правилами доступа"
      }
      if (!closed) found.signature?.let { lines += "сигнатура: $it" }
      return lines
    }

    /** Why the answer is the text reading: the model decides by it whether asking again later is worth it. */
    internal fun textTraceNote(reason: VibeSymbolLookup.Reason): String = when (reason) {
      VibeSymbolLookup.Reason.NOT_SERVED -> "разбор текстовый: языковой сервер этот файл не обслуживает"
      VibeSymbolLookup.Reason.NOT_IN_FILE -> "разбор текстовый: имени нет в тексте файла целым словом"
      VibeSymbolLookup.Reason.NO_ANSWER ->
        "разбор текстовый: языковой сервер не ответил вовремя, скорее всего ещё загружает проект — спросите позже, ответ будет точным"
      VibeSymbolLookup.Reason.NOTHING_FOUND -> "разбор текстовый: языковой сервер не знает объявления этого имени"
    }
  }
}
