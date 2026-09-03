// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

/**
 * Разбор файла `.http` — запросы, которые лежат в репозитории рядом с кодом.
 *
 * Почему файлы, а не своё хранилище: у Postman коллекции живут в его облаке, и это его же главная
 * претензия со стороны разработчиков — запрос нельзя посмотреть в код-ревью, нельзя вернуть
 * откатом, нельзя увидеть, кто и когда его сломал. Файл в git отвечает на всё это по построению,
 * а формат `.http` вдобавок открыт спекой (http-request-in-editor-spec, CC-BY-4.0) и понятен
 * соседним инструментам — от REST Client в VS Code до Bruno.
 *
 * Чистый: на входе текст, на выходе структура. Ни файлов, ни сети, ни проекта — чтобы разбор
 * проверялся построчно, а не «запустите IDE и посмотрите».
 */
object HttpRequestFile {
  /** Тело запроса: либо текст прямо в файле, либо содержимое соседнего файла (`< data.json`). */
  sealed interface Body {
    data class Inline(val text: String) : Body
    data class FromFile(val path: String) : Body
  }

  data class Header(val name: String, val value: String)

  data class Request(
    /** Имя из `### Имя` или из `# @name Имя`; null — запрос без имени, показываем метод и адрес. */
    val name: String?,
    val method: String,
    val target: String,
    val httpVersion: String?,
    val headers: List<Header>,
    val body: Body?,
    /** Строки файла, 0-based: по ним рисуется гаттер «выполнить» и подсвечивается блок. */
    val startLine: Int,
    val endLine: Int,
    /** `# @no-redirect` — не идти по 3xx. */
    val noRedirect: Boolean = false,
    /** `# @timeout 30` — секунды; null означает «как в настройках». */
    val timeoutSeconds: Int? = null,
    /** `>> path` — куда сохранить тело ответа. */
    val saveResponseTo: String? = null,
    /**
     * `> {% ... %}` или `> handler.js` — обработчик ответа на JavaScript.
     *
     * Мы его РАСПОЗНАЁМ и не выполняем: свой JS-движок означал бы новую зависимость размером с
     * половину плагина. Молча проглотить нельзя — человек, написавший обработчик, обязан узнать,
     * что он не сработал, иначе он будет искать ошибку в самом скрипте.
     */
    val responseHandler: String? = null,
  ) {
    /** Как показать запрос в списке и в гаттере. */
    val title: String get() = name ?: "$method $target"
  }

  data class ParsedFile(
    val requests: List<Request>,
    /** Переменные файла (`@host = ...`) — видны всем запросам ниже по тексту. */
    val variables: Map<String, String>,
    /** Строки, которые не удалось понять. Разбор при этом не падает. */
    val problems: List<Problem>,
  )

  /**
   * Что именно не понято — КОДОМ, а не фразой.
   *
   * Правило проекта: чистый модуль отдаёт данные, фразу собирает интерфейс. Иначе разбор начинает
   * зависеть от языка интерфейса, а тест — сравнивать русский текст.
   */
  enum class Trouble { NOT_A_REQUEST, NOT_A_HEADER, TIMEOUT_NOT_A_NUMBER }

  data class Problem(val line: Int, val trouble: Trouble)

  private val SEPARATOR = Regex("^###+\\s*(.*)$")
  private val VARIABLE = Regex("^@([A-Za-z0-9_\\-.]+)\\s*=\\s*(.*)$")
  private val META = Regex("^(?://|#)\\s*@([A-Za-z\\-]+)\\s*(.*)$")
  private val REQUEST_LINE = Regex("^([A-Z]+)\\s+(\\S.*?)(?:\\s+(HTTP/[0-9.]+))?\\s*$")
  private val HEADER = Regex("^([A-Za-z0-9!#$%&'*+\\-.^_`|~]+)\\s*:\\s*(.*)$")

  /** Методы, которые считаем методами: строка «SELECT * FROM» иначе выглядела бы как запрос. */
  private val METHODS = setOf(
    "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT",
    "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK",
  )

  fun parse(text: String): ParsedFile {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val requests = ArrayList<Request>()
    val variables = LinkedHashMap<String, String>()
    val problems = ArrayList<Problem>()

    var index = 0
    var pendingName: String? = null
    // Пометки пишут и над строкой запроса, и под ней; собираем встреченные до неё и передаём внутрь.
    // Без этого «# @name» сразу после «###» молча терялся: главный цикл считал его комментарием.
    var pendingMeta = ArrayList<Pair<Int, String>>()
    while (index < lines.size) {
      val line = lines[index]
      val separator = SEPARATOR.matchEntire(line.trim())
      if (separator != null) {
        pendingName = separator.groupValues[1].trim().takeIf { it.isNotEmpty() }
        index++
        continue
      }
      val variable = VARIABLE.matchEntire(line.trim())
      if (variable != null) {
        variables[variable.groupValues[1]] = variable.groupValues[2].trim()
        index++
        continue
      }
      val meta = META.matchEntire(line.trim())
      if (meta != null) {
        pendingMeta.add(index to line.trim())
        index++
        continue
      }
      if (line.isBlank() || isComment(line)) {
        index++
        continue
      }
      val parsed = parseRequest(lines, index, pendingName, pendingMeta, problems)
      if (parsed == null) {
        problems.add(Problem(index, Trouble.NOT_A_REQUEST))
        pendingMeta = ArrayList()
        index++
        continue
      }
      requests.add(parsed.first)
      pendingName = null
      pendingMeta = ArrayList()
      index = parsed.second
    }
    return ParsedFile(requests, variables, problems)
  }

  private fun isComment(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("//") || (trimmed.startsWith("#") && !trimmed.startsWith("###"))
  }

  /** @return запрос и индекс строки, с которой продолжать, либо null если это не запрос. */
  private fun parseRequest(
    lines: List<String>,
    from: Int,
    name: String?,
    pendingMeta: List<Pair<Int, String>>,
    problems: MutableList<Problem>,
  ): Pair<Request, Int>? {
    val start = from
    val match = REQUEST_LINE.matchEntire(lines[from].trim())
    val (method, target, version) = when {
      match != null && match.groupValues[1] in METHODS ->
        Triple(match.groupValues[1], match.groupValues[2].trim(), match.groupValues[3].takeIf { it.isNotEmpty() })
      // Метод можно опустить — спека разрешает, и это самый частый способ записать GET.
      lines[from].trim().let { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("{{") } ->
        Triple("GET", lines[from].trim(), null)
      else -> return null
    }

    var index = from + 1
    val headers = ArrayList<Header>()
    var metaName = name
    var noRedirect = false
    var timeout: Int? = null
    var saveTo: String? = null
    var handler: String? = null

    fun applyMeta(lineNumber: Int, text: String) {
      val meta = META.matchEntire(text) ?: return
      when (meta.groupValues[1].lowercase()) {
        "name" -> metaName = meta.groupValues[2].trim().takeIf { it.isNotEmpty() } ?: metaName
        "no-redirect" -> noRedirect = true
        "timeout" -> timeout = meta.groupValues[2].trim().toIntOrNull()
          ?: run { problems.add(Problem(lineNumber, Trouble.TIMEOUT_NOT_A_NUMBER)); null }
        // Незнакомая пометка — не ошибка: спека растёт, а файл должен остаться читаемым.
        else -> Unit
      }
    }
    for ((lineNumber, text) in pendingMeta) applyMeta(lineNumber, text)

    // Заголовки идут до пустой строки; метакомментарии среди них разрешены.
    while (index < lines.size) {
      val line = lines[index]
      if (line.isBlank()) { index++; break }
      if (SEPARATOR.matches(line.trim())) break
      if (META.matches(line.trim())) {
        applyMeta(index, line.trim())
        index++
        continue
      }
      if (isComment(line)) { index++; continue }
      val header = HEADER.matchEntire(line.trim())
      if (header == null) {
        problems.add(Problem(index, Trouble.NOT_A_HEADER))
        index++
        continue
      }
      headers.add(Header(header.groupValues[1], header.groupValues[2].trim()))
      index++
    }

    // Тело — до следующего разделителя.
    val bodyLines = ArrayList<String>()
    var bodyFile: String? = null
    while (index < lines.size) {
      val line = lines[index]
      if (SEPARATOR.matches(line.trim())) break
      val trimmed = line.trim()
      when {
        trimmed.startsWith("< ") -> bodyFile = trimmed.removePrefix("< ").trim()
        trimmed.startsWith(">> ") -> saveTo = trimmed.removePrefix(">> ").trim()
        trimmed.startsWith("> ") -> handler = trimmed.removePrefix("> ").trim()
        else -> bodyLines.add(line)
      }
      index++
    }

    val bodyText = bodyLines.joinToString("\n").trim()
    val body = when {
      bodyFile != null -> Body.FromFile(bodyFile)
      bodyText.isNotEmpty() -> Body.Inline(bodyText)
      else -> null
    }
    val end = (index - 1).coerceAtLeast(start)
    return Request(
      name = metaName,
      method = method,
      target = target,
      httpVersion = version,
      headers = headers,
      body = body,
      startLine = start,
      endLine = end,
      noRedirect = noRedirect,
      timeoutSeconds = timeout,
      saveResponseTo = saveTo,
      responseHandler = handler,
    ) to index
  }

  /** Запрос, внутри которого стоит курсор, — то, что выполняет действие «выполнить запрос». */
  fun requestAt(parsed: ParsedFile, line: Int): Request? =
    parsed.requests.firstOrNull { line in it.startLine..it.endLine }
}
