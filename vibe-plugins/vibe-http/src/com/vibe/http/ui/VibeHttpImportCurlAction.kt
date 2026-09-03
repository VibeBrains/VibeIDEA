// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.http.CurlConversion
import com.vibe.http.HttpFileType
import java.awt.datatransfer.DataFlavor

/**
 * «Вставить cURL как запрос» — команда из буфера обмена превращается в блок `.http`.
 *
 * Разбор `curl` был написан вместе с форматом, но позвать его было негде: возможность существовала
 * и не работала (найдено ревизией 03.09.2026). А приходит запрос почти всегда именно так — из
 * документации, из тикета, из вкладки «Network» браузера, где «Copy as cURL» есть у всех.
 */
class VibeHttpImportCurlAction : AnAction({ t("http.action.importCurl") }) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
    e.presentation.isEnabledAndVisible =
      e.getData(CommonDataKeys.EDITOR) != null && file?.extension?.lowercase() in HttpFileType.EXTENSIONS
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val clipboard = runCatching {
      CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
    }.getOrNull().orEmpty()
    val request = CurlConversion.fromCurl(clipboard)
    if (request == null) {
      // Отказ называет причину: молча ничего не вставить — худший из ответов, человек решит,
      // что действие сломано, а не что в буфере лежит не команда.
      Messages.showWarningDialog(project, t("http.importCurl.notCurl"), t("http.action.importCurl"))
      return
    }
    val text = "\n" + CurlConversion.toHttpFile(request)
    WriteCommandAction.runWriteCommandAction(project, t("http.action.importCurl"), null, {
      val offset = editor.document.textLength
      editor.document.insertString(offset, text)
      editor.caretModel.moveToOffset(offset + 1)
    })
  }
}
