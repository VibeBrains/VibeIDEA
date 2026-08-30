// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.i18n

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled catalogues themselves — the gate checks them in CI, these tests check them from
 * inside the plugin, so a broken resource fails the build rather than the first screenshot.
 */
class LangCatalogTest {
  private fun resource(path: String): String =
    LangCatalogTest::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
      ?: error("нет ресурса $path")

  private val base = VibeI18n.parse(resource("/lang/base.json"))
  private val english = VibeI18n.parse(resource("/lang/en.json"))

  @Test
  fun `the base catalogue is bundled and non-empty`() {
    assertTrue(base.size > 30, "в базе ${base.size} ключей")
  }

  @Test
  fun `our own second language ships as a FILE with the same keys`() {
    // English lives by the same rules as anyone's Elvish — that is how the rules get tested on us.
    assertEquals(base.keys, english.keys)
  }

  @Test
  fun `placeholders match between the base and the translation`() {
    val placeholder = Regex("\\{([a-zA-Z]+)}")
    for ((key, ru) in base) {
      val en = english[key] ?: continue
      assertEquals(
        placeholder.findAll(ru).map { it.groupValues[1] }.toSet(),
        placeholder.findAll(en).map { it.groupValues[1] }.toSet(),
        "подстановки разошлись в ключе $key",
      )
    }
  }

  @Test
  fun `keys are flat and dotted`() {
    for (key in base.keys) {
      assertTrue(key.matches(Regex("[a-z][a-zA-Z0-9]*(\\.[a-zA-Z0-9]+)+")), "ключ не по форме: $key")
    }
  }

  @Test
  fun `no value is empty — an empty string renders as a missing label`() {
    for ((key, value) in base + english) assertTrue(value.isNotBlank(), "пустое значение у $key")
  }

  @Test
  fun `every design rule has both a message and an explanation`() {
    // A finding without a «why» is useless to whoever meets it for the first time: they see a
    // verdict and no reason to believe it.
    val base = VibeI18n.parse(
      LangCatalogTest::class.java.getResourceAsStream("/lang/base.json")!!.bufferedReader().readText())
    val messages = base.keys.filter { it.startsWith("design.rule.") && it.endsWith(".message") }
    assertTrue(messages.size >= 25, "правил в каталоге: ${messages.size}")
    for (key in messages) {
      val why = key.removeSuffix(".message") + ".why"
      assertTrue(base.containsKey(why), "у правила нет объяснения: $why")
      assertTrue(base.getValue(why).length > 30, "объяснение слишком короткое, чтобы объяснять: $why")
    }
  }
}
