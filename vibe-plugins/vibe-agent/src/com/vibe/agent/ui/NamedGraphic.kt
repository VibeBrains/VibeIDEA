// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent

/**
 * Рисованный компонент, у которого ЕСТЬ имя для экранного диктора.
 *
 * У голого `JComponent` поле `accessibleContext` пусто, пока наследник его не создаст, и запись
 * `accessibleContext.accessibleName = …` падает с NPE прямо в конструкторе. Так 20.09.2026 умерла
 * вся страница «Оформление» на живой 0.6.22 — ради подписи к образцу темы, которую хотели дать
 * экранному диктору. Здесь контекст создаётся лениво, как это делает сам `JPanel`.
 */
abstract class NamedGraphic(name: String) : JComponent() {
  init {
    toolTipText = name
  }

  private val described: String = name

  override fun getAccessibleContext(): AccessibleContext {
    if (accessibleContext == null) {
      accessibleContext = object : AccessibleJComponent() {
        override fun getAccessibleRole(): AccessibleRole = AccessibleRole.ICON
        override fun getAccessibleName(): String = described
      }
    }
    return accessibleContext
  }
}
