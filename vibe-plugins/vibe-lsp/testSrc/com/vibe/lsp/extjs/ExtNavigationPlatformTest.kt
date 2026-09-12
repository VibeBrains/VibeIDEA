// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Переход на живой платформе: индекс, обработчик и открытие файла на смещении.
 *
 * Разбор по тексту проверяют чистые тесты, а здесь проверяется то, чего они не видят по построению:
 * что индекс действительно собрался на файлах проекта и что обработчик вернул цель, ведущую в место
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

  fun `test клик по имени родителя ведёт в его объявление`() {
    myFixture.addFileToProject("app/BaseModal.js", parent)
    val file = myFixture.addFileToProject("app/ExecModal.js", child)
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.editor.caretModel.moveToOffset(child.indexOf("common.Modals.BaseModal", child.indexOf("extend")))

    val targets = ExtGotoDeclarationHandler()
      .getGotoDeclarationTargets(myFixture.file, myFixture.caretOffset, myFixture.editor)

    assertNotNull("перехода нет вовсе", targets)
    assertEquals(1, targets!!.size)
    val target = targets[0]
    assertEquals("BaseModal.js", target.containingFile.name)
    assertEquals(
      "цель обязана указывать на само имя, а не на начало файла",
      parent.indexOf("common.Modals.BaseModal"),
      target.textOffset,
    )
  }

  fun `test клик по псевдониму ведёт в класс, который его объявил`() {
    val file = myFixture.addFileToProject("app/ExecModal.js", child)
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.editor.caretModel.moveToOffset(child.indexOf("widget.execModal"))

    val targets = ExtGotoDeclarationHandler()
      .getGotoDeclarationTargets(myFixture.file, myFixture.caretOffset, myFixture.editor)

    assertEquals(1, targets!!.size)
    assertEquals("ExecModal.js", targets[0].containingFile.name)
  }

  fun `test строка с путём открывает файл проекта`() {
    myFixture.addFileToProject("app/BaseModal.js", parent)
    val file = myFixture.addFileToProject("app/Loader.js", "var path = 'BaseModal.js';")
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("BaseModal.js"))

    val targets = ExtGotoDeclarationHandler()
      .getGotoDeclarationTargets(myFixture.file, myFixture.caretOffset, myFixture.editor)

    assertEquals(1, targets!!.size)
    assertEquals("BaseModal.js", targets[0].containingFile.name)
  }

  fun `test строка, которая никуда не ведёт, перехода не даёт`() {
    val file = myFixture.addFileToProject("app/ExecModal.js", child)
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.editor.caretModel.moveToOffset(child.indexOf("Ext.window.Window"))

    val targets = ExtGotoDeclarationHandler()
      .getGotoDeclarationTargets(myFixture.file, myFixture.caretOffset, myFixture.editor)

    assertTrue("несуществующий класс не должен давать цели", targets == null || targets.isEmpty())
  }
}
