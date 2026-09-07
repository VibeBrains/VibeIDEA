// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Форма, которую попросил агент.
 *
 * Собирается из описания, а не из нашего представления о вопросе: агент присылает JSON Schema, и
 * поля рисуются по ней — список для перечисления, галочка для булева, поле ввода для остального.
 * Подписи приходят от агента и НЕ переводятся: это его текст, а не наш интерфейс; наши только
 * кнопки и пояснение.
 */
class ElicitationDialog(
  project: Project,
  private val request: Elicitation.Request,
) : DialogWrapper(project) {
  private val editors = LinkedHashMap<String, () -> String>()
  private val components = LinkedHashMap<Elicitation.Field, JComponent>()

  init {
    title = t("elicit.title")
    setOKButtonText(t("elicit.send"))
    setCancelButtonText(t("elicit.decline"))
    init()
  }

  override fun createCenterPanel(): JComponent {
    val builder = FormBuilder.createFormBuilder()
    request.message?.takeIf { it.isNotBlank() }?.let {
      builder.addComponent(JBLabel("<html>" + it + "</html>"))
    }
    for (field in request.fields) {
      val component: JComponent = when (field.kind) {
        Elicitation.Field.Kind.ENUM -> ComboBox(field.options.toTypedArray()).apply {
          field.default?.let { selectedItem = it }
          editors[field.name] = { selectedItem?.toString().orEmpty() }
        }
        Elicitation.Field.Kind.BOOLEAN -> JBCheckBox("", field.default?.equals("true", ignoreCase = true) ?: false).apply {
          editors[field.name] = { isSelected.toString() }
        }
        else -> JBTextField(field.default.orEmpty(), 30).apply {
          editors[field.name] = { text.trim() }
        }
      }
      components[field] = component
      // Звёздочка у обязательного: иначе «отправить» молча ничего не делает, и это читается как
      // сломанная кнопка.
      val label = field.title + (if (field.required) " *" else "")
      builder.addLabeledComponent(label, component)
      field.description?.takeIf { it.isNotBlank() }?.let {
        builder.addComponent(JBLabel("<html>" + it + "</html>").apply { foreground = com.intellij.ui.JBColor.GRAY })
      }
    }
    return builder.panel.apply {
      border = JBUI.Borders.empty(8)
      preferredSize = Dimension(460, minOf(120 + request.fields.size * 60, 520))
    }
  }

  /** Значения полей, как их ввёл человек. */
  fun values(): Map<String, String> = editors.mapValues { (_, read) -> read() }

  override fun doValidate(): ValidationInfo? {
    val missing = Elicitation.missing(request.fields, values())
    if (missing.isEmpty()) return null
    val field = missing.first()
    return ValidationInfo(t("elicit.required", "field" to field.title), components[field])
  }
}
