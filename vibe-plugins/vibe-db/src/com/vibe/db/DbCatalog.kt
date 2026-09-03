// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

/**
 * Дерево объектов базы: схемы, таблицы, столбцы — и правила, по которым мы его показываем.
 *
 * Чистая часть отдельно от `DatabaseMetaData`, потому что решения здесь не про JDBC: какие схемы
 * прятать, как сортировать, что считать таблицей. Ошибка в них выглядит как «инструмент не видит
 * мою таблицу», и ловить её на живой базе — самый дорогой способ.
 */
object DbCatalog {
  data class Column(val name: String, val typeName: String, val nullable: Boolean)
  data class Table(val schema: String?, val name: String, val kind: Kind)
  data class Schema(val name: String, val tables: List<Table>)

  enum class Kind { TABLE, VIEW, OTHER }

  fun kindOf(jdbcType: String?): Kind = when (jdbcType?.uppercase()) {
    "TABLE", "BASE TABLE" -> Kind.TABLE
    "VIEW", "MATERIALIZED VIEW" -> Kind.VIEW
    else -> Kind.OTHER
  }

  /**
   * Служебные схемы, которые по умолчанию не показываем.
   *
   * Их десятки, и среди них теряются две схемы проекта. Прятать — не то же самое, что не иметь:
   * переключатель «показать системные» остаётся, но по умолчанию человек видит своё.
   */
  private val SYSTEM_SCHEMAS = setOf(
    "information_schema", "pg_catalog", "pg_toast", "performance_schema", "mysql", "sys",
    "innodb", "tmp", "sysibm", "sqlite_temp_master",
  )

  fun isSystem(schema: String): Boolean =
    schema.lowercase() in SYSTEM_SCHEMAS || schema.lowercase().startsWith("pg_temp") || schema.lowercase().startsWith("pg_toast")

  /**
   * Раскладывает плоский список таблиц по схемам.
   *
   * Таблицы без схемы (SQLite) собираются в одну безымянную группу: показывать пользователю
   * пустой узел «null» значит объяснять ему устройство JDBC вместо его же данных.
   */
  fun group(tables: List<Table>, showSystem: Boolean = false): List<Schema> {
    val visible = tables.filter { showSystem || it.schema == null || !isSystem(it.schema) }
    return visible.groupBy { it.schema.orEmpty() }
      .map { (schema, list) -> Schema(schema, list.sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))) }
      .sortedBy { it.name.lowercase() }
  }

  /** Поиск по дереву: по подстроке без учёта регистра, как ищут глазами. */
  fun filter(schemas: List<Schema>, query: String): List<Schema> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return schemas
    return schemas.mapNotNull { schema ->
      val matched = schema.tables.filter { it.name.lowercase().contains(needle) }
      when {
        matched.isNotEmpty() -> schema.copy(tables = matched)
        // Совпало имя схемы — показываем её целиком: человек искал именно её.
        schema.name.lowercase().contains(needle) -> schema
        else -> null
      }
    }
  }
}
