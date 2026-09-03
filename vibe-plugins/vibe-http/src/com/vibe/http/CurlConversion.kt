// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

/**
 * Перевод между запросом и командой `curl` — в обе стороны.
 *
 * Зачем: `curl` — это общий язык, на котором разработчики пересылают друг другу запросы. В Postman
 * «импорт из cURL» и «копировать как cURL» — две самые используемые кнопки, потому что запрос
 * приходит из документации, из чужого тикета, из вкладки «Network» браузера. Без них файл `.http`
 * пришлось бы набивать руками по чужой команде, а это ровно та работа, ради избавления от которой
 * инструмент и существует.
 *
 * Чистый: строки внутрь, строки наружу.
 */
object CurlConversion {
  /** Запрос → однострочная команда, которую можно вставить в терминал или в тикет. */
  fun toCurl(request: HttpRequestFile.Request, bodyText: String? = null): String = buildString {
    append("curl")
    if (request.method != "GET") append(" -X ").append(request.method)
    append(" ").append(quote(request.target))
    for (header in request.headers) {
      append(" \\\n  -H ").append(quote("${header.name}: ${header.value}"))
    }
    val body = bodyText ?: (request.body as? HttpRequestFile.Body.Inline)?.text
    if (!body.isNullOrEmpty()) append(" \\\n  --data-raw ").append(quote(body))
    if (request.noRedirect) return@buildString
    append(" \\\n  -L")
  }

  /**
   * Команда `curl` → запрос.
   *
   * Понимаем то, что реально встречается в чужих командах: `-X`, `-H`/`--header`, `-d`/`--data`/
   * `--data-raw`/`--data-binary`, `-u`, `--url`, `-L`, `--compressed`, перенос строки обратным
   * слэшем. Незнакомый флаг пропускается молча — команда из документации почти всегда несёт
   * что-нибудь про вывод (`-s`, `-o`), и отказывать из-за этого значит отказывать всегда.
   */
  fun fromCurl(command: String): HttpRequestFile.Request? {
    val tokens = tokenize(command.replace("\\\n", " ").replace("\\\r\n", " "))
    if (tokens.isEmpty() || !tokens.first().endsWith("curl")) return null
    var method: String? = null
    var url: String? = null
    val headers = ArrayList<HttpRequestFile.Header>()
    var body: String? = null
    var noRedirect = true
    var index = 1
    while (index < tokens.size) {
      val token = tokens[index]
      when {
        token == "-X" || token == "--request" -> { method = tokens.getOrNull(++index)?.uppercase() }
        token == "-H" || token == "--header" -> {
          val raw = tokens.getOrNull(++index).orEmpty()
          val colon = raw.indexOf(':')
          if (colon > 0) headers.add(HttpRequestFile.Header(raw.take(colon).trim(), raw.drop(colon + 1).trim()))
        }
        token == "-d" || token == "--data" || token == "--data-raw" || token == "--data-binary" || token == "--data-ascii" ->
          body = tokens.getOrNull(++index)
        token == "-u" || token == "--user" -> {
          val credentials = tokens.getOrNull(++index).orEmpty()
          // Basic собираем сами: заголовок понятнее в файле, чем флаг, и его видно при чтении.
          val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
          headers.add(HttpRequestFile.Header("Authorization", "Basic $encoded"))
        }
        token == "--url" -> url = tokens.getOrNull(++index)
        token == "-L" || token == "--location" -> noRedirect = false
        token.startsWith("-") -> Unit
        url == null -> url = token
        else -> Unit
      }
      index++
    }
    val target = url ?: return null
    return HttpRequestFile.Request(
      name = null,
      // Тело без явного метода означает POST — так же считает сам curl.
      method = method ?: if (body != null) "POST" else "GET",
      target = target,
      httpVersion = null,
      headers = headers,
      body = body?.let { HttpRequestFile.Body.Inline(it) },
      startLine = 0,
      endLine = 0,
      noRedirect = noRedirect,
    )
  }

  /** Как записать запрос в файл `.http` — то, что вставляется после импорта. */
  fun toHttpFile(request: HttpRequestFile.Request, name: String? = null): String = buildString {
    append("### ").append(name ?: request.title).append('\n')
    if (request.noRedirect) append("# @no-redirect\n")
    append(request.method).append(' ').append(request.target).append('\n')
    for (header in request.headers) append(header.name).append(": ").append(header.value).append('\n')
    (request.body as? HttpRequestFile.Body.Inline)?.let { append('\n').append(it.text).append('\n') }
  }

  private fun quote(value: String): String =
    if (value.none { it in " \t\"'{}[]()$&|<>;*?`\\\n" }) value
    else "'" + value.replace("'", "'\\''") + "'"

  /** Разбор командной строки с кавычками — иначе `-H 'A: b c'` распадётся на три токена. */
  private fun tokenize(command: String): List<String> {
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var index = 0
    var started = false
    while (index < command.length) {
      val ch = command[index]
      when {
        quote != null && ch == quote -> { quote = null }
        quote != null -> current.append(ch)
        // Вне кавычек обратный слэш экранирует следующий символ. Это не мелочь: строку с апострофом
        // сам же curl печатает как '\'' — закрыть кавычку, экранировать апостроф, открыть снова, —
        // и без этой ветки собственный вывод не читается обратно.
        ch == '\\' && index + 1 < command.length -> { current.append(command[index + 1]); index++; started = true }
        ch == '\'' || ch == '"' -> { quote = ch; started = true }
        ch.isWhitespace() -> {
          if (current.isNotEmpty() || started) { tokens.add(current.toString()); current.setLength(0); started = false }
        }
        else -> current.append(ch)
      }
      index++
    }
    if (current.isNotEmpty() || started) tokens.add(current.toString())
    return tokens
  }
}
