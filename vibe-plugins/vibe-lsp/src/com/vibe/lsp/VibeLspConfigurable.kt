// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.vibe.agent.i18n.VibeI18n.t
import javax.swing.JComponent

/**
 * Settings page: where each language server actually is.
 *
 * Nothing here has to be filled in — an empty field means the usual resolution, which starts with
 * the person's own PATH and ends with the copy we ship. The page exists for the case the ordinary
 * rule cannot express: a server that lives in `vendor/bin`, in a shared container folder, or in a
 * checkout built from source.
 */
class VibeLspConfigurable : Configurable {
  private val fields = LinkedHashMap<String, TextFieldWithBrowseButton>()

  override fun getDisplayName(): String = t("settings.lsp.title")

  override fun createComponent(): JComponent {
    val builder = FormBuilder.createFormBuilder()
    for (spec in LspDoctor.ALL) {
      if (spec.id !in ServerPaths.OVERRIDABLE) continue
      val field = TextFieldWithBrowseButton().apply {
        text = ServerPaths.get(spec.id)
        addBrowseFolderListener(
          null,
          FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle(t("settings.lsp.choose", "server" to spec.displayName)),
        )
      }
      fields[spec.id] = field
      builder.addLabeledComponent(spec.displayName, field)
    }
    builder.addComponent(JBLabel("<html>" + t("settings.lsp.hint") + "</html>").apply {
      foreground = com.intellij.ui.JBColor.GRAY
    })
    return builder.panel
  }

  override fun isModified(): Boolean =
    fields.any { (id, field) -> field.text.trim() != ServerPaths.get(id) }

  override fun apply() {
    fields.forEach { (id, field) -> ServerPaths.set(id, field.text) }
  }

  override fun reset() {
    fields.forEach { (id, field) -> field.text = ServerPaths.get(id) }
  }
}
