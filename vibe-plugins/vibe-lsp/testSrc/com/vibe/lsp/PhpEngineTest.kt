// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Windows behaviour is checked here, on macOS: the OS is a parameter, not an environment.
 * Otherwise the only way to learn that Windows picks the wrong PHP server is to go and find a
 * Windows machine — which is exactly how this defect reached a release.
 */
class PhpEngineTest {
  @Test
  fun `на Windows автоматический выбор не может быть Phpactor`() {
    // Его phar требует ext-posix, которого в Windows-сборках PHP нет ни в одной: это обёртка над
    // системными вызовами POSIX, а не пакет, который кто-то забыл собрать.
    assertEquals(PhpEngine.INTELEPHENSE, PhpServerChoice.resolve(PhpEngine.AUTO, windows = true))
  }

  @Test
  fun `вне Windows автоматический выбор — встроенный Phpactor`() {
    // Встроенный, значит работающий сразу после установки и без чужой учётной записи в npm.
    assertEquals(PhpEngine.PHPACTOR, PhpServerChoice.resolve(PhpEngine.AUTO, windows = false))
  }

  @Test
  fun `явный выбор человека уважается, даже когда он невозможен`() {
    // Молча подменить выбор — научить человека, что настройка ничего не делает. Настройка
    // работает; невозможен выбор, и доктор говорит об этом отдельной строкой.
    assertEquals(PhpEngine.PHPACTOR, PhpServerChoice.resolve(PhpEngine.PHPACTOR, windows = true))
    assertTrue(PhpServerChoice.impossibleHere(PhpEngine.PHPACTOR, windows = true))
    assertFalse(PhpServerChoice.impossibleHere(PhpEngine.INTELEPHENSE, windows = true))
    assertFalse(PhpServerChoice.impossibleHere(PhpEngine.PHPACTOR, windows = false))
  }

  @Test
  fun `автоматический выбор на Windows не считается невозможным`() {
    // Иначе доктор ругался бы на настройку, которую человек не трогал и которая уже права.
    assertFalse(PhpServerChoice.impossibleHere(PhpEngine.AUTO, windows = true))
  }

  @Test
  fun `на php отвечает ровно один сервер`() {
    for (engine in listOf(PhpEngine.PHPACTOR, PhpEngine.INTELEPHENSE)) {
      val php = LspDoctor.active(engine).filter { "php" in it.extensions }
      assertEquals(1, php.size, "два сервера на *.php запустились бы оба и удвоили подсказки")
    }
    assertEquals(LspDoctor.INTELEPHENSE, LspDoctor.active(PhpEngine.INTELEPHENSE).single { "php" in it.extensions })
  }

  @Test
  fun `выбор движка не выбрасывает остальные языки`() {
    val ids = LspDoctor.active(PhpEngine.INTELEPHENSE).map { it.id }
    assertTrue(ids.containsAll(listOf(LspDoctor.VTSLS.id, LspDoctor.CSS.id, LspDoctor.ESLINT.id)))
    assertEquals(LspDoctor.ALL.size - 1, ids.size, "лишним оказывается ровно один движок PHP")
  }

  @Test
  fun `Intelephense мы не поставляем и он не требует php`() {
    // Проприетарный: ставится на машину, как vtsls и ESLint. Заявить его встроенным значило бы
    // обещать работу там, где ничего не установлено.
    assertEquals(null, LspDoctor.bundledPath(LspDoctor.INTELEPHENSE))
    assertEquals("node", LspDoctor.runtimeFor(LspDoctor.INTELEPHENSE))
    assertTrue(LspDoctor.INTELEPHENSE.installCommand.startsWith("npm install -g"))
  }

  @Test
  fun `свой путь можно задать каждому движку отдельно`() {
    // Один путь на «PHP» не выразил бы обычный случай: Phpactor из vendor/bin в проекте И
    // Intelephense из npm на той же машине.
    assertTrue(LspDoctor.PHPACTOR.id in ServerPaths.OVERRIDABLE)
    assertTrue(LspDoctor.INTELEPHENSE.id in ServerPaths.OVERRIDABLE)
  }
}
