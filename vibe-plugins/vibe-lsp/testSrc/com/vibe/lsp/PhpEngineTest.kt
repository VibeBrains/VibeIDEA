// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The OS is a parameter, so the Windows command line is checked here, on macOS. */
class PhpEngineTest {
  @Test
  fun `автоматический выбор — встроенный Phpactor на любой системе, Windows включительно`() {
    // Требование ext-posix у phar оказалось ложным: все вызовы внутри защищены, а проверку Box
    // IDE отключает сама. Второй сервер ради Windows больше не нужен.
    assertEquals(PhpEngine.PHPACTOR, PhpServerChoice.resolve(PhpEngine.AUTO, windows = true))
    assertEquals(PhpEngine.PHPACTOR, PhpServerChoice.resolve(PhpEngine.AUTO, windows = false))
  }

  @Test
  fun `явный выбор человека уважается`() {
    assertEquals(PhpEngine.INTELEPHENSE, PhpServerChoice.resolve(PhpEngine.INTELEPHENSE, windows = false))
    assertEquals(PhpEngine.PHPACTOR, PhpServerChoice.resolve(PhpEngine.PHPACTOR, windows = true))
  }

  @Test
  fun `на Windows phar запускается через лаунчер, вне Windows — сам собой`() {
    val launcher = "/app/servers/phpactorLaunch.php"
    val phar = "/app/servers/phpactor.phar"
    // Собственная проверка phar (extension_loaded('posix') и exit 255) на Windows не проходит
    // никогда: расширения там нет ни в одной сборке PHP. Лаунчер поднимает автозагрузчик phar мимо
    // этой строки — больше он не делает ничего.
    assertEquals(launcher, ServerBinaries.phpactorScript(windows = true, launcher = launcher, phar = phar))
    assertEquals(phar, ServerBinaries.phpactorScript(windows = false, launcher = launcher, phar = phar))
    // Лаунчера рядом нет (запуск из исходников без скачанного набора) — зовём сам phar: он назовёт
    // настоящую причину вместо падения на несуществующем пути.
    assertEquals(phar, ServerBinaries.phpactorScript(windows = true, launcher = null, phar = phar))
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
    assertEquals(null, LspDoctor.bundledPath(LspDoctor.INTELEPHENSE))
    assertEquals("node", LspDoctor.runtimeFor(LspDoctor.INTELEPHENSE))
    assertTrue(LspDoctor.INTELEPHENSE.installCommand.startsWith("npm install -g"))
  }

  @Test
  fun `свой путь можно задать каждому движку отдельно`() {
    assertTrue(LspDoctor.PHPACTOR.id in ServerPaths.OVERRIDABLE)
    assertTrue(LspDoctor.INTELEPHENSE.id in ServerPaths.OVERRIDABLE)
  }
}
