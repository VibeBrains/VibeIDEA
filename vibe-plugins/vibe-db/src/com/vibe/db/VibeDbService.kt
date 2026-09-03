// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.ResultSet
import java.util.Properties

/**
 * Единственное место, где плагин ходит в базу.
 *
 * Драйверы мы НЕ поставляем: у них разные лицензии (MySQL Connector/J — GPL с исключением для
 * свободного ПО), и класть их в дистрибутив значит принимать чужие условия за пользователя.
 * Человек указывает jar сам, а мы грузим его в отдельном загрузчике классов — иначе два драйвера
 * разных версий однажды встретятся в одном classpath, и разбираться в этом будет невозможно.
 */
@Service(Service.Level.PROJECT)
class VibeDbService(private val project: Project) {
  private val loaders = HashMap<String, URLClassLoader>()

  sealed interface Outcome {
    data class Rows(val table: ResultTable.Table, val elapsedMs: Long) : Outcome
    /** Оператор изменил данные: число строк — единственный ответ, который у нас есть. */
    data class Updated(val count: Int, val elapsedMs: Long) : Outcome
    data class Failed(val trouble: Trouble, val message: String) : Outcome
  }

  /** Что пошло не так — кодом; фразу собирает интерфейс. */
  enum class Trouble { NO_DRIVER, DRIVER_JAR_MISSING, CONNECT_FAILED, QUERY_FAILED }

  /** Пароль живёт в связке ключей системы, а не в файле проекта и не в настройках IDE. */
  private fun credentials(source: DataSources.DataSource): CredentialAttributes =
    CredentialAttributes(generateServiceName("VibeIDEA Database", source.id), source.user)

  fun password(source: DataSources.DataSource): String? =
    PasswordSafe.instance.get(credentials(source))?.getPasswordAsString()

  fun storePassword(source: DataSources.DataSource, password: String?) {
    PasswordSafe.instance.set(credentials(source), password?.let { Credentials(source.user, it) })
  }

  private fun driver(source: DataSources.DataSource): Result<Driver?> {
    val jar = source.driverPath?.takeIf { it.isNotBlank() } ?: return Result.success(null)
    val path = runCatching { Path.of(jar) }.getOrNull()
    if (path == null || !Files.isRegularFile(path)) return Result.failure(IllegalStateException(jar))
    val loader = loaders.getOrPut(jar) { URLClassLoader(arrayOf<URL>(path.toUri().toURL()), javaClass.classLoader) }
    val explicit = source.driverClass
    val driver = if (explicit != null) {
      loader.loadClass(explicit).getDeclaredConstructor().newInstance() as Driver
    }
    else {
      // Современный jar объявляет себя сервисом; старый — нет, и тогда нужен явный класс.
      java.util.ServiceLoader.load(Driver::class.java, loader).firstOrNull()
    }
    return Result.success(driver)
  }

  /** Открывает соединение. Блокирует поток; вызывать только из фонового. */
  fun connect(source: DataSources.DataSource): Result<Connection> {
    val properties = Properties().apply {
      source.user?.let { setProperty("user", it) }
      password(source)?.let { setProperty("password", it) }
    }
    val found = driver(source).getOrElse { return Result.failure(it) }
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
   * Предел строк ставим и здесь, а не только в тексте запроса: `setMaxRows` останавливает выборку
   * на стороне драйвера, и миллион строк не приедет в память, даже если человек выполнил
   * `SELECT * FROM` большой таблицы своими руками.
   */
  fun execute(
    connection: Connection,
    sql: String,
    maxRows: Int = QueryLimit.PREVIEW_ROWS,
    timeoutSeconds: Int = 30,
  ): Outcome {
    val started = System.nanoTime()
    return try {
      connection.createStatement().use { statement ->
        statement.queryTimeout = timeoutSeconds
        statement.maxRows = maxRows + 1
        val hasResult = statement.execute(sql)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (!hasResult) return Outcome.Updated(statement.updateCount, elapsed)
        statement.resultSet.use { rs -> Outcome.Rows(read(rs, maxRows), elapsed) }
      }
    }
    catch (e: Exception) {
      Outcome.Failed(Trouble.QUERY_FAILED, e.message ?: e.javaClass.simpleName)
    }
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

  /** Таблицы базы для дерева объектов. */
  fun tables(connection: Connection): List<DbCatalog.Table> {
    val meta = connection.metaData
    val result = ArrayList<DbCatalog.Table>()
    meta.getTables(null, null, "%", arrayOf("TABLE", "VIEW")).use { rs ->
      while (rs.next()) {
        result.add(
          DbCatalog.Table(
            schema = rs.getString("TABLE_SCHEM")?.takeIf { it.isNotBlank() },
            name = rs.getString("TABLE_NAME") ?: continue,
            kind = DbCatalog.kindOf(rs.getString("TABLE_TYPE")),
          )
        )
      }
    }
    return result
  }

  fun columns(connection: Connection, table: DbCatalog.Table): List<DbCatalog.Column> {
    val result = ArrayList<DbCatalog.Column>()
    connection.metaData.getColumns(null, table.schema, table.name, "%").use { rs ->
      while (rs.next()) {
        result.add(
          DbCatalog.Column(
            name = rs.getString("COLUMN_NAME") ?: continue,
            typeName = rs.getString("TYPE_NAME") ?: "",
            nullable = rs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls,
          )
        )
      }
    }
    return result
  }

  companion object {
    fun getInstance(project: Project): VibeDbService = project.service()
  }
}
