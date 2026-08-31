// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRefsTest {
  private val vault = mapOf("GH_TOKEN" to "ghp_xxx", "EMPTY" to "")

  @Test
  fun `ссылки находятся по имени и без повторов`() {
    assertEquals(listOf("GH_TOKEN"), SecretRefs.names("gh auth login --with-token \${secret:GH_TOKEN} \${secret:GH_TOKEN}"))
    assertTrue(SecretRefs.names("обычная строка").isEmpty())
    assertFalse(SecretRefs.has("\${secret:}"), "имя обязательно — пустая ссылка ссылкой не считается")
  }

  @Test
  fun `подстановка кладёт значение, а не имя`() {
    assertEquals("token=ghp_xxx", SecretRefs.substitute("token=\${secret:GH_TOKEN}") { vault[it] })
  }

  @Test
  fun `неизвестное имя остаётся ссылкой, а не пустотой`() {
    // Пустая подстановка превратила бы «нет секрета» в запрос, падающий далеко и непонятно почему.
    val text = "token=\${secret:NOPE}"
    assertEquals(text, SecretRefs.substitute(text) { vault[it] })
    assertEquals(listOf("NOPE"), SecretRefs.missing(text) { vault[it] })
  }

  @Test
  fun `пустое значение считается отсутствующим`() {
    assertEquals(listOf("EMPTY"), SecretRefs.missing("x=\${secret:EMPTY}") { vault[it] })
  }
}
