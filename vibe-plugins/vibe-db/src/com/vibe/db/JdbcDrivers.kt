// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import java.nio.file.Files
import java.nio.file.Path

/**
 * Драйверы, которые IDE умеет скачать по просьбе человека.
 *
 * Поставлять их нельзя: у драйверов разные лицензии, и класть чужой jar в дистрибутив значит
 * принимать чужие условия за пользователя. Но требовать «найдите и скачайте jar сами» — это стена
 * на первом же шаге: человек хотел посмотреть таблицу, а получил задание по сборке.
 *
 * Середина ровно та же, что у языковых серверов в докторе: скачиваем **по явному нажатию**, из
 * официального репозитория, с закреплённой версией и **проверкой sha256**. Хеш здесь не
 * формальность: подменённый jar — это код, который выполнится в IDE с правами пользователя.
 *
 * Чистая часть: где лежит, как называется, чем проверяется. Скачивание — [VibeDbService].
 */
object JdbcDrivers {
  data class Driver(
    val kind: DataSources.Kind,
    val title: String,
    val version: String,
    /** Путь внутри Maven Central — единственный источник, откуда мы качаем. */
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val licence: String,
    /** Класс драйвера — нужен только тем, кто не объявляет себя сервисом. */
    val driverClass: String? = null,
  ) {
    val fileName: String get() = path.substringAfterLast('/')
    val url: String get() = "$MAVEN_CENTRAL/$path"
  }

  const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

  /**
   * Закреплённые версии и хеши. Проверены загрузкой 03.09.2026 — это не переписанные откуда-то
   * числа, а результат `shasum -a 256` над скачанными файлами.
   */
  val KNOWN: List<Driver> = listOf(
    Driver(
      kind = DataSources.Kind.POSTGRES, title = "PostgreSQL", version = "42.7.7",
      path = "org/postgresql/postgresql/42.7.7/postgresql-42.7.7.jar",
      sha256 = "157963d60ae66d607e09466e8c0cdf8087e9cb20d0159899ffca96bca2528460",
      sizeBytes = 1_098_916, licence = "BSD-2-Clause",
    ),
    Driver(
      kind = DataSources.Kind.SQLITE, title = "SQLite", version = "3.49.1.0",
      path = "org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar",
      sha256 = "5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291",
      sizeBytes = 14_317_659, licence = "Apache-2.0",
    ),
    Driver(
      kind = DataSources.Kind.MYSQL, title = "MySQL", version = "9.3.0",
      path = "com/mysql/mysql-connector-j/9.3.0/mysql-connector-j-9.3.0.jar",
      sha256 = "6c8e6692b521376d89bc5618c16cdeaf8c61854329f4fa25677ed08776c5bb76",
      sizeBytes = 2_593_726,
      // Условия называем прямо: скачивание — решение человека, и он должен знать, что принимает.
      licence = "GPL-2.0 + FOSS exception",
    ),
    Driver(
      kind = DataSources.Kind.MARIADB, title = "MariaDB", version = "3.5.3",
      path = "org/mariadb/jdbc/mariadb-java-client/3.5.3/mariadb-java-client-3.5.3.jar",
      sha256 = "85c4ba2f221d0dfd439c26affbb294f784960763544263c65aba9c2c76858706",
      sizeBytes = 746_140, licence = "LGPL-2.1",
    ),
    Driver(
      kind = DataSources.Kind.H2, title = "H2", version = "2.3.232",
      path = "com/h2database/h2/2.3.232/h2-2.3.232.jar",
      sha256 = "8dae62d22db8982c3dcb3826edb9c727c5d302063a67eef7d63d82de401f07d3",
      sizeBytes = 2_651_157, licence = "MPL-2.0 / EPL-1.0",
    ),
  )

  fun forUrl(url: String): Driver? = KNOWN.firstOrNull { it.kind == DataSources.kindOf(url) }

  /**
   * Куда кладём. Рядом с остальным нашим хозяйством в домашней папке, а не в проект: драйвер один
   * на машину, и десять проектов не должны носить десять копий одного jar.
   */
  fun directory(home: String): Path = Path.of(home, ".vibe", "drivers")

  fun target(home: String, driver: Driver): Path = directory(home).resolve(driver.fileName)

  fun isDownloaded(home: String, driver: Driver): Boolean {
    val path = target(home, driver)
    return Files.isRegularFile(path) && Files.size(path) == driver.sizeBytes
  }

  /** Совпал ли хеш. Отдельной функцией — её и проверяет тест на подменённом файле. */
  fun matches(bytes: ByteArray, driver: Driver): Boolean = sha256(bytes) == driver.sha256

  fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it) }
}
