// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.preview

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.vibe.agent.ui.VibeToolWindows

/**
 * Открыть адрес во встроенном браузере — точка входа для остальных плагинов.
 *
 * Браузер в IDE ровно один, и живёт он в панели «Дизайн»: у CEF цена в сотни мегабайт на экземпляр,
 * а второй браузер к тому же измерял бы не ту страницу, которую видит дизайн-гейт. Поэтому
 * «показать превью» из панели стека — это не свой браузер, а просьба к существующему.
 *
 * @return открылось ли; false означает «встроенного браузера в этой сборке нет» — вызывающий обязан
 *   сказать это человеку, а не молчать: пустая панель выглядит как поломка.
 */
object PreviewOpener {
  fun open(project: Project, url: String): Boolean {
    // Ответ даётся ДО активации: содержимое панели создаётся при показе, и решать по результату
    // колбэка значило бы отвечать «не открылось» каждый первый раз.
    if (!com.intellij.ui.jcef.JBCefApp.isSupported()) return false
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(VibeToolWindows.DESIGN) ?: return false
    toolWindow.activate({
      toolWindow.contentManager.contents
        .firstNotNullOfOrNull { it.component as? com.vibe.agent.design.DesignPreviewPanel }
        ?.openAt(url)
    }, true)
    return true
  }
}
