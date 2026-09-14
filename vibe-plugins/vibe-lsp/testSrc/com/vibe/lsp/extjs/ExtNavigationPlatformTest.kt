// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Переход на живой платформе: индексы, обработчик и открытие файла на смещении.
 *
 * Разбор по тексту проверяют чистые тесты, а здесь проверяется то, чего они не видят по построению:
 * что индексы действительно собрались на файлах проекта и что обработчик вернул цель, ведущую в место
 * объявления, а не в начало файла.
 */
class ExtNavigationPlatformTest : BasePlatformTestCase() {
  private val parent = """
    Ext.define('common.Modals.BaseModal', {
      extend: 'Ext.window.Window'
    });
  """.trimIndent()

  private val child = """
    Ext.define('common.Modals.ExecModal', {
      extend: 'common.Modals.BaseModal',
      alias: 'widget.execModal'
    });
  """.trimIndent()

  private val baseGrid = """
    Ext.define('app.BaseGrid', {
      mixins: ['app.Filterable'],
      config: { store: null },
      initComponent: function () {
        this.addDocked();
      },
      reload: function () {}
    });
  """.trimIndent()

  private val filterable = """
    Ext.define('app.Filterable', {
      applyFilter: function () {}
    });
  """.trimIndent()

  private val orderGrid = """
    Ext.define('app.OrderGrid', {
      extend: 'app.BaseGrid',
      items: [{ xtype: 'execModal' }],
      initComponent: function () {
        var me = this;
        me.callParent(arguments);
        this.reload();
        me.applyFilter();
        this.getStore();
        me.fireEvent('ordersloaded', me);
      }
    });
  """.trimIndent()

  private val listener = """
    Ext.define('app.OrderPanel', {
      listeners: { ordersloaded: 'onLoaded' }
    });
  """.trimIndent()

  private fun targetsAt(path: String, text: String, needle: String, from: String? = null): Array<PsiElement>? {
    val file = myFixture.addFileToProject(path, text)
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    val start = from?.let { text.indexOf(it) } ?: 0
    myFixture.editor.caretModel.moveToOffset(text.indexOf(needle, start) + 1)
    return ExtGotoDeclarationHandler().getGotoDeclarationTargets(myFixture.file, myFixture.caretOffset, myFixture.editor)
  }

  private fun assertSingleTarget(targets: Array<PsiElement>?, fileName: String, expectedOffset: Int) {
    assertNotNull("перехода нет вовсе", targets)
    assertEquals(1, targets!!.size)
    assertEquals(fileName, targets[0].containingFile.name)
    assertEquals("цель обязана указывать на само имя, а не на начало файла", expectedOffset, targets[0].textOffset)
  }

  private fun addHierarchy() {
    myFixture.addFileToProject("app/BaseGrid.js", baseGrid)
    myFixture.addFileToProject("app/Filterable.js", filterable)
  }

  fun `test клик по имени родителя ведёт в его объявление`() {
    myFixture.addFileToProject("app/BaseModal.js", parent)
    val targets = targetsAt("app/ExecModal.js", child, "common.Modals.BaseModal", from = "extend")
    assertSingleTarget(targets, "BaseModal.js", parent.indexOf("common.Modals.BaseModal"))
  }

  fun `test клик по псевдониму ведёт в класс, который его объявил`() {
    val targets = targetsAt("app/ExecModal.js", child, "widget.execModal")
    assertEquals(1, targets!!.size)
    assertEquals("ExecModal.js", targets[0].containingFile.name)
  }

  fun `test короткий xtype ведёт в класс с alias widget`() {
    myFixture.addFileToProject("app/ExecModal.js", child)
    addHierarchy()
    val targets = targetsAt("app/OrderGrid.js", orderGrid, "execModal")
    assertSingleTarget(targets, "ExecModal.js", child.indexOf("widget.execModal"))
  }

  fun `test this-метод из родителя`() {
    addHierarchy()
    val targets = targetsAt("app/OrderGrid.js", orderGrid, "reload")
    assertSingleTarget(targets, "BaseGrid.js", baseGrid.indexOf("reload"))
  }

  fun `test me-метод из примеси родителя`() {
    addHierarchy()
    val targets = targetsAt("app/OrderGrid.js", orderGrid, "applyFilter")
    assertSingleTarget(targets, "Filterable.js", filterable.indexOf("applyFilter"))
  }

  fun `test callParent ведёт в тот же метод родителя`() {
    addHierarchy()
    val targets = targetsAt("app/OrderGrid.js", orderGrid, "callParent")
    assertSingleTarget(targets, "BaseGrid.js", baseGrid.indexOf("initComponent"))
  }

  fun `test геттер ведёт в ключ config`() {
    addHierarchy()
    val targets = targetsAt("app/OrderGrid.js", orderGrid, "getStore")
    assertSingleTarget(targets, "BaseGrid.js", baseGrid.indexOf("store"))
  }

  fun `test событие показывает, где его слушают`() {
    myFixture.addFileToProject("app/OrderPanel.js", listener)
    addHierarchy()
    val targets = targetsAt("app/OrderGrid.js", orderGrid, "ordersloaded")
    assertSingleTarget(targets, "OrderPanel.js", listener.indexOf("ordersloaded"))
  }

  fun `test минифицированный файл не даёт целей`() {
    myFixture.addFileToProject("ext/ext-all.js", "Ext.define('app.Minified',{});" + "var x=1;".repeat(MINIFIED_LINE_CHARS))
    val targets = targetsAt("app/Use.js", "Ext.create('app.Minified');", "app.Minified")
    assertTrue("минифицированный код не индексируется", targets == null || targets.isEmpty())
  }

  fun `test строка с путём открывает файл проекта`() {
    myFixture.addFileToProject("app/BaseModal.js", parent)
    val targets = targetsAt("app/Loader.js", "var path = 'BaseModal.js';", "BaseModal.js")
    assertEquals(1, targets!!.size)
    assertEquals("BaseModal.js", targets[0].containingFile.name)
  }

  fun `test строка, которая никуда не ведёт, перехода не даёт`() {
    val targets = targetsAt("app/ExecModal.js", child, "Ext.window.Window")
    assertTrue("несуществующий класс не должен давать цели", targets == null || targets.isEmpty())
  }

  private val numberConfigs = """
    Ext.define('app.constants.NumberConfigs', {
      singleton: true,
      spinnerInteger: { allowDecimals: false },
      spinnerDecimal: { allowDecimals: true }
    });
  """.trimIndent()

  private val form = """
    Ext.define('app.OrderForm', {
      extend: 'app.BaseGrid',
      initComponent: function () {
        const _ths = this;
        const constants = app.constants;
        const spinnerInteger = constants.NumberConfigs.spinnerInteger;
        _ths.reload();
        const win = this.up('window');
        win.spinnerDecimal;
      }
    });
  """.trimIndent()

  fun `test псевдоним this с любым именем ведёт в метод родителя`() {
    addHierarchy()
    val targets = targetsAt("app/OrderForm.js", form, "reload", from = "_ths.reload")
    assertSingleTarget(targets, "BaseGrid.js", baseGrid.indexOf("reload"))
  }

  fun `test свойство singleton-константы по цепочке ведёт в объявление`() {
    myFixture.addFileToProject("app/constants/NumberConfigs.js", numberConfigs)
    val targets = targetsAt("app/OrderForm.js", form, "spinnerInteger", from = "NumberConfigs.")
    assertSingleTarget(targets, "NumberConfigs.js", numberConfigs.indexOf("spinnerInteger"))
  }

  fun `test свойство у получателя неизвестного класса находится по имени`() {
    myFixture.addFileToProject("app/constants/NumberConfigs.js", numberConfigs)
    val targets = targetsAt("app/OrderForm.js", form, "spinnerDecimal", from = "win.")
    assertSingleTarget(targets, "NumberConfigs.js", numberConfigs.indexOf("spinnerDecimal"))
  }
}
