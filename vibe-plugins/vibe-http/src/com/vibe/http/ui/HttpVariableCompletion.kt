// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.vibe.http.HttpEnvironmentChoice
import java.nio.file.Path

/**
 * Подсказки внутри `{{…}}`: имена из выбранного окружения, переменные файла и динамические.
 *
 * Забытая или переименованная переменная — самая частая ошибка в этих файлах, и подсказка снимает
 * её до отправки. Значение показывается рядом с именем, потому что «host» без «http://localhost»
 * не отвечает на вопрос «то ли это окружение, что мне нужно».
 */
class HttpVariableCompletion : CompletionContributor() {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    val file = parameters.originalFile
    if (file.fileType != com.vibe.http.HttpFileType.INSTANCE) return
    val text = file.text
    val offset = parameters.offset
    // Подсказываем только внутри двойных фигурных скобок: в остальном тексте это был бы шум.
    val open = text.lastIndexOf("{{", offset.coerceAtMost(text.length))
    if (open < 0) return
    val closeBefore = text.lastIndexOf("}}", offset.coerceAtMost(text.length))
    if (closeBefore > open) return

    val dir = file.virtualFile?.parent?.path?.let { runCatching { Path.of(it) }.getOrNull() }
    val variables = HttpEnvironmentChoice.variables(file.project, dir, text)
    for ((name, value) in variables) {
      result.addElement(
        LookupElementBuilder.create(name)
          .withIcon(AllIcons.Nodes.Variable)
          .withTypeText(value.take(40), true)
      )
    }
    for (name in HttpEnvironmentChoice.DYNAMIC) {
      result.addElement(LookupElementBuilder.create("\$$name").withIcon(AllIcons.Nodes.Constant))
    }
    result.stopHere()
  }
}
