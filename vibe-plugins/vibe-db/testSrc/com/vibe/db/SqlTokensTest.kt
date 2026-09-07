// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Подсветка SQL: слово, покрашенное неверно, врёт убедительнее, чем некрашеное. */
class SqlTokensTest {
  private fun kinds(text: String): List<Pair<SqlTokens.Kind, String>> =
    SqlTokens.scan(text).filter { it.kind != SqlTokens.Kind.WHITESPACE }
      .map { it.kind to text.substring(it.start, it.end) }

  @Test
  fun `куски покрывают текст целиком, без дыр и нахлёстов`() {
    val text = "SELECT id, name -- комментарий\nFROM users WHERE id = 1;"
    val tokens = SqlTokens.scan(text)
    assertEquals(0, tokens.first().start)
    assertEquals(text.length, tokens.last().end)
    tokens.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start, "дыра или нахлёст между кусками") }
  }

  @Test
  fun `ключевые слова узнаются в любом регистре`() {
    assertEquals(SqlTokens.Kind.KEYWORD, kinds("select 1").first().first)
    assertEquals(SqlTokens.Kind.KEYWORD, kinds("SeLeCt 1").first().first)
  }

  @Test
  fun `удвоенная кавычка не обрывает строку`() {
    // «O''Connor» — строка целиком; иначе остаток запроса красился бы как текст.
    val tokens = kinds("SELECT 'O''Connor' FROM t")
    assertEquals(SqlTokens.Kind.STRING, tokens[1].first)
    assertEquals("'O''Connor'", tokens[1].second)
  }

  @Test
  fun `имя в двойных кавычках — идентификатор, а не строка`() {
    val tokens = kinds("""SELECT "user name" FROM t""")
    assertEquals(SqlTokens.Kind.IDENTIFIER, tokens[1].first)
  }

  @Test
  fun `долларовые кавычки PostgreSQL не разъезжаются`() {
    val body = "CREATE FUNCTION f() AS ${'$'}${'$'} SELECT 'a'; ${'$'}${'$'} LANGUAGE sql"
    val string = SqlTokens.scan(body).first { it.kind == SqlTokens.Kind.STRING }
    assertTrue(body.substring(string.start, string.end).contains("SELECT 'a';"),
               "тело функции — один кусок, а не десяток операторов")
  }

  @Test
  fun `комментарии обоих видов`() {
    assertEquals(SqlTokens.Kind.COMMENT, kinds("-- всё\nSELECT 1").first().first)
    val block = kinds("/* всё\nещё всё */ SELECT 1")
    assertEquals(SqlTokens.Kind.COMMENT, block.first().first)
    assertEquals(SqlTokens.Kind.KEYWORD, block[1].first)
  }

  @Test
  fun `вызов отличается от столбца с тем же именем`() {
    // count( — функция; просто count — столбец, и красить его нельзя.
    assertEquals(SqlTokens.Kind.FUNCTION, kinds("SELECT count(*) FROM t")[1].first)
    assertEquals(SqlTokens.Kind.PLAIN, kinds("SELECT count FROM t")[1].first)
  }

  @Test
  fun `имена столбцов и таблиц остаются обычным текстом`() {
    val tokens = kinds("SELECT value, type FROM settings")
    assertTrue(tokens.filter { it.second in setOf("value", "type", "settings") }
                 .all { it.first == SqlTokens.Kind.PLAIN },
               "слово, ключевое в одном диалекте и столбец в другом, красить нельзя")
  }

  @Test
  fun `незакрытые комментарий и строка не роняют разбор`() {
    assertEquals("текста хватает", "текста хватает")
    val unclosedComment = SqlTokens.scan("SELECT 1 /* и всё")
    assertEquals("SELECT 1 /* и всё".length, unclosedComment.last().end)
    val unclosedString = SqlTokens.scan("SELECT 'и всё")
    assertEquals("SELECT 'и всё".length, unclosedString.last().end)
  }
}
