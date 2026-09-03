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
  fun `на Windows встроенный phar получает выключатель проверки Box, вне Windows — ничего`() {
    // Именно -d, а не переменная окружения: запуск процесса делает клиент LSP, и его окружение
    // не наше, а интерпретаторные опции — наши.
    assertEquals(listOf("-d", "auto_prepend_file=/app/servers/phpactorNoPosixCheck.php"),
                 ServerBinaries.phpactorInterpreterArgs(windows = true, shim = "/app/servers/phpactorNoPosixCheck.php"))
    assertEquals(emptyList(), ServerBinaries.phpactorInterpreterArgs(windows = false, shim = "/app/servers/phpactorNoPosixCheck.php"))
    // Нет файла — нет опции: php с несуществующим auto_prepend_file падает, а без опции phar хотя бы
    // назовёт настоящую причину.
    assertEquals(emptyList(), ServerBinaries.phpactorInterpreterArgs(windows = true, shim = null))
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
