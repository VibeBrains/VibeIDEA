// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.i18n

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VibeI18nTest {
  // --- fallback chain ---

  @Test
  fun `a saved language whose file exists wins`() {
    assertEquals("de", VibeI18n.resolveActive(saved = "de", systemLanguage = "en", present = setOf("de", "en")))
  }

  @Test
  fun `a saved language whose file vanished does not stick`() {
    // Offering a language that no longer exists is a way to show an empty interface.
    assertEquals("en", VibeI18n.resolveActive(saved = "de", systemLanguage = "en", present = setOf("en")))
    assertEquals(VibeI18n.BASE_LANGUAGE, VibeI18n.resolveActive(saved = "de", systemLanguage = "fr", present = emptySet()))
  }

  @Test
  fun `the base language never needs a file`() {
    assertEquals(VibeI18n.BASE_LANGUAGE, VibeI18n.resolveActive(saved = VibeI18n.BASE_LANGUAGE, systemLanguage = "en", present = emptySet()))
  }

  @Test
  fun `without a choice the system language is used when it is present`() {
    assertEquals("en", VibeI18n.resolveActive(saved = null, systemLanguage = "en", present = setOf("en")))
    assertEquals(VibeI18n.BASE_LANGUAGE, VibeI18n.resolveActive(saved = null, systemLanguage = "ja", present = setOf("en")))
  }

  // --- substitutions ---

  @Test
  fun `named placeholders survive being moved by a translator`() {
    val ru = "Прогонов: {total}, работают {running}"
    val de = "{running} von {total} laufen"
    val args = mapOf("total" to 5, "running" to 2)
    assertEquals("Прогонов: 5, работают 2", VibeI18n.substitute(ru, args))
    assertEquals("2 von 5 laufen", VibeI18n.substitute(de, args))
  }

  @Test
  fun `a placeholder with no value stays VISIBLE`() {
    // A visible {count} names the bug; silently deleting it hides one.
    assertEquals("Осталось {count}", VibeI18n.substitute("Осталось {count}", emptyMap()))
    assertEquals("Осталось {count}", VibeI18n.substitute("Осталось {count}", mapOf("count" to null)))
  }

  // --- catalogue files ---

  @Test
  fun `only flat string values are read`() {
    val parsed = VibeI18n.parse("""{"a.b":"строка","n":42,"nested":{"x":"y"}}""")
    assertEquals(mapOf("a.b" to "строка"), parsed)
  }

  @Test
  fun `a broken file is not a catalogue`() {
    assertTrue(VibeI18n.parse("не json").isEmpty())
    assertTrue(VibeI18n.parse("[1,2]").isEmpty())
  }

  @Test
  fun `the file name is the language code`() {
    assertEquals("en", VibeI18n.codeOf("en.json"))
    assertEquals("pt-br", VibeI18n.codeOf("pt-BR.json"))
    assertEquals("qya", VibeI18n.codeOf("qya.json"))
    assertNull(VibeI18n.codeOf("README.md"))
    assertNull(VibeI18n.codeOf("english.txt"))
    assertNull(VibeI18n.codeOf("2.json"))
  }
}
