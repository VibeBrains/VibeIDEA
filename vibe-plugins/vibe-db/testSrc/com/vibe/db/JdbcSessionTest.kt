// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Слой JDBC на НАСТОЯЩЕЙ базе.
 *
 * Драйвер SQLite уже лежит в дереве (им пользуется `platform/sqlite`), поэтому проверка не требует
 * ни сети, ни сервера, ни установки чего-либо: временный файл базы создаётся здесь же. Без этого
 * весь путь «подключиться → прочитать метаданные → выполнить → разобрать результат» был бы проверен
 * только глазами на живой базе — то есть однажды и никогда больше.
 */
class JdbcSessionTest {
  private val session = JdbcSession()

  private fun source(file: Path, driverPath: String? = null) = DataSources.DataSource(
    id = "test", name = "test", url = "jdbc:sqlite:${file.toAbsolutePath()}",
    user = null, driverPath = driverPath, driverClass = null,
  )

  private fun withDatabase(driverPath: String? = null, block: (Connection) -> Unit) {
    val file = Files.createTempFile("vibe-db-", ".sqlite")
    Files.deleteIfExists(file)
    try {
      val connection = session.connect(source(file, driverPath), password = null).getOrThrow()
      connection.use {
        it.createStatement().use { st ->
          st.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, note TEXT, avatar BLOB)")
          st.executeUpdate("CREATE VIEW active AS SELECT * FROM users WHERE name IS NOT NULL")
          st.executeUpdate("INSERT INTO users VALUES (1, 'Иван', NULL, x'0102030405')")
          st.executeUpdate("INSERT INTO users VALUES (2, NULL, '', NULL)")
        }
        block(it)
      }
    }
    finally {
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun `дерево объектов видит и таблицу, и представление`() {
    withDatabase { connection ->
      val tables = session.tables(connection)
      assertEquals(setOf("users" to DbCatalog.Kind.TABLE, "active" to DbCatalog.Kind.VIEW),
                   tables.map { it.name to it.kind }.toSet())
      // SQLite не знает схем — таблицы собираются в одну безымянную группу, а не в узел «null».
      val grouped = DbCatalog.group(tables)
      assertEquals(1, grouped.size)
      assertEquals("", grouped.single().name)
    }
  }

  @Test
  fun `столбцы читаются с типами и признаком NULL`() {
    withDatabase { connection ->
      val users = session.tables(connection).first { it.name == "users" }
      val columns = session.columns(connection, users)
      assertEquals(listOf("id", "name", "note", "avatar"), columns.map { it.name })
      assertEquals("TEXT", columns.first { it.name == "name" }.typeName.uppercase())
      assertTrue(columns.first { it.name == "name" }.nullable)
    }
  }

  @Test
  fun `NULL, пустая строка и двоичное различаются в результате`() {
    // Ради этого и написан тест на живой базе: три «пустых» значения, которые нельзя путать.
    withDatabase { connection ->
      val rows = session.execute(connection, "SELECT name, note, avatar FROM users ORDER BY id") as JdbcSession.Outcome.Rows
      val first = rows.table.rows[0]
      val second = rows.table.rows[1]
      assertEquals(ResultTable.Cell.Text("Иван"), first[0])
      assertEquals(ResultTable.Cell.Null, first[1], "NULL в базе — это не пустая строка")
      assertEquals(ResultTable.Cell.Binary(5), first[2])
      assertEquals(ResultTable.Cell.Null, second[0])
      assertEquals(ResultTable.Cell.Text(""), second[1], "пустая строка — это не NULL")
      assertEquals(ResultTable.Cell.Null, second[2])
    }
  }

  @Test
  fun `предел строк ставится на стороне драйвера`() {
    withDatabase { connection ->
      session.execute(connection, "INSERT INTO users SELECT 3, 'a', NULL, NULL")
      val limited = session.execute(connection, "SELECT * FROM users", maxRows = 1) as JdbcSession.Outcome.Rows
      assertEquals(1, limited.table.rowCount)
      assertTrue(limited.table.truncated, "не показанные строки должны быть названы, а не отброшены молча")
    }
  }

  @Test
  fun `изменяющий оператор возвращает число строк, а не таблицу`() {
    withDatabase { connection ->
      val updated = session.execute(connection, "UPDATE users SET note = 'x' WHERE id = 1")
      assertEquals(1, (updated as JdbcSession.Outcome.Updated).count)
    }
  }

  @Test
  fun `битый запрос возвращает причину, а не исключение`() {
    withDatabase { connection ->
      val failed = session.execute(connection, "SELECT * FROM нет_такой_таблицы")
      assertTrue(failed is JdbcSession.Outcome.Failed)
      assertTrue((failed as JdbcSession.Outcome.Failed).message.isNotBlank())
    }
  }

  @Test
  fun `одинаковые имена столбцов разводятся на настоящем запросе`() {
    withDatabase { connection ->
      val rows = session.execute(connection, "SELECT id, id FROM users LIMIT 1") as JdbcSession.Outcome.Rows
      assertEquals(listOf("id", "id (2)"), rows.table.columns.map { it.label })
    }
  }

  @Test
  fun `драйвер из указанного jar грузится своим загрузчиком`() {
    // Путь к jar берём у самого драйвера: так проверяется ветка driverPath, а не только classpath.
    // Через ресурс, а не через codeSource: под Bazel второй пуст, и тест молча ничего бы не проверил.
    val resource = Class.forName("org.sqlite.JDBC").classLoader.getResource("org/sqlite/JDBC.class")
    assertNotNull(resource, "класс драйвера должен быть на classpath теста")
    val jar = resource.toString().removePrefix("jar:").substringBefore("!/").removePrefix("file:")
      .let { runCatching { Path.of(it) }.getOrNull() }
    assertNotNull(jar, "не удалось найти jar драйвера — проверить ветку driverPath нечем")
    assertTrue(Files.isRegularFile(jar), "ожидался jar, а не каталог классов: $jar")
    val driver = session.driver(jar.toString(), null).getOrThrow()
    assertNotNull(driver, "ServiceLoader должен найти драйвер в jar")
    withDatabase(driverPath = jar.toString()) { connection ->
      assertEquals(2, (session.execute(connection, "SELECT * FROM users") as JdbcSession.Outcome.Rows).table.rowCount)
    }
  }

  @Test
  fun `несуществующий jar — отказ с причиной, а не падение`() {
    assertTrue(session.driver("/нет/такого.jar", null).isFailure)
  }
}
