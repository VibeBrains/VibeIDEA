// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Разбиение SQL на подсвечиваемые куски.
 *
 * Отдельно от платформы и без единого класса IDE — по той же причине, что и у `.http`: подсветка
 * состоит из решений («докуда тянется строка», «это комментарий или деление»), и проверять их надо
 * тестом на строках, а не глазами по открытой консоли.
 *
 * Диалект намеренно один, общий: подсветка не обязана знать, чей перед ней SQL. Ключевые слова
 * взяты из пересечения диалектов, а всё незнакомое остаётся обычным текстом — незнакомое слово,
 * покрашенное как ключевое, врёт убедительнее, чем некрашеное.
 *
 * Куски идут подряд и покрывают текст целиком, без дыр и нахлёстов: платформа именно этого и ждёт.
 */
object SqlTokens {
  enum class Kind {
    /** `SELECT`, `FROM`, `WHERE`… */
    KEYWORD,
    /** `COUNT`, `NOW`, `COALESCE`… — имя перед открывающей скобкой. */
    FUNCTION,
    /** `'строка'`, `$$тело$$` — литерал. */
    STRING,
    /** `"имя"` — идентификатор в кавычках. */
    IDENTIFIER,
    /** Число. */
    NUMBER,
    /** `--` до конца строки и `/* … */`. */
    COMMENT,
    /** `=`, `<>`, `+`, `(`, `,`, `;`… */
    OPERATOR,
    /** Всё остальное: имена таблиц, столбцов, псевдонимы. */
    PLAIN,
    WHITESPACE,
  }

  data class Token(val kind: Kind, val start: Int, val end: Int)

  /**
   * Ключевые слова — пересечение диалектов.
   *
   * Список закрытый и намеренно неполный: слово, которое красится ключевым в одном диалекте и
   * является именем столбца в другом (`value`, `key`, `type`), красить нельзя — человек решит, что
   * у него ошибка там, где всё верно.
   */
  val KEYWORDS: Set<String> = setOf(
    "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
    "CREATE", "ALTER", "DROP", "TABLE", "VIEW", "INDEX", "SCHEMA", "DATABASE", "SEQUENCE",
    "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON", "USING",
    "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "FETCH", "FIRST", "NEXT", "ROWS", "ONLY",
    "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE", "ILIKE", "IS", "NULL",
    "AS", "DISTINCT", "ALL", "UNION", "INTERSECT", "EXCEPT", "CASE", "WHEN", "THEN", "ELSE", "END",
    "PRIMARY", "FOREIGN", "KEY", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT", "CONSTRAINT",
    "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "WITH", "RETURNING", "CASCADE",
    "ASC", "DESC", "TRUE", "FALSE", "ADD", "COLUMN", "RENAME", "TO", "IF",
  )

  private const val OPERATORS = "=<>!+-*/%|&^~(),;."

  /**
   * Разбор.
   *
   * Правила границ те же, что у [SqlStatements] (кавычки, комментарии, долларовые кавычки
   * PostgreSQL) — иначе подсветка и разбиение на операторы разошлись бы, и человек видел бы
   * раскрашенным одно, а выполненным другое.
   */
  fun scan(text: String): List<Token> {
    val tokens = ArrayList<Token>()
    var index = 0
    while (index < text.length) {
      val char = text[index]
      when {
        char.isWhitespace() -> {
          val start = index
          while (index < text.length && text[index].isWhitespace()) index++
          tokens.add(Token(Kind.WHITESPACE, start, index))
        }
        char == '-' && index + 1 < text.length && text[index + 1] == '-' -> {
          val start = index
          while (index < text.length && text[index] != '\n') index++
          tokens.add(Token(Kind.COMMENT, start, index))
        }
        char == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
          val start = index
          index += 2
          while (index < text.length && !(text[index] == '*' && index + 1 < text.length && text[index + 1] == '/')) index++
          index = minOf(text.length, index + 2)
          tokens.add(Token(Kind.COMMENT, start, index))
        }
        char == '\'' || char == '"' -> {
          val quote = char
          val start = index
          index++
          while (index < text.length) {
            // Удвоенная кавычка внутри литерала — это кавычка, а не конец: без этого строка
            // «O''Connor» обрывалась бы посередине и красила остаток запроса как текст.
            if (text[index] == quote) {
              if (index + 1 < text.length && text[index + 1] == quote) index += 2 else { index++; break }
            }
            else index++
          }
          tokens.add(Token(if (quote == '"') Kind.IDENTIFIER else Kind.STRING, start, index))
        }
        char == '$' -> {
          val tag = dollarTagAt(text, index)
          if (tag == null) {
            tokens.add(Token(Kind.OPERATOR, index, index + 1)); index++
          }
          else {
            val start = index
            index += tag.length
            val close = text.indexOf(tag, index)
            index = if (close < 0) text.length else close + tag.length
            tokens.add(Token(Kind.STRING, start, index))
          }
        }
        char.isDigit() -> {
          val start = index
          while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
          tokens.add(Token(Kind.NUMBER, start, index))
        }
        char.isLetter() || char == '_' -> {
          val start = index
          while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
          val word = text.substring(start, index)
          val kind = when {
            word.uppercase() in KEYWORDS -> Kind.KEYWORD
            // Имя перед открывающей скобкой — вызов: так `count(` красится, а столбец `count` нет.
            isCallAhead(text, index) -> Kind.FUNCTION
            else -> Kind.PLAIN
          }
          tokens.add(Token(kind, start, index))
        }
        char in OPERATORS -> {
          tokens.add(Token(Kind.OPERATOR, index, index + 1)); index++
        }
        else -> {
          tokens.add(Token(Kind.PLAIN, index, index + 1)); index++
        }
      }
    }
    return tokens
  }

  /** `$$` или `$tag$` в этой позиции, или null — тогда это обычный доллар. */
  private fun dollarTagAt(text: String, index: Int): String? {
    var cursor = index + 1
    while (cursor < text.length && (text[cursor].isLetterOrDigit() || text[cursor] == '_')) cursor++
    return if (cursor < text.length && text[cursor] == '$') text.substring(index, cursor + 1) else null
  }

  private fun isCallAhead(text: String, from: Int): Boolean {
    var cursor = from
    while (cursor < text.length && text[cursor] == ' ') cursor++
    return cursor < text.length && text[cursor] == '('
  }
}
