// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Check" must tell four answers apart, not two.
 *
 * "Does not start" and "starts but does not report a version" mean "fix it" and "all is fine". Some language servers do
 * not know `--version` at all: they speak over stdio and silently wait for the protocol, which from outside looks like
 * a hang. Calling that broken would send a person to fix something that works.
 */
class ServerCheckTest {
  private fun answer(exit: Int, output: String, timedOut: Boolean = false) =
    { _: String -> ServerCheck.ProcessResult(exit, output, timedOut) }

  @Test
  fun `сервер назвал версию`() {
    val outcome = ServerCheck.of("/bin/vtsls", answer(0, "0.2.9\n"))
    assertTrue(outcome is ServerCheck.Outcome.Works, "ответ 0.2.9 не признан рабочим: $outcome")
    assertEquals("0.2.9", (outcome as ServerCheck.Outcome.Works).version)
  }

  @Test
  fun `версия вынимается из строки с именем`() {
    assertEquals("2026.06.23.0", ServerCheck.versionFrom("Phpactor 2026.06.23.0"))
  }

  @Test
  fun `строка без номера показывается целиком`() {
    assertEquals("language server", ServerCheck.versionFrom("language server"))
  }

  @Test
  fun `молчание — это не поломка, а сервер без ключа версии`() {
    val outcome = ServerCheck.of("/bin/svelte", answer(-1, "", timedOut = true))
    assertTrue(outcome is ServerCheck.Outcome.NoVersion,
               "ожидание протокола принято за поломку: $outcome")
  }

  @Test
  fun `пустой путь — это отсутствие, а не отказ`() {
    assertEquals(ServerCheck.Outcome.Missing, ServerCheck.of(null))
    assertEquals(ServerCheck.Outcome.Missing, ServerCheck.of("   "))
  }

  @Test
  fun `отказ с внятной причиной так и называется`() {
    val outcome = ServerCheck.of("/bin/ngserver", answer(1, "bad CPU type in executable"))
    assertTrue(outcome is ServerCheck.Outcome.Failed, "отказ не распознан: $outcome")
    assertTrue((outcome as ServerCheck.Outcome.Failed).reason.isNotEmpty(), "причина отказа потеряна")
  }

  @Test
  fun `ненулевой код без вывода — тоже отсутствие версии, а не поломка`() {
    val outcome = ServerCheck.of("/bin/some", answer(9, "   "))
    assertTrue(outcome is ServerCheck.Outcome.NoVersion,
               "код без объяснения принят за поломку — так серверы отвечают на незнакомый ключ: $outcome")
  }

  @Test
  fun `упавший запуск не роняет страницу настроек`() {
    val outcome = ServerCheck.of("/bin/nope") { throw java.io.IOException("Cannot run program") }
    assertTrue(outcome is ServerCheck.Outcome.Failed, "исключение обязано стать отказом: $outcome")
  }
}
