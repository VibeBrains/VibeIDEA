// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement

/**
 * Переход по коду, который подчёркивает ТОЛЬКО то, что языковой сервер действительно резолвит.
 *
 * Чем это отличается от навигации LSP4IJ: их решение — про файл целиком
 * (`isDeclarationSupported(PsiFile)`), поэтому под курсором подчёркивается каждое слово, включая
 * те, где идти некуда. Спросить их API про КОНКРЕТНУЮ позицию нельзя — такого вопроса в нём нет.
 *
 * Наш ответ берётся из [VibeLspNavigation]: кэш отвечает мгновенно, промах заводит фоновый запрос
 * и честно говорит «пока не знаю» — то есть не подчёркивает. Через мгновение наведения ответ уже
 * есть, и подчёркивание появляется на том, что ведёт куда-то.
 *
 * Цель — [FakePsiElement] с дескриптором открытия: тот же приём, что в переходе по Ext JS, и по
 * той же причине — PSI у файлов на языковом сервере нет, а обработчику перехода хватает документа
 * и позиции.
 */
class VibeLspGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
    if (!VibeLspNavigation.isEnabled()) return null
    val project = editor.project ?: return null
    val document = editor.document
    val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
    val navigation = VibeLspNavigation.of(project)
    if (navigation.answer(file, document, offset) != LspDefinitionCache.Answer.RESOLVED) return null
    val targets = navigation.targets(file, document, offset).mapNotNull { element(project, it) }
    return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
  }

  private fun element(project: Project, target: LspDefinitionCache.Target): PsiElement? {
    val virtual = VirtualFileManager.getInstance().findFileByUrl(normalize(target.uri)) ?: return null
    val containing = PsiManager.getInstance(project).findFile(virtual) ?: return null
    return object : FakePsiElement() {
      override fun getParent(): PsiElement = containing

      override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(project, virtual, target.line, target.character).navigate(requestFocus)
      }

      override fun canNavigate(): Boolean = true

      override fun getName(): String = virtual.name

      // Смещение цели, а не ноль: по умолчанию FakePsiElement отдаёт ноль, и платформа считает,
      // что цель — начало файла (та же грабля, что в переходе по Ext JS).
      override fun getTextOffset(): Int = 0
    }
  }

  /**
   * Сервер отдаёт URI, а платформа хранит URL — и расходятся они процентным кодированием.
   *
   * `file:///Мои%20файлы/a.ts` от сервера и `file:///Мои файлы/a.ts` у платформы — один и тот же
   * файл, но по первому она не найдёт ничего. Симптом обманчив: сервер ответил правильно, цель
   * есть, а переход «не работает» — и искать начинают в навигации, а не в кодировании.
   *
   * Декодируем только если есть что декодировать: строка без `%` проходит насквозь.
   */
  private fun normalize(uri: String): String =
    if ('%' !in uri) uri
    else runCatching { java.net.URLDecoder.decode(uri, Charsets.UTF_8) }.getOrDefault(uri)
}
