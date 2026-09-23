// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An npm install runs with the npm of the IDE's own Node — never with whatever npm a login shell finds first.
 *
 * With a version manager, the shell's npm often belongs to another Node: the package then lands in that Node's global
 * folder, the IDE does not see it and keeps running its bundled copy.
 */
class NpmInstallTest {
  @Test
  fun `npm берётся рядом с нодой IDE, а не из оболочки`() {
    val plan = NpmInstall.plan("npm install -g @vtsls/language-server", "/home/u/.fnm/v24/bin/node", windows = false)
    assertEquals(NpmInstall.Plan.Run(listOf("/home/u/.fnm/v24/bin/npm", "install", "-g", "@vtsls/language-server")), plan)
  }

  @Test
  fun `на Windows это npm_cmd рядом с node_exe`() {
    val plan = NpmInstall.plan("npm install -g intelephense", "C:\\Program Files\\nodejs\\node.exe", windows = true)
    val argv = (plan as NpmInstall.Plan.Run).argv
    assertEquals(listOf("install", "-g", "intelephense"), argv.drop(1))
    assertEquals(true, argv.first().endsWith("npm.cmd"), "на Windows npm — это npm.cmd: ${argv.first()}")
  }

  @Test
  fun `без ноды установка не запускается, а называет причину`() {
    assertEquals(NpmInstall.Plan.NoNode, NpmInstall.plan("npm install -g svelte-language-server", null, windows = false))
  }

  @Test
  fun `не npm-команда идёт как написана`() {
    assertEquals(NpmInstall.Plan.NotNpm,
                 NpmInstall.plan("mkdir -p ~/.local/bin && curl -Lo ~/.local/bin/phpactor x", "/usr/bin/node", windows = false))
  }

  @Test
  fun `лишние пробелы не ломают разбор`() {
    val plan = NpmInstall.plan("  npm   install  -g   stylus-lsp ", "/usr/local/bin/node", windows = false)
    assertEquals(NpmInstall.Plan.Run(listOf("/usr/local/bin/npm", "install", "-g", "stylus-lsp")), plan)
  }
}
