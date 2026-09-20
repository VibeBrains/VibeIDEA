// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * Вид страницы настроек: следует ширине окна, а когда следовать уже некуда — честно прокручивается.
 *
 * Дефект «страница едет вбок» возвращался ТРИЖДЫ, и каждый раз у него находилась новая причина:
 * обёртка страницы (18.09), ширина подсказки (19.09), несжимаемый список (20.09). Каждый гейт
 * закрывал свою и честно зеленел, пока следующая была жива. Общее у всех трёх — не причина, а
 * СЛЕДСТВИЕ: содержимое, которое не поместилось, **молча обрезалось**.
 *
 * Молчание и есть то, что чинится здесь раз и навсегда. Пока горизонтальной полосы нет, любая
 * будущая причина — новый компонент с большим минимумом, чужой виджет, длинная подпись — даст
 * ровно тот же симптом: человек увидит обрезанную страницу и напишет об этом снова.
 *
 * Поэтому правило стало другим: страница следует ширине окна, ПОКА окно не уже минимума
 * содержимого, а ниже — отдаёт полосу прокрутки. Ничего не теряется ни при каких
 * обстоятельствах; полоса при этом появляется редко, потому что минимум держат наши формы
 * ([SettingsUi]) и меряет `SettingsPageShrinkTest`.
 */
class TracksViewportWidthPanel(content: JComponent) : JPanel(BorderLayout()), Scrollable {
  init {
    add(content, BorderLayout.NORTH)
  }

  /**
   * Минимум — настоящий, а не нулевой.
   *
   * Нулевой минимум был половиной прежнего правила: он позволял `JViewport` сжимать вид сколько
   * угодно, и содержимое уезжало за край без следа. Теперь он называет ту ширину, ниже которой
   * содержимое честно не помещается, — и именно по ней [getScrollableTracksViewportWidth]
   * решает, пора ли отдавать полосу прокрутки.
   */
  override fun getMinimumSize(): Dimension = super.getMinimumSize()

  override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
  override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(UNIT_INCREMENT)
  override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = visible.height
  /**
   * Следовать ширине окна — пока окно не уже минимума содержимого.
   *
   * Классический приём Swing: вернуть `false`, когда вид уже не помещается, — тогда `JScrollPane`
   * даёт ему его минимальную ширину и показывает горизонтальную полосу. Это и есть замена
   * молчаливой обрезке.
   */
  override fun getScrollableTracksViewportWidth(): Boolean {
    val viewport = parent as? javax.swing.JViewport ?: return true
    return viewport.width >= minimumSize.width
  }
  override fun getScrollableTracksViewportHeight(): Boolean = false

  private companion object {
    const val UNIT_INCREMENT = 16
  }
}
