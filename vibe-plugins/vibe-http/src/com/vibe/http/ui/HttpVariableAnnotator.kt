// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.http.HttpEnvironmentChoice
import com.vibe.http.HttpTokenTypes
import java.nio.file.Path

/**
 * Подсветка неподставленной переменной прямо в редакторе.
 *
 * До отправки, а не после: запрос с `{{token}}` уходит на сервер и получает 401, и человек идёт
 * искать ошибку в авторизации. Предупреждение на месте называет причину до первого нажатия.
 *
 * Именно предупреждение, а не ошибка: переменная может приезжать из окружения, которого нет на
 * этой машине, и красным подчёркивать чужой рабочий файл мы права не имеем.
 */
class HttpVariableAnnotator : Annotator {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    if (element.node?.elementType != HttpTokenTypes.VARIABLE) return
    val name = element.text.removePrefix("{{").removeSuffix("}}").trim()
    if (name.startsWith("$")) {
      // Динамические известны всегда; незнакомое имя после «$» — опечатка, и о ней стоит сказать.
      if (name.removePrefix("$") in HttpEnvironmentChoice.DYNAMIC) return
      holder.newAnnotation(HighlightSeverity.WARNING, t("http.annotate.unknownDynamic",
        "name" to name, "known" to HttpEnvironmentChoice.DYNAMIC.joinToString { "\$$it" }))
        .range(element).create()
      return
    }
    val file = element.containingFile ?: return
    val dir = file.virtualFile?.parent?.path?.let { runCatching { Path.of(it) }.getOrNull() }
    if (name in HttpEnvironmentChoice.variables(file.project, dir, file.text)) return
    holder.newAnnotation(HighlightSeverity.WARNING, t("http.annotate.unresolved", "name" to name))
      .range(element).create()
  }
}
