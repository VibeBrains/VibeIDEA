// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Путь к интерпретатору, положенный в поле сервера.
 *
 * Поля ищут глазами: человеку нужно указать ноду, он находит первое поле с путём и пишет её туда.
 * Дальше этот путь становится ВСЕЙ командой, и сервер запускается как `node --stdio` — без скрипта.
 * Ровно это владелец прислал 18.09.2026 двумя сообщениями: снимок настроек и
 * «Unable to start language server: commands=[…/node, --stdio]».
 */
class ServerPathsInterpreterTest {
  @Test
  fun `an interpreter in a server field is recognised on both platforms`() {
    assertTrue(ServerPaths.isInterpreter("/Users/konstantin/.nvm/versions/node/v22.23.2/bin/node"))
    assertTrue(ServerPaths.isInterpreter("C:\\Program Files\\nodejs\\node.exe"))
    assertTrue(ServerPaths.isInterpreter("/usr/bin/php"))
    assertFalse(ServerPaths.isInterpreter("/work/app/node_modules/.bin/vtsls"))
    assertFalse(ServerPaths.isInterpreter("/opt/vendor/bin/phpactor"))
  }

  @Test
  fun `such a path is not used as a command and is named separately`() {
    val node = "/Users/konstantin/.nvm/versions/node/v22.23.2/bin/node"
    // Исполняемый — и всё же не сервер: «сломанным путём» его звать нельзя, он рабочий.
    assertNull(ServerPaths.overrideFor("vtsls", stored = node, usable = { true }))
    assertNull(ServerPaths.broken("vtsls", stored = node, usable = { true }))
    assertEquals(node, ServerPaths.interpreterInstead("vtsls", stored = node))
  }

  @Test
  fun `a real server path still works, and a missing one is still called broken`() {
    val server = "/work/app/node_modules/.bin/vtsls"
    assertEquals(server, ServerPaths.overrideFor("vtsls", stored = server, usable = { true }))
    assertEquals(server, ServerPaths.broken("vtsls", stored = server, usable = { false }))
    assertNull(ServerPaths.interpreterInstead("vtsls", stored = server))
  }
}
