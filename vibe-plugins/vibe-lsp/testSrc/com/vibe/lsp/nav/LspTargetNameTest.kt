// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Names and positions around a go-to target: what the list of targets shows, and where the hint asks the server.
 */
class LspTargetNameTest {
  private val code = "export function fetchUser(id: string) { return \$api.get(id) }"

  @Test
  fun `диапазон имени — это имя`() {
    val start = code.indexOf("fetchUser")
    assertEquals("fetchUser", LspTarget.declaredName(code, start, start + "fetchUser".length))
  }

  @Test
  fun `пустой диапазон указывает на начало имени`() {
    assertEquals("fetchUser", LspTarget.declaredName(code, code.indexOf("fetchUser"), code.indexOf("fetchUser")))
  }

  @Test
  fun `диапазон всего объявления имени не называет`() {
    // A server that answers with the whole declaration would otherwise name every target «export».
    assertNull(LspTarget.declaredName(code, 0, code.indexOf("{")))
  }

  @Test
  fun `слово под мышью находится с любой его буквы`() {
    assertEquals("fetchUser", LspTarget.wordAround(code, code.indexOf("User")))
    assertEquals("\$api", LspTarget.wordAround(code, code.indexOf("api")))
  }

  @Test
  fun `за пределами текста имени нет`() {
    assertNull(LspTarget.declaredName(code, code.length + 5, code.length + 6))
    assertEquals("", LspTarget.wordAround(code, -1))
  }

  @Test
  fun `подсказку спрашивают на имени внутри токена, а не на его начале`() {
    // A TextMate token may be wider than the name; its start would ask the server about `this`.
    assertEquals(105, LspSignatureDocumentation.sourceOffset(100, "this.sendMail", "sendMail"))
    assertEquals(100, LspSignatureDocumentation.sourceOffset(100, "sendMail", "sendMail"))
    assertEquals(100, LspSignatureDocumentation.sourceOffset(100, "other", "sendMail"))
    assertEquals(100, LspSignatureDocumentation.sourceOffset(100, "this.sendMail", null))
  }
}
