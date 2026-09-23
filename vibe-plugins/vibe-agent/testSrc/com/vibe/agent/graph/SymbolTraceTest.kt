// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Откуда пришло значение» на реальных формах кода.
 *
 * Разбор текстовый, и тест держит его честным: он обязан находить то, что встречается каждый день
 * (импорт, поле класса, внедрение в конструктор), и не обязан притворяться разбором языка.
 */
class SymbolTraceTest {
  @Test
  fun `an import says where the name comes from`() {
    val steps = SymbolTrace.stepsIn("import { Mailer } from './services/mailer';", "Mailer")
    assertEquals(SymbolTrace.Kind.IMPORT, steps.single().kind)
    assertEquals("./services/mailer", steps.single().from)
  }

  @Test
  fun `a default import counts too`() {
    val steps = SymbolTrace.stepsIn("import Mailer from './mailer'", "Mailer")
    assertEquals(SymbolTrace.Kind.IMPORT, steps.single().kind)
  }

  @Test
  fun `a php use statement gives its namespace`() {
    val steps = SymbolTrace.stepsIn("use App\\Service\\Mailer;", "Mailer")
    assertEquals(SymbolTrace.Kind.IMPORT, steps.single().kind)
    assertEquals("App\\Service", steps.single().from)
  }

  @Test
  fun `constructor injection is the answer Angular and PHP give most often`() {
    val steps = SymbolTrace.stepsIn("  constructor(private readonly mailer: Mailer) {}", "mailer")
    assertEquals(SymbolTrace.Kind.INJECTED, steps.single().kind)
  }

  @Test
  fun `a declaration in the same file is found with its line`() {
    val text = """
      const other = 1;
      const answer = 42;
    """.trimIndent()
    val steps = SymbolTrace.stepsIn(text, "answer")
    assertEquals(2, steps.single().line)
    assertEquals(SymbolTrace.Kind.DECLARATION, steps.single().kind)
  }

  @Test
  fun `several places are all reported, because choosing between them is not a regex decision`() {
    val text = """
      import { config } from './config';
      const config = override();
    """.trimIndent()
    assertEquals(listOf(SymbolTrace.Kind.IMPORT, SymbolTrace.Kind.DECLARATION), SymbolTrace.stepsIn(text, "config").map { it.kind })
  }

  @Test
  fun `a name that is only used, never declared, yields nothing`() {
    assertTrue(SymbolTrace.stepsIn("console.log(mailer.send());", "mailer").isEmpty())
  }

  @Test
  fun `a line without a module says so instead of inventing one`() {
    assertNull(SymbolTrace.moduleOf("const x = 1;"))
  }

  @Test
  fun `occurrences are whole identifiers only`() {
    val text = "import { Mailer } from './mailer'; const m = new Mailer(); MailerFactory; \$Mailer; mailer"
    val found = SymbolTrace.occurrences(text, "Mailer")
    // The import and the `new`, not the longer name, not the PHP-style variable, not the lower-case module.
    assertEquals(listOf(text.indexOf("Mailer"), text.indexOf("new Mailer") + 4), found)
  }

  @Test
  fun `occurrences at the edges of the text count`() {
    assertEquals(listOf(0, 7), SymbolTrace.occurrences("Mailer Mailer", "Mailer"))
  }

  @Test
  fun `an empty name occurs nowhere`() {
    assertTrue(SymbolTrace.occurrences("anything", "").isEmpty())
  }
}
