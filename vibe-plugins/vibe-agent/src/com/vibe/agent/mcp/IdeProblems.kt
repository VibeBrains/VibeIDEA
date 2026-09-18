// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import java.io.File

/**
 * Ошибки и предупреждения, которые человек ВИДИТ в редакторе, — агенту.
 *
 * Разрыв, который это закрывает: «почини ошибки в этом файле» агент выполнить не мог, потому что
 * ошибок не видел вовсе. Он мог прочитать файл и рассуждать о нём, но подчёркивания, которые IDE
 * уже посчитала — инспекции платформы, диагностика языкового сервера, ошибки разбора, — оставались
 * только на экране. Из-за этого агент в чате отличался от агента в IDE ровно ничем: он работал с
 * текстом, а не с проектом (жалоба владельца про «интеграцию в кору», 18.09.2026).
 *
 * Берём ТУ ЖЕ разметку документа, которую рисует редактор, а не запускаем инспекции заново: второй
 * проход дал бы другой набор (у него другой контекст и другие настройки профиля), и агент спорил бы
 * с тем, что человек видит своими глазами.
 *
 * Цена этого решения названа честно в описании инструмента: разметка существует только у ОТКРЫТОГО
 * документа. Для закрытого файла ответ — «откройте его», а не пустой список: пустой список означал
 * бы «ошибок нет», и агент закрыл бы задачу, ничего не сделав.
 */
object IdeProblems {
  data class Problem(val line: Int, val severity: String, val message: String, val text: String)

  /** Файлы, по которым можно спросить без открытия: те, что уже открыты в редакторе. */
  fun openPaths(project: Project): List<String> {
    var paths: List<String> = emptyList()
    ApplicationManager.getApplication().invokeAndWait {
      paths = FileEditorManager.getInstance(project).openFiles.map { it.path }
    }
    return paths
  }

  /**
   * Проблемы файла, или null — если его разметки нет (файл не открыт).
   *
   * Список пустой означает ровно то, что означает: IDE посчитала и ничего не нашла.
   */
  fun of(project: Project, path: String, minSeverity: HighlightSeverity = HighlightSeverity.WEAK_WARNING): List<Problem>? {
    var result: List<Problem>? = null
    ApplicationManager.getApplication().invokeAndWait {
      result = ReadAction.compute<List<Problem>?, RuntimeException> { read(project, path, minSeverity) }
    }
    return result
  }

  private fun read(project: Project, path: String, minSeverity: HighlightSeverity): List<Problem>? {
    val file = LocalFileSystem.getInstance().findFileByIoFile(File(path)) ?: return null
    val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
    // Незакоммиченный документ означает, что анализ ещё не видел последних правок: отвечать по
    // старой разметке значит отправить агента чинить то, что человек уже поправил.
    if (PsiDocumentManager.getInstance(project).isUncommited(document)) return emptyList()
    val found = ArrayList<Problem>()
    DaemonCodeAnalyzerEx.processHighlights(document, project, minSeverity, 0, document.textLength) { info ->
      describe(document, info)?.let { found += it }
      true
    }
    return found.sortedBy { it.line }
  }

  private fun describe(document: Document, info: HighlightInfo): Problem? {
    val message = info.description?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val line = document.getLineNumber(info.startOffset.coerceIn(0, document.textLength)) + 1
    val from = document.getLineStartOffset(line - 1)
    val to = document.getLineEndOffset(line - 1)
    return Problem(
      line = line,
      severity = info.severity.name,
      message = message,
      text = document.getText(com.intellij.openapi.util.TextRange(from, to)).trim().take(LINE_CHARS),
    )
  }

  private const val LINE_CHARS = 200
}
