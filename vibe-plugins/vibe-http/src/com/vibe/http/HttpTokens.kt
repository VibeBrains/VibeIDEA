// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

/**
 * Разбиение текста `.http` на подсвечиваемые куски.
 *
 * Отдельно от лексера платформы и без единого класса IDE: подсветка — это решения («где здесь имя
 * заголовка», «докуда тянется тело»), и проверять их надо тестом на строках, а не глазами по
 * открытому файлу. Лексер поверх — тонкая обёртка, которая просто отдаёт эти куски платформе.
 *
 * Куски идут подряд и покрывают текст целиком, без дыр и нахлёстов: платформа именно этого и ждёт,
 * а пропущенный байт превращается в невидимую дыру в подсветке.
 */
object HttpTokens {
  enum class Kind {
    /** `### Имя` — разделитель запросов. */
    SEPARATOR,
    /** `#` или `//` — обычный комментарий. */
    COMMENT,
    /** `# @name`, `# @timeout` — пометка, управляющая запросом. */
    META,
    /** `GET`, `POST`, … */
    METHOD,
    /** Адрес запроса. */
    TARGET,
    /** Имя заголовка до двоеточия. */
    HEADER_NAME,
    /** Значение заголовка. */
    HEADER_VALUE,
    /** `{{переменная}}` — где угодно. */
    VARIABLE,
    /** `@host = …` — объявление переменной файла. */
    VARIABLE_DECL,
    /** `< body.json`, `>> out.json`, `> handler.js`. */
    DIRECTIVE,
    /** Тело запроса. */
    BODY,
    /** Пробелы и переводы строк между кусками. */
    WHITESPACE,
  }

  data class Token(val kind: Kind, val start: Int, val end: Int)

  private val METHODS = setOf(
    "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT",
    "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK",
  )

  /** Внутри какой части запроса мы находимся — от этого зависит смысл строки. */
  private enum class Section { BEFORE_REQUEST, HEADERS, BODY }

  fun scan(text: String): List<Token> {
    val tokens = ArrayList<Token>()
    var section = Section.BEFORE_REQUEST
    var offset = 0
    while (offset < text.length) {
      val lineEnd = text.indexOf('\n', offset).let { if (it == -1) text.length else it }
      val line = text.substring(offset, lineEnd)
      val trimmed = line.trim()
      val indent = line.length - line.trimStart().length
      val contentStart = offset + indent
      val contentEnd = offset + line.trimEnd().length

      fun whole(kind: Kind) {
        if (indent > 0) tokens.add(Token(Kind.WHITESPACE, offset, contentStart))
        if (contentEnd > contentStart) tokens.add(Token(kind, contentStart, contentEnd))
        if (lineEnd > contentEnd) tokens.add(Token(Kind.WHITESPACE, contentEnd, lineEnd))
      }

      when {
        trimmed.isEmpty() -> {
          // Пустая строка — это ноль символов до перевода строки: сам перевод добавляется ниже,
          // а кусок нулевой длины платформа считает ошибкой лексера.
          if (lineEnd > offset) tokens.add(Token(Kind.WHITESPACE, offset, lineEnd))
          // Пустая строка после заголовков открывает тело — это и есть правило формата.
          if (section == Section.HEADERS) section = Section.BODY
        }
        trimmed.startsWith("###") -> { whole(Kind.SEPARATOR); section = Section.BEFORE_REQUEST }
        isMeta(trimmed) -> whole(Kind.META)
        trimmed.startsWith("//") || (trimmed.startsWith("#") && !trimmed.startsWith("###")) -> whole(Kind.COMMENT)
        trimmed.startsWith("@") && section != Section.BODY && VARIABLE_DECL.matches(trimmed) -> whole(Kind.VARIABLE_DECL)
        trimmed.startsWith("<") || trimmed.startsWith(">") -> whole(Kind.DIRECTIVE)
        section == Section.BEFORE_REQUEST && requestLine(trimmed) != null -> {
          val (method, _) = requestLine(trimmed)!!
          if (indent > 0) tokens.add(Token(Kind.WHITESPACE, offset, contentStart))
          if (method != null) {
            tokens.add(Token(Kind.METHOD, contentStart, contentStart + method.length))
            val targetStart = contentStart + method.length
            addWithVariables(tokens, text, targetStart, contentEnd, Kind.TARGET)
          }
          else addWithVariables(tokens, text, contentStart, contentEnd, Kind.TARGET)
          if (lineEnd > contentEnd) tokens.add(Token(Kind.WHITESPACE, contentEnd, lineEnd))
          section = Section.HEADERS
        }
        section == Section.HEADERS && trimmed.contains(':') -> {
          val colon = line.indexOf(':', indent)
          if (indent > 0) tokens.add(Token(Kind.WHITESPACE, offset, contentStart))
          tokens.add(Token(Kind.HEADER_NAME, contentStart, offset + colon + 1))
          addWithVariables(tokens, text, offset + colon + 1, contentEnd, Kind.HEADER_VALUE)
          if (lineEnd > contentEnd) tokens.add(Token(Kind.WHITESPACE, contentEnd, lineEnd))
        }
        else -> {
          if (indent > 0) tokens.add(Token(Kind.WHITESPACE, offset, contentStart))
          addWithVariables(tokens, text, contentStart, contentEnd, Kind.BODY)
          if (lineEnd > contentEnd) tokens.add(Token(Kind.WHITESPACE, contentEnd, lineEnd))
        }
      }
      offset = if (lineEnd < text.length) lineEnd + 1 else text.length
      if (lineEnd < text.length) tokens.add(Token(Kind.WHITESPACE, lineEnd, lineEnd + 1))
    }
    return tokens
  }

  private val VARIABLE_DECL = Regex("^@[A-Za-z0-9_\\-.]+\\s*=.*$")
  private val META_LINE = Regex("^(?://|#)\\s*@[A-Za-z\\-]+.*$")
  private val PLACEHOLDER = Regex("\\{\\{[^{}]*}}")

  private fun isMeta(trimmed: String): Boolean = META_LINE.matches(trimmed)

  /** Метод и адрес, если строка похожа на строку запроса. Метод может отсутствовать. */
  private fun requestLine(trimmed: String): Pair<String?, String>? {
    val space = trimmed.indexOf(' ')
    if (space > 0) {
      val head = trimmed.take(space)
      if (head in METHODS) return head to trimmed.drop(space).trim()
    }
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("{{")) null to trimmed
    else null
  }

  /** Кусок текста, внутри которого `{{переменные}}` подсвечиваются отдельно. */
  private fun addWithVariables(tokens: MutableList<Token>, text: String, start: Int, end: Int, kind: Kind) {
    if (end <= start) return
    var cursor = start
    for (match in PLACEHOLDER.findAll(text.substring(start, end))) {
      val from = start + match.range.first
      val to = start + match.range.last + 1
      if (from > cursor) tokens.add(Token(kind, cursor, from))
      tokens.add(Token(Kind.VARIABLE, from, to))
      cursor = to
    }
    if (cursor < end) tokens.add(Token(kind, cursor, end))
  }
}
