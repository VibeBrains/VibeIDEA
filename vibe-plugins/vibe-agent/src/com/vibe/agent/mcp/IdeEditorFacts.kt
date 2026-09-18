// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * Что сейчас открыто в редакторе IDE.
 *
 * Отдельно от [VibeMcpTools] по одной причине: здесь живут правила ПОТОКОВ, а не правила
 * инструмента. Редактор принадлежит EDT, а инструмент зовут из потока хода с потолком в тридцать
 * секунд, — поэтому чтение идёт через `invokeAndWait` и read action, и это надо было написать в
 * одном месте, а не повторять в каждом вызове.
 */
object IdeEditorFacts {
  data class Selection(val fromLine: Int, val toLine: Int, val text: String)

  data class OpenFile(
    /** Полный путь: его же проверяет политика прав, а относительный она не поймёт. */
    val path: String,
    val text: String,
    val selection: Selection?,
    /** Пути остальных открытых вкладок — над чем человек работает, видно по ним. */
    val otherTabs: List<String>,
  )

  /**
   * Файл под курсором, или null — если редактор пуст.
   *
   * Берётся ВЫБРАННЫЙ текстовый редактор, а не первая вкладка: человек говорит «открытый файл» про
   * тот, который видит.
   */
  fun selected(project: Project): OpenFile? {
    var result: OpenFile? = null
    ApplicationManager.getApplication().invokeAndWait {
      result = ReadAction.compute<OpenFile?, RuntimeException> { read(project) }
    }
    return result
  }

  private fun read(project: Project): OpenFile? {
    val manager = FileEditorManager.getInstance(project)
    val editor = manager.selectedTextEditor ?: return null
    val document = editor.document
    val file = FileDocumentManager.getInstance().getFile(document) ?: return null
    val model = editor.selectionModel
    val selection = if (model.hasSelection()) {
      Selection(
        fromLine = document.getLineNumber(model.selectionStart) + 1,
        toLine = document.getLineNumber(model.selectionEnd) + 1,
        text = model.selectedText.orEmpty(),
      )
    }
    else null
    val others = manager.openFiles.map { it.path }.filter { it != file.path }
    // Текст берётся у ДОКУМЕНТА, а не у файла на диске: несохранённые правки человека — это и есть
    // то, о чём он спрашивает, а файл на диске отстаёт от них ровно на несохранённое.
    return OpenFile(path = file.path, text = document.text, selection = selection, otherTabs = others)
  }
}
