// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.extjs

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex

/**
 * Ctrl+Click по строке в Ext JS: имя класса ведёт в его объявление, путь — в файл.
 *
 * В PhpStorm это делает закрытый плагин JavaScript, которого в открытой платформе нет. Обработчик
 * перехода выбран потому, что он единственный работает **без PSI**: `.js` у нас показывает TextMate,
 * и вешать ссылки не на что — а здесь достаточно документа и позиции курсора.
 */
class ExtGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
    val project = editor.project ?: return null
    val literal = literalAt(editor.document.charsSequence, offset) ?: return null
    if (literal.isEmpty()) return null

    val currentFile = sourceElement?.containingFile?.virtualFile
    if (isExtClassName(literal)) {
      val definitions = definitionsOf(project, literal)
      if (definitions.isNotEmpty()) return definitions.toTypedArray()
    }
    val file = fileOf(project, currentFile, literal) ?: return null
    return arrayOf(file)
  }

  /** Объявления класса или псевдонима во всём проекте: одноимённых может оказаться несколько. */
  private fun definitionsOf(project: Project, name: String): List<PsiElement> {
    val targets = ArrayList<PsiElement>()
    val manager = PsiManager.getInstance(project)
    FileBasedIndex.getInstance().processValues(
      ExtDefineIndex.NAME, name, null,
      { file, offset ->
        val psiFile = manager.findFile(file)
        if (psiFile != null) targets.add(ExtDefinitionTarget(psiFile, offset, name))
        true
      },
      GlobalSearchScope.projectScope(project),
    )
    return targets
  }

  /**
   * Строка, которая на самом деле путь к файлу проекта.
   *
   * Сначала рядом с текущим файлом, потом от корня проекта — тот же порядок, в котором путь читает
   * человек. Не нашлось — перехода нет: подсвечивать ссылкой то, что никуда не ведёт, хуже, чем
   * ничего.
   */
  private fun fileOf(project: Project, currentFile: VirtualFile?, path: String): PsiFile? {
    val candidate = path.trim().removePrefix("./")
    if (candidate.isEmpty() || candidate.contains('\n')) return null
    val roots = listOfNotNull(currentFile?.parent, project.baseDir())
    for (root in roots) {
      val found = root.findFileByRelativePath(candidate) ?: continue
      if (found.isDirectory) continue
      return PsiManager.getInstance(project).findFile(found)
    }
    return null
  }

  private fun Project.baseDir(): VirtualFile? {
    val path = basePath ?: return null
    return com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
  }
}

/**
 * Место объявления как цель перехода.
 *
 * Настоящего PSI-элемента под объявлением нет — файл плоский, — поэтому цель открывает файл на
 * нужном смещении сама. Без этого переход приводил бы в начало файла, что для файла на тысячу строк
 * равносильно отсутствию перехода.
 */
private class ExtDefinitionTarget(
  private val file: PsiFile,
  private val offset: Int,
  private val name: String,
) : FakePsiElement() {
  override fun getParent(): PsiElement = file

  override fun getContainingFile(): PsiFile = file

  override fun getName(): String = name

  // Смещение обязано быть у самого элемента, а не только внутри navigate(): по нему платформа
  // показывает предпросмотр и выбирает строку в списке, когда объявлений найдено несколько.
  override fun getTextOffset(): Int = offset

  override fun getTextRange(): TextRange = TextRange(offset, offset + name.length)

  override fun canNavigate(): Boolean = file.virtualFile != null

  override fun navigate(requestFocus: Boolean) {
    val virtualFile = file.virtualFile ?: return
    OpenFileDescriptor(file.project, virtualFile, offset).navigate(requestFocus)
  }
}
