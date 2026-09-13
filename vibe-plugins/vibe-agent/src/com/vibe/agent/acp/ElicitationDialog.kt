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
        // The list shows the agent's titles and sends back the wire values they stand for.
        Elicitation.Field.Kind.ENUM -> ComboBox(field.options.map { field.labelOf(it) }.toTypedArray()).apply {
          field.default?.let { selectedItem = field.labelOf(it) }
          editors[field.name] = { field.options.getOrNull(selectedIndex).orEmpty() }
        }
        Elicitation.Field.Kind.MULTI -> {
          val chosen = field.default?.split(Elicitation.MULTI_SEPARATOR).orEmpty().toSet()
          val boxes = field.options.map { value -> value to JBCheckBox(field.labelOf(value), value in chosen) }
          editors[field.name] = { boxes.filter { it.second.isSelected }.joinToString(Elicitation.MULTI_SEPARATOR) { it.first } }
          javax.swing.JPanel(com.intellij.ui.components.panels.VerticalLayout(0)).apply { boxes.forEach { add(it.second) } }
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
    val values = values()
    Elicitation.missing(request.fields, values).firstOrNull()?.let { field ->
      return ValidationInfo(t("elicit.required", "field" to field.title), components[field])
    }
    val invalid = Elicitation.invalid(request.fields, values) ?: return null
    val field = invalid.field.title
    val limit = invalid.limit.orEmpty()
    // One t() per branch, literally: the catalogue gate counts a key as used only where it is called.
    val message = when (invalid.reason) {
      Elicitation.Invalid.Reason.NOT_NUMBER -> t("elicit.invalid.number", "field" to field)
      Elicitation.Invalid.Reason.NOT_INTEGER -> t("elicit.invalid.integer", "field" to field)
      Elicitation.Invalid.Reason.TOO_SHORT -> t("elicit.invalid.minLength", "field" to field, "limit" to limit)
      Elicitation.Invalid.Reason.TOO_LONG -> t("elicit.invalid.maxLength", "field" to field, "limit" to limit)
      Elicitation.Invalid.Reason.PATTERN -> t("elicit.invalid.pattern", "field" to field, "limit" to limit)
      Elicitation.Invalid.Reason.FORMAT -> t("elicit.invalid.format", "field" to field, "limit" to limit)
      Elicitation.Invalid.Reason.BELOW_MINIMUM -> t("elicit.invalid.minimum", "field" to field, "limit" to limit)
      Elicitation.Invalid.Reason.ABOVE_MAXIMUM -> t("elicit.invalid.maximum", "field" to field, "limit" to limit)
      Elicitation.Invalid.Reason.TOO_FEW -> t("elicit.invalid.minItems", "field" to field, "limit" to limit)
      Elicitation.Invalid.Reason.TOO_MANY -> t("elicit.invalid.maxItems", "field" to field, "limit" to limit)
    }
    return ValidationInfo(message, components[invalid.field])
  }
}
