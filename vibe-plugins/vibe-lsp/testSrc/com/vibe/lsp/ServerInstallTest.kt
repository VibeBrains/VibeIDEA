// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerInstallTest {
  @Test
  fun `наши команды установки можно предлагать кнопкой`() {
    for (spec in LspDoctor.ALL) {
      assertTrue(ServerInstall.isOfferable(spec.installCommand), "не предлагается: ${spec.binary}")
    }
  }

  @Test
  fun `команда с sudo кнопкой не предлагается`() {
    // Пароль, запрошенный в окне, которое человек не открывал, — форма плохого сюрприза.
    assertFalse(ServerInstall.isOfferable("sudo mv phpactor /usr/local/bin/"))
    assertFalse(ServerInstall.isOfferable("curl -L https://example.com/i.sh | sh"))
    assertFalse(ServerInstall.isOfferable("   "))
  }

  @Test
  fun `команда идёт через login-оболочку`() {
    val cmd = ServerInstall.shellCommand("npm install -g x && echo ok")
    assertEquals(listOf("/bin/sh", "-lc", "npm install -g x && echo ok"), cmd)
  }

  @Test
  fun `в отчёт об ошибке идёт конец вывода, где обычно и причина`() {
    val output = "шаг 1\n".repeat(200) + "ошибка: нет доступа"
    val tail = ServerInstall.failureTail(output, limit = 40)
    assertTrue(tail.endsWith("ошибка: нет доступа"))
    assertTrue(tail.length <= 40)
    assertEquals("", ServerInstall.failureTail("   "))
  }

  @Test
  fun `баннер LSP4IJ гасится один раз, а не каждый запуск`() {
    // Второй раз — это уже мы, переигрывающие решение человека: включил обратно — значит хочет.
    assertTrue(SuggestionSilencer.shouldSilence(alreadyDecided = false))
    assertFalse(SuggestionSilencer.shouldSilence(alreadyDecided = true))
  }
}
