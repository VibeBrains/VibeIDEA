// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DbCatalogTest {
  private fun table(schema: String?, name: String, kind: DbCatalog.Kind = DbCatalog.Kind.TABLE) =
    DbCatalog.Table(schema, name, kind)

  @Test
  fun `служебные схемы спрятаны, но не потеряны`() {
    val tables = listOf(table("public", "users"), table("pg_catalog", "pg_class"), table("information_schema", "tables"))
    assertEquals(listOf("public"), DbCatalog.group(tables).map { it.name })
    assertEquals(3, DbCatalog.group(tables, showSystem = true).sumOf { it.tables.size })
    assertTrue(DbCatalog.isSystem("pg_temp_3"))
    assertFalse(DbCatalog.isSystem("app"))
  }

  @Test
  fun `таблицы без схемы собираются в одну группу`() {
    // SQLite не знает схем; узел «null» объяснял бы человеку устройство JDBC вместо его данных.
    val grouped = DbCatalog.group(listOf(table(null, "users"), table(null, "orders")))
    assertEquals(1, grouped.size)
    assertEquals("", grouped.single().name)
    assertEquals(listOf("orders", "users"), grouped.single().tables.map { it.name })
  }

  @Test
  fun `таблицы идут раньше представлений, внутри — по алфавиту`() {
    val grouped = DbCatalog.group(listOf(
      table("s", "z_view", DbCatalog.Kind.VIEW),
      table("s", "b_table"),
      table("s", "a_view", DbCatalog.Kind.VIEW),
    ))
    assertEquals(listOf("b_table", "a_view", "z_view"), grouped.single().tables.map { it.name })
  }

  @Test
  fun `поиск находит и таблицу, и схему целиком`() {
    val schemas = DbCatalog.group(listOf(table("app", "users"), table("app", "orders"), table("logs", "events")))
    assertEquals(listOf("users"), DbCatalog.filter(schemas, "user").flatMap { it.tables }.map { it.name })
    // Совпало имя схемы — показываем её целиком: человек искал именно её.
    assertEquals(listOf("events"), DbCatalog.filter(schemas, "logs").flatMap { it.tables }.map { it.name })
    assertEquals(3, DbCatalog.filter(schemas, "  ").sumOf { it.tables.size })
  }

  @Test
  fun `вид объекта опознаётся по типу JDBC`() {
    assertEquals(DbCatalog.Kind.TABLE, DbCatalog.kindOf("BASE TABLE"))
    assertEquals(DbCatalog.Kind.VIEW, DbCatalog.kindOf("materialized view"))
    assertEquals(DbCatalog.Kind.OTHER, DbCatalog.kindOf(null))
  }
}
