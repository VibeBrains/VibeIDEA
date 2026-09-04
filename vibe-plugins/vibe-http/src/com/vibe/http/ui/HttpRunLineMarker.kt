// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.http.HttpFileType
import com.vibe.http.HttpRequestFile
import com.vibe.http.HttpTokenTypes

/**
 * Зелёная стрелка на полях против каждого запроса.
 *
 * То, из-за чего инструмент вообще открывают в редакторе: запрос видно и запускать его можно там
 * же, где он написан. Значок ставится на строку запроса, а не на разделитель, потому что глаз
 * ищет метод и адрес.
 */
class HttpRunLineMarker : LineMarkerProvider {
  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Только на листе-методе: иначе платформа поставит значок и на файл целиком, и он будет
    // означать «выполнить что-то», чего человек не выбирал.
    if (element.node?.elementType != HttpTokenTypes.METHOD) return null
    val file = element.containingFile ?: return null
    if (file.fileType != HttpFileType.INSTANCE) return null
    val document = file.viewProvider.document ?: return null
    val line = document.getLineNumber(element.textRange.startOffset)
    val parsed = HttpRequestFile.parse(document.text)
    val request = HttpRequestFile.requestAt(parsed, line) ?: return null

    return LineMarkerInfo(
      element,
      element.textRange,
      AllIcons.RunConfigurations.TestState.Run,
      { t("http.gutter.run", "request" to request.title) },
      { _, _ ->
        val project = element.project
        val window = ToolWindowManager.getInstance(project).getToolWindow(com.vibe.agent.ui.VibeToolWindows.HTTP) ?: return@LineMarkerInfo
        window.activate {
          val panel = window.contentManager.contents.firstNotNullOfOrNull { it.component as? HttpPanel }
            ?: return@activate
          panel.reload()
          panel.run(request)
        }
      },
      GutterIconRenderer.Alignment.LEFT,
      { t("http.gutter.run", "request" to request.title) },
    )
  }
}
