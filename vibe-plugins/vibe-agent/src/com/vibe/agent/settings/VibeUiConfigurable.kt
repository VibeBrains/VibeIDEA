// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.vibe.agent.ui.VibeScroll
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → VibeIDEA → Интерфейс: product-wide look knobs.
 *
 * Scrollbar thickness lives in the Registry key `vibe.scrollbar.thickness` rather than in our own
 * storage on purpose: the platform reads it (patched `JBScrollBar`, see FORK_CHANGES.md) and the
 * platform cannot depend on our plugin. The key is declared by this plugin via the `registryKey`
 * extension point, so it also shows up in the internal Registry — but a person should not have to
 * go there for something this visible.
 */
class VibeUiConfigurable : Configurable {
  private var thickness: JBIntSpinner? = null

  override fun getDisplayName(): String = "Интерфейс"

  override fun createComponent(): JComponent {
    val spinner = JBIntSpinner(currentThickness(), MIN_THICKNESS, MAX_THICKNESS).also { thickness = it }
    return FormBuilder.createFormBuilder()
      .addLabeledComponent("Толщина скроллов, px:", spinner)
      .addComponent(JBLabel("<html>Тонкие скроллы во всей IDE — дерево проекта, редактор, наши панели и всплывающие списки. " +
        "Штатные скроллы платформы — 10–14 px; <code>0</code> возвращает их. Значение применяется сразу к открытым окнам.</html>").apply {
        foreground = com.intellij.ui.JBColor.GRAY
        font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
      })
      .addComponentFillVertically(JPanel(), 0)
      .panel.apply { border = JBUI.Borders.empty(8) }
  }

  override fun isModified(): Boolean = (thickness?.number ?: currentThickness()) != currentThickness()

  override fun apply() {
    val value = thickness?.number ?: return
    Registry.get(KEY).setValue(value)
    // Existing scrollbars keep the UI they were built with — rebuild them so the change is visible
    // now, not after a restart.
    VibeScroll.refreshAllScrollBars()
  }

  override fun reset() {
    thickness?.value = currentThickness()
  }

  private fun currentThickness(): Int = Registry.intValue(KEY, DEFAULT_THICKNESS)

  private companion object {
    const val KEY = "vibe.scrollbar.thickness"
    const val DEFAULT_THICKNESS = 4
    const val MIN_THICKNESS = 0
    const val MAX_THICKNESS = 14
  }
}
