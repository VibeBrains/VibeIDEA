// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор Ext JS по тексту.
 *
 * Образец взят с экрана владельца (12.09.2026): именно на нём переход не работал, потому что его в
 * продукте не было вовсе.
 */
class ExtJsTest {
  private val sample = """
    /** Выполнение лучевой терапии */
    Ext.define('common.MorbusOnko.swEvnUslugaOnkoBeam.Modals.ExecModal', {
      extend: 'common.MorbusOnko.swEvnUslugaOnkoBeam.Modals.BaseModal',
      alias: 'widget.swEvnUslugaOnkoBeamExecModal',
      title: 'Выполнение лучевой терапии',
      saveUrl: '/?d=onko&c=EvnUslugaOnkoBeamExec&m=save',
    });
  """.trimIndent()

  @Test
  fun `class and alias are both declarations`() {
    val names = scanSymbols(sample).map { it.name }
    assertEquals(
      listOf(
        "common.MorbusOnko.swEvnUslugaOnkoBeam.Modals.ExecModal",
        "widget.swEvnUslugaOnkoBeamExecModal",
      ),
      names,
    )
  }

  @Test
  fun `the parent class is not a declaration`() {
    assertFalse(scanSymbols(sample).any { it.name.endsWith("BaseModal") })
  }

  @Test
  fun `a declaration points at its own name, not at the file start`() {
    val symbol = scanSymbols(sample).first()
    assertEquals(symbol.name, sample.substring(symbol.offset, symbol.offset + symbol.name.length))
  }

  @Test
  fun `double quotes and spaces around the call are the same declaration`() {
    val names = scanSymbols("""Ext . define ( "app.Grid" , {} )""").map { it.name }
    assertEquals(listOf("app.Grid"), names)
  }

  @Test
  fun `the literal under the caret is found from any position inside it`() {
    val offset = sample.indexOf("BaseModal")
    assertEquals("common.MorbusOnko.swEvnUslugaOnkoBeam.Modals.BaseModal", literalAt(sample, offset))
  }

  @Test
  fun `the caret outside a literal finds nothing`() {
    assertNull(literalAt(sample, sample.indexOf("extend:")))
  }

  @Test
  fun `a literal is not searched across lines`() {
    val text = "var a = 'left';\nvar b = 'right';"
    assertEquals("right", literalAt(text, text.indexOf("right")))
  }

  @Test
  fun `only a dotted name is a class name`() {
    assertTrue(isExtClassName("common.MorbusOnko.Modals.BaseModal"))
    assertTrue(isExtClassName("widget.execModal"))
    assertFalse(isExtClassName("BaseModal"))
    assertFalse(isExtClassName("Выполнение лучевой терапии"))
    assertFalse(isExtClassName("/?d=onko&c=EvnUslugaOnkoBeamExec&m=save"))
  }
}
