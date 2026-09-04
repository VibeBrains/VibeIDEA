// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Куки: показываем сокращённо, сохраняем только то, что и так пережило бы перезапуск. */
class CookiesTest {
  private val now = 1_700_000_000_000L

  private fun cookie(name: String, expires: Long? = null) =
    Cookies.Cookie("example.com", "/", name, "abcdef0123456789", secure = true, expiresAtEpochMs = expires)

  @Test
  fun `длинное значение сокращается, короткое остаётся целым`() {
    assertEquals("abcdef…456789", Cookies.shorten("abcdef0123456789"))
    assertEquals("short", Cookies.shorten("short"))
  }

  @Test
  fun `истёкшая кука не живая`() {
    assertTrue(Cookies.isAlive(cookie("a", now + 1000), now))
    assertFalse(Cookies.isAlive(cookie("a", now - 1000), now))
    assertTrue(Cookies.isAlive(cookie("a", null), now), "кука сессии жива, пока жив процесс")
  }

  @Test
  fun `сессионные куки не сохраняются`() {
    val saved = Cookies.persistable(listOf(cookie("session"), cookie("token", now + 5000)), now)
    assertEquals(listOf("token"), saved.map { it.name })
  }

  @Test
  fun `запись и чтение возвращают ту же куку`() {
    val original = cookie("token", now + 5000)
    assertEquals(original, Cookies.decode(Cookies.encode(original)))
  }

  @Test
  fun `битая строка стоит одной куки, а не всего файла`() {
    assertNull(Cookies.decode("мусор"))
    val text = Cookies.encode(cookie("a", now + 1)) + "\nмусор\n" + Cookies.encode(cookie("b", now + 2))
    assertEquals(listOf("a", "b"), Cookies.render(text).map { it.name })
  }

  @Test
  fun `табуляция в значении не разъезжает по столбцам`() {
    val weird = Cookies.Cookie("example.com", "/", "t", "a\tb", secure = false, expiresAtEpochMs = now)
    assertEquals("a b", Cookies.decode(Cookies.encode(weird))?.value)
  }
}
