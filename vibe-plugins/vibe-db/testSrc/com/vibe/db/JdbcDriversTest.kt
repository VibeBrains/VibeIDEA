// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcDriversTest {
  @Test
  fun `у каждого драйвера закреплены версия, хеш, размер и лицензия`() {
    // Хеш здесь не формальность: подменённый jar — это код, который выполнится в IDE с правами
    // пользователя. Лицензия названа потому, что скачивание — решение человека.
    assertTrue(JdbcDrivers.KNOWN.isNotEmpty())
    for (driver in JdbcDrivers.KNOWN) {
      assertEquals(64, driver.sha256.length, "${driver.title}: sha256 должен быть 64 шестнадцатеричных знака")
      assertTrue(driver.sha256.all { it in "0123456789abcdef" }, driver.title)
      assertTrue(driver.sizeBytes > 100_000, "${driver.title}: подозрительно маленький размер")
      assertTrue(driver.licence.isNotBlank(), driver.title)
      assertTrue(driver.url.startsWith(JdbcDrivers.MAVEN_CENTRAL + "/"), "качаем только из Maven Central")
      assertTrue(driver.fileName.endsWith(".jar"), driver.fileName)
    }
  }

  @Test
  fun `драйвер выбирается по адресу подключения`() {
    assertEquals("PostgreSQL", JdbcDrivers.forUrl("jdbc:postgresql://db/app")?.title)
    assertEquals("SQLite", JdbcDrivers.forUrl("jdbc:sqlite:./dev.db")?.title)
    assertEquals("MySQL", JdbcDrivers.forUrl("JDBC:MYSQL://db/app")?.title, "регистр адреса не важен")
    // Неизвестная база — не повод предлагать чужой драйвер.
    assertNull(JdbcDrivers.forUrl("jdbc:oracle:thin:@db:1521:orcl"))
  }

  @Test
  fun `подменённый файл не проходит проверку`() {
    val driver = JdbcDrivers.KNOWN.first()
    assertFalse(JdbcDrivers.matches("подделка".toByteArray(), driver))
    // И положительный случай: функция считает то, что мы думаем.
    assertEquals("2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
                 JdbcDrivers.sha256("foo".toByteArray()))
  }

  @Test
  fun `драйверы лежат в домашней папке, а не в проекте`() {
    // Драйвер один на машину: десять проектов не должны носить десять копий одного jar.
    val driver = JdbcDrivers.KNOWN.first()
    val path = JdbcDrivers.target("/home/пользователь", driver)
    assertTrue(path.toString().endsWith("/.vibe/drivers/" + driver.fileName), path.toString())
  }

  @Test
  fun `лицензия MySQL названа прямо`() {
    // Человек, нажимающий «скачать», обязан знать, что принимает: у этого драйвера GPL.
    val mysql = JdbcDrivers.KNOWN.first { it.kind == DataSources.Kind.MYSQL }
    assertTrue(mysql.licence.contains("GPL"), mysql.licence)
  }
}
