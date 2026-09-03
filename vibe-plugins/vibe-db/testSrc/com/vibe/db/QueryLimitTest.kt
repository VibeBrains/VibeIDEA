// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryLimitTest {
  @Test
  fun `предпросмотр таблицы собирается с кавычками и пределом`() {
    assertEquals("""SELECT * FROM "public"."users" LIMIT 200""", QueryLimit.preview("users", "public"))
    assertEquals("""SELECT * FROM "users" LIMIT 10""", QueryLimit.preview("users", null, rows = 10))
  }

  @Test
  fun `кавычка в имени таблицы удваивается`() {
    // Редкость, но такая таблица собрала бы синтаксически битый запрос — и это выглядело бы как
    // «инструмент не умеет открывать таблицы».
    assertEquals("""SELECT * FROM "стран""ный" LIMIT 200""", QueryLimit.preview("стран\"ный", null))
  }

  @Test
  fun `свой предел человека виден и не подменяется`() {
    assertTrue(QueryLimit.hasOwnLimit("select * from t limit 5"))
    assertTrue(QueryLimit.hasOwnLimit("SELECT * FROM t\nFETCH FIRST 10 ROWS ONLY"))
    assertTrue(QueryLimit.hasOwnLimit("SELECT TOP 10 * FROM t"))
    assertFalse(QueryLimit.hasOwnLimit("select * from t"))
  }
}
