// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.util.registry.Registry
import javax.swing.UIManager

/**
 * Тонкий скролл в РЕДАКТОРЕ — единственном месте, куда общая правка не доставала.
 *
 * Почему не доставала: у редактора скроллбар не обычный. Когда полоса разметки включена (а она
 * включена всегда), `EditorMarkupModelImpl` ставит ему собственный UI `MyErrorPanel`, минуя
 * `JBScrollBar.createUI` вместе с нашим выбором тонкого варианта. Его толщина считается по-своему:
 * ширина самого скролла плюс зазор плюс высота отметки — и первую часть платформа берёт из
 * UI-свойства `Editor.scrollBarWidth` (по умолчанию 14).
 *
 * Отсюда решение: **не патчить платформу второй раз**, а положить в это свойство нашу толщину.
 * `JBValue.UIInteger` читает `UIManager` на каждый запрос, поэтому значение применяется без
 * пересоздания редакторов и следует за настройкой.
 *
 * Честная граница, которую надо знать: **полностью тонким скролл редактора не станет**. Кроме
 * самого бегунка в его ширину входит полоса разметки — те самые риски ошибок и предупреждений
 * справа, ради которых на неё и смотрят. Сжать её в четыре пикселя значит выбросить возможность,
 * а не сделать интерфейс тоньше; поэтому уменьшается ровно бегунок.
 */
object EditorScrollBarWidth {
  /** UI-свойство платформы: ширина бегунка скролла в редакторе, без полосы разметки. */
  private const val PROPERTY = "Editor.scrollBarWidth"

  /** Значение платформы по умолчанию — к нему возвращаемся, когда тонкие скроллы выключены. */
  private const val PLATFORM_DEFAULT = 14

  private const val KEY = "vibe.scrollbar.thickness"
  private const val DEFAULT_THICKNESS = 4

  /**
   * Ставит ширину по текущей настройке. Зовётся при старте и при каждой смене настройки или темы:
   * тема пересоздаёт значения `UIManager`, и без повтора редактор молча потолстел бы обратно.
   */
  fun apply() {
    val thickness = Registry.intValue(KEY, DEFAULT_THICKNESS)
    UIManager.put(PROPERTY, if (thickness > 0) thickness else PLATFORM_DEFAULT)
  }
}

/**
 * Смена темы пересоздаёт значения `UIManager`, и ширина редакторного скролла молча возвращается к
 * платформенным 14. Слушатель ставит её заново — иначе настройка «работает, пока не поменяешь тему»,
 * а это худший вид работающей настройки: сломается она позже и без связи с причиной.
 */
class EditorScrollBarWidthLafListener : com.intellij.ide.ui.LafManagerListener {
  override fun lookAndFeelChanged(source: com.intellij.ide.ui.LafManager) {
    EditorScrollBarWidth.apply()
  }
}

