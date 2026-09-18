// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * Две формы, из которых собирается любая наша страница настроек: сама страница и подсказка на ней.
 *
 * Дефект «страница едет вбок» возвращался четырежды, и каждый раз чинился по-своему, потому что
 * причин у него ДВЕ, а лечили одну.
 *
 * Первая — прокрутка. Оборачивание в [TracksViewportWidthPanel] заставляет вид следовать ширине
 * окна, но только до МИНИМАЛЬНОЙ ширины содержимого: `JViewport` ниже минимума не сжимает. Поэтому
 * минимум панели объявлен нулевым, а горизонтальная полоса выключена совсем — странице настроек
 * ездить вбок незачем ни при каких обстоятельствах.
 *
 * Вторая — сама подсказка. `JBLabel("<html>…")` сообщает ширину В ОДНУ СТРОКУ: страница из-за одной
 * длинной фразы просит ширину в полторы тысячи точек, и всё остальное на ней разъезжается следом.
 * Это ровно то, что владелец увидел на странице языковых серверов 18.09.2026 — подписи ушли за левый
 * край. Копируемый `JBLabel` внутри устроен как текстовая панель: переносит по ширине и честно
 * сообщает высоту после переноса, а заодно позволяет выделить текст подсказки.
 *
 * Правило проекта поэтому спрашивается с одной строки: страница — [page], подсказка — [hint].
 * Проверяет `checkVibeUi.sh`.
 */
object SettingsUi {
  /** Содержимое страницы настроек: вертикальная прокрутка и никакой горизонтальной. */
  fun page(content: JComponent): JScrollPane =
    com.vibe.agent.ui.VibeScroll.pane(TracksViewportWidthPanel(content)).apply {
      horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
      verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
      border = JBUI.Borders.empty()
    }

  /** Заголовок раздела страницы: то же правило переноса, только полужирным и с отступом сверху. */
  fun section(text: String): JComponent = hint("<b>$text</b>").apply {
    foreground = JBColor.foreground()
    border = JBUI.Borders.emptyTop(8)
  }

  /**
   * Длинное объяснение под настройкой.
   *
   * [text] — как он лежит в каталоге строк, без обрамляющего `<html>`: тег добавляется здесь, иначе
   * о нём забывают ровно в той строке, которая потом и ломает страницу.
   */
  fun hint(text: String): JComponent = JBLabel("<html>$text</html>").apply {
    foreground = JBColor.GRAY
    setCopyable(true)
    setAllowAutoWrapping(true)
    // Минимум в ноль — вторая половина того же правила: с ненулевым минимумом окно не сожмётся
    // ниже него, и полоса прокрутки вернулась бы, сколько бы вид ни следовал за шириной.
    minimumSize = java.awt.Dimension(0, 0)
  }
}
