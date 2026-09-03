// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlStatementsTest {
  @Test
  fun `точка с запятой внутри строки не делит запрос`() {
    // Наивное деление по «;» разъехалось бы здесь на два оператора и отправило серверу мусор.
    val statements = SqlStatements.split("""SELECT 'a;b' AS x, "col;name" FROM t;SELECT 2""")
    assertEquals(2, statements.size)
    assertEquals("""SELECT 'a;b' AS x, "col;name" FROM t""", statements[0].text)
  }

  @Test
  fun `удвоенная кавычка внутри строки — это кавычка, а не конец строки`() {
    val statements = SqlStatements.split("SELECT 'it''s; fine' FROM t; SELECT 1")
    assertEquals(2, statements.size)
    assertEquals("SELECT 'it''s; fine' FROM t", statements[0].text)
  }

  @Test
  fun `комментарии не делят и не теряются`() {
    val statements = SqlStatements.split(
      """
      -- берём всех; даже уволенных
      SELECT * FROM users;
      /* блок; с точкой с запятой */
      SELECT 1;
      """.trimIndent()
    )
    assertEquals(2, statements.size)
    assertTrue(statements[0].text.startsWith("-- берём всех"))
    // Заголовок для списка — первая содержательная строка, а не комментарий.
    assertEquals("SELECT * FROM users", statements[0].title)
  }

  @Test
  fun `долларовые кавычки PostgreSQL держат тело функции целиком`() {
    val sql = """
      CREATE FUNCTION f() RETURNS int AS ${'$'}${'$'}
      BEGIN
        SELECT 1;
        SELECT 2;
      END;
      ${'$'}${'$'} LANGUAGE plpgsql;
      SELECT f();
    """.trimIndent()
    val statements = SqlStatements.split(sql)
    assertEquals(2, statements.size, "тело функции — один оператор, а не четыре")
    assertTrue(statements[0].text.contains("END;"))
  }

  @Test
  fun `оператор находится по строке курсора`() {
    val statements = SqlStatements.split("SELECT 1;\n\nSELECT 2;\n")
    assertEquals("SELECT 1", SqlStatements.statementAt(statements, 0)?.text)
    assertEquals("SELECT 2", SqlStatements.statementAt(statements, 2)?.text)
  }

  @Test
  fun `читающий оператор отличается от меняющего`() {
    assertTrue(SqlStatements.isReadOnly("SELECT * FROM t"))
    assertTrue(SqlStatements.isReadOnly("  with x as (select 1) select * from x"))
    assertTrue(SqlStatements.isReadOnly("-- комментарий\nSHOW TABLES"))
    assertFalse(SqlStatements.isReadOnly("DELETE FROM users"))
    assertFalse(SqlStatements.isReadOnly("update t set a = 1"))
  }
}
