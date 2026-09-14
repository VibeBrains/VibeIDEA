// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The structure scan: class bodies, members, config, mixins, events — and what must not be mistaken for them. */
class ExtScanTest {
  private val grid = """
    /* Ext.define('commented.Out', {}) */
    Ext.define('app.view.OrderGrid', {
      extend: 'app.view.BaseGrid',
      alias: ['widget.orderGrid', 'widget.orders'],
      mixins: { filterable: 'app.mixin.Filterable' },
      config: { store: null, readOnly: false },
      title: 'Заказы { не скобка }',
      columns: [{ text: 'a}b', dataIndex: 'id', renderer: function (v) { return v.replace(/[}{]/g, ''); } }],
      initComponent: function () {
        var me = this;
        me.callParent(arguments);
        me.fireEvent('ordersloaded', me);
      },
      onRefresh() { this.getStore().load(); },
      listeners: { ordersloaded: 'onLoaded', scope: 'this' }
    });
  """.trimIndent()

  private val scan = scanFile(grid)
  private val cls = scan.classes.single()

  @Test
  fun `a commented-out define is not a class`() {
    assertEquals(listOf("app.view.OrderGrid"), scan.classes.map { it.name })
  }

  @Test
  fun `parent, aliases and mixins are read`() {
    assertEquals("app.view.BaseGrid", cls.extend)
    assertEquals(listOf("widget.orderGrid", "widget.orders"), cls.aliases.map { it.name })
    assertEquals(listOf("app.mixin.Filterable"), cls.mixins)
  }

  @Test
  fun `braces inside strings, regexes and nested objects do not end the body`() {
    val names = cls.members.filter { it.kind != ExtMember.Kind.CONFIG }.map { it.name }
    assertEquals(listOf("extend", "alias", "mixins", "config", "title", "columns", "initComponent", "onRefresh", "listeners"), names)
    assertEquals(grid.lastIndexOf('}'), cls.bodyEnd)
  }

  @Test
  fun `functions and shorthand methods are methods, the rest properties`() {
    val kinds = cls.members.associate { it.name to it.kind }
    assertEquals(ExtMember.Kind.METHOD, kinds["initComponent"])
    assertEquals(ExtMember.Kind.METHOD, kinds["onRefresh"])
    assertEquals(ExtMember.Kind.PROPERTY, kinds["title"])
  }

  @Test
  fun `config keys are config members`() {
    assertEquals(listOf("store", "readOnly"), cls.members.filter { it.kind == ExtMember.Kind.CONFIG }.map { it.name })
    assertEquals("store", configNameOf("getStore"))
    assertEquals("readOnly", configNameOf("updateReadOnly"))
    assertNull(configNameOf("getter"))
    assertNull(configNameOf("store"))
  }

  @Test
  fun `a member points at its own name`() {
    cls.members.forEach { assertEquals(it.name, grid.substring(it.offset, it.offset + it.name.length)) }
  }

  @Test
  fun `fired and listened events are found, listener options are not`() {
    assertEquals(listOf("ordersloaded", "ordersloaded"), scan.events.map { it.name })
    scan.events.forEach { assertEquals(it.name, grid.substring(it.offset, it.offset + it.name.length)) }
  }

  @Test
  fun `callParent knows the method it is called from`() {
    val offset = grid.indexOf("callParent")
    assertEquals(cls, classAt(scan.classes, offset))
    assertEquals("initComponent", methodAt(cls, offset)?.name)
  }

  @Test
  fun `the identifier and its receiver under the caret`() {
    val offset = grid.indexOf("getStore") + 3
    val word = identifierAt(grid, offset)!!
    assertEquals("getStore", word.name)
    assertEquals("this", receiverOf(grid, word.offset))
    assertTrue(isCalled(grid, word.offset + word.name.length))
    assertEquals("me", receiverOf(grid, grid.indexOf("callParent")))
    assertNull(receiverOf(grid, grid.indexOf("initComponent")))
  }

  @Test
  fun `a widget alias is also found by its short xtype`() {
    assertEquals(listOf("widget.orderGrid", "orderGrid"), lookupNames("widget.orderGrid"))
    assertEquals(listOf("app.view.OrderGrid"), lookupNames("app.view.OrderGrid"))
  }

  @Test
  fun `minified code is recognised by its line length`() {
    assertTrue(isMinified("Ext.define('a.B',{})" + ";x=1".repeat(MINIFIED_LINE_CHARS)))
    assertFalse(isMinified(grid))
  }

  @Test
  fun `strings that cannot be symbols are not looked up`() {
    assertTrue(isSymbolLike("orderGrid"))
    assertTrue(isSymbolLike("app.view.OrderGrid"))
    assertFalse(isSymbolLike("/?d=onko&c=Exec"))
    assertFalse(isSymbolLike("Заказы"))
  }

  @Test
  fun `an unclosed class at the end of an edited file still has its members`() {
    val partial = scanFile("Ext.define('a.B', {\n  extend: 'a.A',\n  foo: function () {")
    assertEquals("a.A", partial.classes.single().extend)
    assertEquals(listOf("extend", "foo"), partial.classes.single().members.map { it.name })
  }

  @Test
  fun `any variable assigned this is an alias, a component taken from this is not`() {
    val text = """
      Ext.define('app.Form', {
        onSave: function () {
          var _ths = this, win = this.up('window');
          const scope = this;
          let same = _ths == this;
          ths = this;
          _ths.reload(); scope.reload(); win.close();
        }
      });
    """.trimIndent()
    val cls = scanFile(text).classes.single()
    val aliases = selfAliases(text, cls, text.indexOf("_ths.reload"))
    assertEquals(setOf("this", "_ths", "scope", "ths"), aliases)
    assertEquals(setOf("this"), selfAliases(text, cls, text.indexOf("onSave")))
  }
}
