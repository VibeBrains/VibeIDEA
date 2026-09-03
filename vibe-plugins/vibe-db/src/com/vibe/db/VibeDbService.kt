// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.sql.Connection

/**
 * Проектная обвязка над [JdbcSession]: пароли и жизненный цикл.
 *
 * Сама работа с базой живёт в [JdbcSession] и проверяется тестом на настоящей SQLite. Здесь —
 * только то, ради чего нужен проект: пароль из связки ключей системы. В файле проекта его нет и
 * быть не может, файл живёт в git.
 */
@Service(Service.Level.PROJECT)
class VibeDbService(private val project: Project) {
  private val session = JdbcSession()

  private fun credentials(source: DataSources.DataSource): CredentialAttributes =
    CredentialAttributes(generateServiceName("VibeIDEA Database", source.id), source.user)

  fun password(source: DataSources.DataSource): String? =
    PasswordSafe.instance.get(credentials(source))?.getPasswordAsString()

  fun storePassword(source: DataSources.DataSource, password: String?) {
    PasswordSafe.instance.set(credentials(source), password?.let { Credentials(source.user, it) })
  }

  /** Блокирует поток; вызывать только из фонового. */
  fun connect(source: DataSources.DataSource): Result<Connection> = session.connect(source, password(source))

  fun execute(connection: Connection, sql: String, maxRows: Int = QueryLimit.PREVIEW_ROWS): JdbcSession.Outcome =
    session.execute(connection, sql, maxRows)

  fun tables(connection: Connection): List<DbCatalog.Table> = session.tables(connection)

  fun columns(connection: Connection, table: DbCatalog.Table): List<DbCatalog.Column> = session.columns(connection, table)

  companion object {
    fun getInstance(project: Project): VibeDbService = project.service()
  }
}
