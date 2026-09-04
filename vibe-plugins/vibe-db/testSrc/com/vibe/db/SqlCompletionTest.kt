// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Подсказки: правильная подсказка не в том, чтобы предложить всё, а в том, чтобы не предложить чужое. */
class SqlCompletionTest {
  private val users = DbCatalog.Table(null, "users", DbCatalog.Kind.TABLE)
  private val orders = DbCatalog.Table(null, "orders", DbCatalog.Kind.TABLE)
  private val schemas = listOf(DbCatalog.Schema("", listOf(users, orders)))

  private val columns: (DbCatalog.Table) -> List<DbCatalog.Column> = { table ->
    when (table.name) {
      "users" -> listOf(DbCatalog.Column("id", "INTEGER", false), DbCatalog.Column("name", "TEXT", true))
      "orders" -> listOf(DbCatalog.Column("id", "INTEGER", false), DbCatalog.Column("total", "NUMERIC", true))
      else -> emptyList()
    }
  }

  /** Курсор обозначается `|`: подсказки зависят и от текста слева, и от оператора целиком. */
  private fun suggest(text: String): List<String> {
    val caret = text.indexOf('|')
    val clean = text.replace("|", "")
    return SqlCompletion.suggest(clean, if (caret < 0) clean.length else caret, schemas, columns).map { it.text }
  }

  @Test
  fun `после FROM предлагаются таблицы`() {
    assertEquals(listOf("users", "orders"), suggest("SELECT * FROM |"))
  }

  @Test
  fun `набранный префикс сужает список`() {
    assertEquals(listOf("orders"), suggest("SELECT * FROM or|"))
  }

  @Test
  fun `в SELECT предлагаются столбцы упомянутых таблиц`() {
    assertEquals(listOf("id", "total"), suggest("SELECT | FROM orders"))
    assertTrue(suggest("SELECT id, t| FROM orders").contains("total"))
  }

  @Test
  fun `псевдоним ограничивает столбцы своей таблицей`() {
    val fromUsers = suggest("SELECT u.| FROM users u JOIN orders o ON o.id = u.id")
    assertEquals(listOf("id", "name"), fromUsers)
    val fromOrders = suggest("SELECT o.| FROM users u JOIN orders o ON o.id = u.id")
    assertEquals(listOf("id", "total"), fromOrders)
  }

  @Test
  fun `имя таблицы работает как псевдоним`() {
    assertEquals(listOf("id", "total"), suggest("SELECT orders.| FROM orders"))
  }

  @Test
  fun `вне известной позиции подсказок нет`() {
    assertEquals(emptyList(), suggest("VACUUM |"))
  }

  @Test
  fun `столбцы неупомянутой таблицы не предлагаются`() {
    assertTrue(suggest("SELECT | FROM orders").none { it == "name" }, "name есть только у users")
  }
}
