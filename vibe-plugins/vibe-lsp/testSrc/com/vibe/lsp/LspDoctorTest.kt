// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LspDoctorTest {
  @Test
  fun `a missing binary is reported as missing, not as a bare name`() {
    val checks = LspDoctor.check(resolve = { null })
    assertTrue(checks.all { !it.installed }, "ни один сервер не должен считаться установленным")
    assertTrue(checks.all { it.path == null }, "путь отсутствующего сервера — null, а не имя бинаря")
  }

  @Test
  fun `an installed binary carries the path it was found at`() {
    val checks = LspDoctor.check(specs = listOf(LspDoctor.VTSLS), resolve = { "/opt/homebrew/bin/$it" })
    assertEquals("/opt/homebrew/bin/vtsls", checks.single().path)
    assertTrue(checks.single().installed)
  }

  @Test
  fun `each server is checked independently`() {
    val checks = LspDoctor.check(resolve = { if (it == "vtsls") "/usr/local/bin/vtsls" else null })
    assertTrue(checks.first { it.spec == LspDoctor.VTSLS }.installed)
    assertFalse(checks.first { it.spec == LspDoctor.PHPACTOR }.installed)
  }

  @Test
  fun `the file extension picks the server responsible for it`() {
    assertEquals(LspDoctor.VTSLS, LspDoctor.serverFor("AgentPanel.tsx"))
    assertEquals(LspDoctor.VTSLS, LspDoctor.serverFor("index.MJS"))
    assertEquals(LspDoctor.PHPACTOR, LspDoctor.serverFor("Kernel.php"))
  }

  @Test
  fun `a file we do not serve asks about nothing`() {
    // Silence here matters: a notification about PHP while opening a README would train the
    // user to dismiss the notification that actually names a missing server.
    assertNull(LspDoctor.serverFor("README.md"))
    assertNull(LspDoctor.serverFor("Makefile"))
    assertNull(LspDoctor.serverFor(".gitignore"))
  }

  @Test
  fun `every server offers a one-line install command`() {
    assertTrue(LspDoctor.ALL.all { it.installCommand.isNotBlank() && '\n' !in it.installCommand })
    assertTrue(LspDoctor.ALL.none { it.installCommand.startsWith("sudo ") }, "команда установки не требует sudo")
  }

  @Test
  fun `server ids match the ids declared to LSP4IJ`() {
    // The report names the server the way the LSP4IJ console does; a drift here sends the
    // user looking for a server that is not called that anywhere in the UI.
    assertEquals(setOf("vibeVtsls", "vibePhpactor", "vibeCss", "vibeEslint"), LspDoctor.ALL.map { it.id }.toSet())
  }

  @Test
  fun `HTML и JSON отданы платформе, а не серверу`() {
    // Тот же npm-пакет несёт серверы html и json, и подключать их нельзя: в Community они уже есть,
    // а два движка на одном файле дают два набора подсказок, половина которых спорит с другой.
    val served = LspDoctor.ALL.flatMap { it.extensions }.toSet()
    assertFalse("html" in served)
    assertFalse("json" in served)
    assertTrue("css" in served, "CSS в Community нет вовсе — вот его и закрываем")
  }

  @Test
  fun `свой сервер сильнее встроенного`() {
    // Проект может быть прибит к другой версии, и наша копия стареет вместе с релизом IDE.
    val own = LspDoctor.check(listOf(LspDoctor.PHPACTOR), resolve = { "/usr/local/bin/phpactor" }, bundled = { "/app/phpactor.phar" }).single()
    assertEquals(LspDoctor.Source.OWN, own.source)
    assertEquals("/usr/local/bin/phpactor", own.path)
  }

  @Test
  fun `встроенный сервер считается установленным и называется встроенным`() {
    val bundled = LspDoctor.check(listOf(LspDoctor.PHPACTOR), resolve = { null }, bundled = { "/app/phpactor.phar" }).single()
    assertEquals(LspDoctor.Source.BUNDLED, bundled.source)
    assertTrue(bundled.installed)
  }

  @Test
  fun `без своего и без встроенного — честное отсутствие`() {
    val absent = LspDoctor.check(listOf(LspDoctor.VTSLS), resolve = { null }, bundled = { null }).single()
    assertEquals(LspDoctor.Source.ABSENT, absent.source)
    assertFalse(absent.installed)
  }

  @Test
  fun `у каждого поставляемого сервера назван нужный ему рантайм`() {
    // Иначе «встроен» может означать «есть файл, который нечем запустить».
    assertEquals("php", LspDoctor.runtimeFor(LspDoctor.PHPACTOR))
    assertEquals("node", LspDoctor.runtimeFor(LspDoctor.VTSLS))
    assertEquals("node", LspDoctor.runtimeFor(LspDoctor.CSS))
    assertEquals("node", LspDoctor.runtimeFor(LspDoctor.ESLINT))
  }
}
