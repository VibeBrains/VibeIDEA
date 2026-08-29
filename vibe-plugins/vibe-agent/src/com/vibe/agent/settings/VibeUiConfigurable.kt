// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n
import com.vibe.agent.i18n.VibeI18n.t
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
  private var promo: JBCheckBox? = null
  private var language: com.intellij.openapi.ui.ComboBox<String>? = null

  override fun getDisplayName(): String = t("settings.ui.title")

  override fun createComponent(): JComponent {
    val spinner = JBIntSpinner(currentThickness(), MIN_THICKNESS, MAX_THICKNESS).also { thickness = it }
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(t("settings.ui.scrollThickness"), spinner)
      .addComponent(hint(t("settings.ui.scrollHint")))
      .addComponent(JBCheckBox(t("settings.ui.promo"), VibePromoSettings.isEnabled()).also { promo = it })
      .addComponent(hint(t("settings.ui.promoHint")))
      .addLabeledComponent(t("settings.ui.language"), languageCombo())
      .addComponent(hint(t("settings.ui.languageHint", "dir" to VibeI18n.langDir().toString())))
      .addComponent(com.intellij.ui.components.ActionLink(t("settings.ui.createBaseFile")) { createBaseFile() })
      .addComponentFillVertically(JPanel(), 0)
      .panel.apply { border = JBUI.Borders.empty(8) }
  }

  /** Grey hint under a control: one place, so wording and colour cannot drift between pages. */
  private fun hint(text: String) = JBLabel("<html>$text</html>").apply {
    foreground = com.intellij.ui.JBColor.GRAY
    font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
  }

  private fun languageCombo(): com.intellij.openapi.ui.ComboBox<String> =
    com.intellij.openapi.ui.ComboBox(VibeI18n.available().toTypedArray()).also {
      it.item = VibeI18n.activeCode()
      language = it
    }

  /**
   * Writes the base catalogue out as a file the user may edit.
   *
   * Not seeded automatically on purpose: a file always beats the binary, so an unasked-for copy
   * would freeze today's wording — a typo fixed in a later version would stay covered by it.
   */
  private fun createBaseFile() {
    val target = VibeI18n.langDir().resolve(VibeI18n.BASE_LANGUAGE + ".json")
    val message = when {
      java.nio.file.Files.exists(target) -> t("settings.ui.baseFileExists", "path" to target)
      else -> runCatching {
        java.nio.file.Files.createDirectories(target.parent)
        val text = javaClass.getResourceAsStream("/lang/base.json")?.bufferedReader()?.readText()
                   ?: error(t("lang.error.notWritable", "path" to target))
        java.nio.file.Files.writeString(target, text)
        t("settings.ui.baseFileCreated", "path" to target)
      }.getOrElse { t("settings.ui.baseFileFailed", "reason" to it.message) }
    }
    com.intellij.openapi.ui.Messages.showInfoMessage(message, t("settings.ui.title"))
  }

  override fun isModified(): Boolean =
    (thickness?.number ?: currentThickness()) != currentThickness() ||
    (promo?.isSelected ?: VibePromoSettings.isEnabled()) != VibePromoSettings.isEnabled() ||
    (language?.item ?: VibeI18n.activeCode()) != VibeI18n.activeCode()

  override fun apply() {
    language?.let { if (it.item != VibeI18n.activeCode()) VibeI18n.setLanguage(it.item) }
    promo?.let {
      if (it.isSelected != VibePromoSettings.isEnabled()) {
        VibePromoSettings.setEnabled(it.isSelected)
        // A banner already painted in an open editor survives the flag — ask for a repaint,
        // same reasoning as the scrollbars below: a settings page must not need a restart.
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
          com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications()
        }
      }
    }
    val value = thickness?.number ?: return
    Registry.get(KEY).setValue(value)
    // Existing scrollbars keep the UI they were built with — rebuild them so the change is visible
    // now, not after a restart.
    VibeScroll.refreshAllScrollBars()
  }

  override fun reset() {
    thickness?.value = currentThickness()
    promo?.isSelected = VibePromoSettings.isEnabled()
    language?.item = VibeI18n.activeCode()
  }

  private fun currentThickness(): Int = Registry.intValue(KEY, DEFAULT_THICKNESS)

  private companion object {
    const val KEY = "vibe.scrollbar.thickness"
    const val DEFAULT_THICKNESS = 4
    const val MIN_THICKNESS = 0
    const val MAX_THICKNESS = 14
  }
}
