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

  /**
   * Ответ на вопрос «что не так с файлом» — тремя РАЗНЫМИ состояниями.
   *
   * Раньше их было два, и это была ложь: пока анализ не догнал последние правки, мы возвращали
   * пустой список, то есть говорили «ошибок нет» там, где правильный ответ «ещё считаю». Агент на
   * этом закрывал задачу, ничего не сделав (перечитка 18.09.2026).
   */
  sealed interface Result {
    data class Found(val problems: List<Problem>) : Result
    /** Файла нет в редакторе, поэтому разметки не существует. */
    data object NotOpen : Result
    /** Документ изменён, анализ ещё не пересчитал: спрашивать надо через мгновение. */
    data object NotReady : Result
  }

  /** Файлы, по которым можно спросить без открытия: те, что уже открыты в редакторе. */
  fun openPaths(project: Project): List<String> {
    var paths: List<String> = emptyList()
    ApplicationManager.getApplication().invokeAndWait {
      paths = FileEditorManager.getInstance(project).openFiles.map { it.path }
    }
    return paths
  }

  /**
   * Проблемы файла одним из трёх состояний [Result].
   *
   * Пустой список внутри [Result.Found] означает ровно то, что означает: IDE посчитала и ничего не
   * нашла. Для остальных двух случаев есть свои ответы — и в этом вся разница.
   */
  fun of(project: Project, path: String, minSeverity: HighlightSeverity = HighlightSeverity.WEAK_WARNING): Result {
    var result: Result = Result.NotOpen
    ApplicationManager.getApplication().invokeAndWait {
      result = ReadAction.compute<Result, RuntimeException> { read(project, path, minSeverity) }
    }
    return result
  }

  private fun read(project: Project, path: String, minSeverity: HighlightSeverity): Result {
    val file = LocalFileSystem.getInstance().findFileByIoFile(File(path)) ?: return Result.NotOpen
    val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return Result.NotOpen
    // Незакоммиченный документ означает, что анализ ещё не видел последних правок: отвечать по
    // старой разметке значит отправить агента чинить то, что человек уже поправил, а отвечать
    // пустым списком — сказать «ошибок нет», чего мы не знаем.
    if (PsiDocumentManager.getInstance(project).isUncommited(document)) return Result.NotReady
    val found = ArrayList<Problem>()
    DaemonCodeAnalyzerEx.processHighlights(document, project, minSeverity, 0, document.textLength) { info ->
      describe(document, info)?.let { found += it }
      true
    }
    return Result.Found(found.sortedBy { it.line })
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
