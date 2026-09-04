// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.ResultSet
import java.util.Properties

/**
 * Всё общение с базой — и ни одной зависимости от IDE.
 *
 * Значения по умолчанию здесь — КОНСТАНТЫ, а не настройки: чтение настроек требует запущенного
 * приложения, и стоило поставить сюда `DbSettings.previewRows`, как тесты на настоящей базе
 * перестали работать вне IDE. Настройки подставляет [VibeDbService] — он и есть край, где
 * появляется окружение.
 *
 * Отдельно от [VibeDbService] ровно затем, чтобы это можно было проверить на настоящей базе:
 * сервису нужен проект (ради связки ключей), а проверять надо не связку, а подключение, чтение
 * метаданных и разбор результата. Тест на временном файле SQLite делает это без сети и сервера.
 */
class JdbcSession {
  /** Загрузчик на каждый jar: два драйвера разных версий в одном classpath однажды встретятся. */
  private val loaders = HashMap<String, URLClassLoader>()

  /**
   * Драйвер из указанного jar, или null — тогда подключение идёт через `DriverManager`
   * (драйвер уже на classpath).
   */
  fun driver(driverPath: String?, driverClass: String?): Result<Driver?> {
    val jar = driverPath?.takeIf { it.isNotBlank() } ?: return Result.success(null)
    val path = runCatching { Path.of(jar) }.getOrNull()
    if (path == null || !Files.isRegularFile(path)) return Result.failure(NoSuchFileException(java.io.File(jar)))
    val loader = loaders.getOrPut(jar) { URLClassLoader(arrayOf<URL>(path.toUri().toURL()), javaClass.classLoader) }
    return runCatching {
      if (driverClass != null) loader.loadClass(driverClass).getDeclaredConstructor().newInstance() as Driver
      // Современный jar объявляет себя сервисом; старый — нет, и тогда нужен явный класс.
      else java.util.ServiceLoader.load(Driver::class.java, loader).firstOrNull()
    }
  }

  fun connect(source: DataSources.DataSource, password: String?): Result<Connection> {
    val properties = Properties().apply {
      source.user?.let { setProperty("user", it) }
      password?.let { setProperty("password", it) }
    }
    val found = driver(source.driverPath, source.driverClass).getOrElse { return Result.failure(it) }
    return runCatching {
      // Свой драйвер зовём напрямую: DriverManager не видит классы чужого загрузчика.
      found?.connect(source.url, properties)
        ?: java.sql.DriverManager.getConnection(source.url, properties)
        ?: error(source.url)
    }
  }

  /**
   * Выполняет один оператор.
   *
   * Предел строк ставится и здесь, а не только в тексте запроса: `setMaxRows` останавливает выборку
   * на стороне драйвера, и миллион строк не приедет в память, даже если `SELECT * FROM` написан
   * руками.
   */
  fun execute(
    connection: Connection,
    sql: String,
    maxRows: Int = DbSettings.DEFAULT_PREVIEW_ROWS,
    timeoutSeconds: Int = DbSettings.DEFAULT_QUERY_TIMEOUT_SECONDS,
  ): Outcome {
    val started = System.nanoTime()
    return try {
      connection.createStatement().use { statement ->
        runCatching { statement.queryTimeout = timeoutSeconds }
        statement.maxRows = maxRows + 1
        val hasResult = statement.execute(sql)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (!hasResult) return Outcome.Updated(statement.updateCount, elapsed)
        statement.resultSet.use { rs -> Outcome.Rows(read(rs, maxRows), elapsed) }
      }
    }
    catch (e: Exception) {
      Outcome.Failed(e.message ?: e.javaClass.simpleName)
    }
  }

  sealed interface Outcome {
    data class Rows(val table: ResultTable.Table, val elapsedMs: Long) : Outcome
    /** Оператор изменил данные: число строк — единственный ответ, который у нас есть. */
    data class Updated(val count: Int, val elapsedMs: Long) : Outcome
    data class Failed(val message: String) : Outcome
  }

  private fun read(rs: ResultSet, maxRows: Int): ResultTable.Table {
    val meta = rs.metaData
    val labels = (1..meta.columnCount).map { meta.getColumnLabel(it) ?: meta.getColumnName(it) ?: "?" }
    val columns = ResultTable.uniqueLabels(labels).mapIndexed { index, label ->
      ResultTable.Column(label, meta.getColumnTypeName(index + 1) ?: "")
    }
    val rows = ArrayList<List<ResultTable.Cell>>()
    var truncated = false
    while (rs.next()) {
      if (rows.size >= maxRows) { truncated = true; break }
      rows.add((1..meta.columnCount).map { index ->
        when (val value = rs.getObject(index)) {
          null -> ResultTable.Cell.Null
          is ByteArray -> ResultTable.Cell.Binary(value.size)
          else -> ResultTable.Cell.Text(value.toString())
        }
      })
    }
    return ResultTable.Table(columns, rows, truncated)
  }

  /** Таблицы и представления базы — для дерева объектов. */
  fun tables(connection: Connection): List<DbCatalog.Table> {
    val result = ArrayList<DbCatalog.Table>()
    connection.metaData.getTables(null, null, "%", arrayOf("TABLE", "VIEW")).use { rs ->
      while (rs.next()) {
        val name = rs.getString("TABLE_NAME") ?: continue
        result.add(
          DbCatalog.Table(
            schema = rs.getString("TABLE_SCHEM")?.takeIf { it.isNotBlank() },
            name = name,
            kind = DbCatalog.kindOf(rs.getString("TABLE_TYPE")),
          )
        )
      }
    }
    return result
  }

  /**
   * Первичный ключ таблицы — единственный способ адресовать строку при правке.
   *
   * Пустой список означает «править нельзя», и это ответ, а не сбой: у таблицы без ключа нет
   * выражения, которое отличает одну строку от её точного двойника.
   */
  fun primaryKey(connection: Connection, schema: String?, table: String): List<String> {
    val keys = ArrayList<Pair<Int, String>>()
    runCatching {
      connection.metaData.getPrimaryKeys(null, schema, table).use { rs ->
        while (rs.next()) {
          val name = rs.getString("COLUMN_NAME") ?: continue
          keys.add(rs.getInt("KEY_SEQ") to name)
        }
      }
    }
    return keys.sortedBy { it.first }.map { it.second }
  }

  /**
   * Правки одной транзакцией: либо применились все, либо ни одной.
   *
   * По отдельности они оставили бы таблицу в состоянии, которого не хотел никто: половина ячеек
   * новая, половина старая, и какая именно половина — видно только по номеру упавшего запроса.
   */
  fun applyAll(connection: Connection, statements: List<String>, timeoutSeconds: Int = DbSettings.DEFAULT_QUERY_TIMEOUT_SECONDS): Outcome {
    if (statements.isEmpty()) return Outcome.Updated(0, 0)
    val started = System.nanoTime()
    val restore = runCatching { connection.autoCommit }.getOrDefault(true)
    return try {
      connection.autoCommit = false
      var total = 0
      connection.createStatement().use { statement ->
        runCatching { statement.queryTimeout = timeoutSeconds }
        for (sql in statements) total += statement.executeUpdate(sql)
      }
      connection.commit()
      Outcome.Updated(total, (System.nanoTime() - started) / 1_000_000)
    }
    catch (e: Exception) {
      runCatching { connection.rollback() }
      Outcome.Failed(e.message ?: e.javaClass.simpleName)
    }
    finally {
      runCatching { connection.autoCommit = restore }
    }
  }

  fun columns(connection: Connection, table: DbCatalog.Table): List<DbCatalog.Column> {
    val result = ArrayList<DbCatalog.Column>()
    connection.metaData.getColumns(null, table.schema, table.name, "%").use { rs ->
      while (rs.next()) {
        val name = rs.getString("COLUMN_NAME") ?: continue
        result.add(
          DbCatalog.Column(
            name = name,
            typeName = rs.getString("TYPE_NAME") ?: "",
            nullable = rs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls,
          )
        )
      }
    }
    return result
  }
}
