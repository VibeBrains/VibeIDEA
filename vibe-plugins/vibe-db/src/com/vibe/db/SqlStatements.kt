// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Разбор SQL-файла на отдельные запросы.
 *
 * Нужен ровно затем же, зачем `.http` разбирается на запросы: человек держит в файле десять
 * операторов и запускает тот, где стоит курсор. Наивное деление по `;` здесь не годится —
 * точка с запятой живёт внутри строк, комментариев и тел функций, и файл миграции разъехался бы
 * на середине первого же `CREATE FUNCTION`.
 *
 * Чистый: текст внутрь, границы наружу.
 */
object SqlStatements {
  data class Statement(val text: String, val startLine: Int, val endLine: Int) {
    /** Как показать в списке: первая содержательная строка, без комментариев. */
    val title: String
      get() = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("--") }
        ?.take(80)
        ?: text.trim().take(80)
  }

  /**
   * Делит текст на операторы.
   *
   * Понимает: строки в одинарных и двойных кавычках (и удвоенную кавычку внутри), комментарии
   * `--` и `/* */`, а также долларовые кавычки PostgreSQL (`$$ … $$`, `$tag$ … $tag$`) — без них
   * тело функции считалось бы десятком операторов.
   */
  fun split(text: String): List<Statement> {
    val statements = ArrayList<Statement>()
    val current = StringBuilder()
    var line = 0
    var startLine = 0
    var index = 0
    var quote: Char? = null
    var dollarTag: String? = null
    var lineComment = false
    var blockComment = false

    fun flush(endLine: Int) {
      val body = current.toString().trim()
      if (body.isNotEmpty()) statements.add(Statement(body, startLine, endLine))
      current.setLength(0)
      startLine = endLine + 1
    }

    while (index < text.length) {
      val ch = text[index]
      val next = text.getOrNull(index + 1)
      when {
        ch == '\n' -> {
          lineComment = false
          current.append(ch)
          line++
          if (current.isBlank()) startLine = line
          index++
        }
        lineComment || blockComment -> {
          if (blockComment && ch == '*' && next == '/') { blockComment = false; current.append("*/"); index += 2 }
          else { current.append(ch); index++ }
        }
        dollarTag != null -> {
          if (text.startsWith(dollarTag, index)) { current.append(dollarTag); index += dollarTag.length; dollarTag = null }
          else { current.append(ch); index++ }
        }
        quote != null -> {
          current.append(ch)
          if (ch == quote) {
            // Удвоенная кавычка — не конец строки, а сама кавычка: 'it''s'.
            if (next == quote) { current.append(next); index++ } else quote = null
          }
          index++
        }
        ch == '-' && next == '-' -> { lineComment = true; current.append("--"); index += 2 }
        ch == '/' && next == '*' -> { blockComment = true; current.append("/*"); index += 2 }
        ch == '\'' || ch == '"' -> { quote = ch; current.append(ch); index++ }
        ch == '$' -> {
          val tag = DOLLAR_TAG.matchAt(text, index)?.value
          if (tag != null) { dollarTag = tag; current.append(tag); index += tag.length }
          else { current.append(ch); index++ }
        }
        ch == ';' -> { flush(line); index++ }
        else -> { current.append(ch); index++ }
      }
    }
    flush(line)
    return statements
  }

  private val DOLLAR_TAG = Regex("\\$[A-Za-z_0-9]*\\$")

  /** Оператор, внутри которого стоит курсор, — то, что выполняет действие «выполнить запрос». */
  fun statementAt(statements: List<Statement>, line: Int): Statement? =
    statements.firstOrNull { line in it.startLine..it.endLine } ?: statements.lastOrNull { it.endLine <= line }

  /** Читающий ли это оператор. По первому слову — этого достаточно и это не притворяется разбором SQL. */
  fun isReadOnly(statement: String): Boolean {
    val first = statement.trimStart()
      .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("--") }
      ?.substringBefore(' ')?.trim()?.uppercase().orEmpty()
    return first in READ_ONLY
  }

  private val READ_ONLY = setOf("SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE")
}
