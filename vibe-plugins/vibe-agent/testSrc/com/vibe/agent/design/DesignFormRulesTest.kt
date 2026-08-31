// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignFormRulesTest {
  private fun field(
    selector: String = "input#email",
    name: String = "email",
    label: String = "Почта",
    hasLabel: Boolean = true,
    type: String = "email",
    inputMode: String = "",
    autocomplete: String = "email",
    width: Double = 320.0,
    required: Boolean = false,
    disabled: Boolean = false,
    readOnly: Boolean = false,
    cursor: String = "text",
    invalid: Boolean = false,
    describedBy: String = "",
    placeholder: String = "",
    insideForm: Boolean = true,
  ) = ElementSnapshot(
    selector = selector, tag = "input", isFormField = true, fieldName = name, accessibleName = label,
    hasLabelElement = hasLabel, inputType = type, inputMode = inputMode, autocompleteAttr = autocomplete,
    widthPx = width, heightPx = 36.0, isRequiredField = required, disabled = disabled, readOnly = readOnly,
    cursorStyle = cursor, ariaInvalid = invalid, describedByText = describedBy,
    placeholderText = placeholder, hasPlaceholder = placeholder.isNotBlank(), insideForm = insideForm,
  )

  private fun page(vararg elements: ElementSnapshot) = DocumentSnapshot(
    viewportWidthPx = 1280.0, viewportHeightPx = 800.0, elements = elements.toList(),
  )

  private fun rules(vararg elements: ElementSnapshot) = DesignFormRules.all(page(*elements)).map { it.rule }

  @Test
  fun `правильно собранное поле не даёт ни одной находки`() {
    assertEquals(emptyList(), rules(field()))
  }

  @Test
  fun `поле без подписи — находка, а aria-label её снимает`() {
    assertTrue(DesignRuleCatalog.FIELD_WITHOUT_LABEL in rules(field(label = "", hasLabel = false)))
    assertTrue(DesignRuleCatalog.FIELD_WITHOUT_LABEL !in rules(field(label = "Почта", hasLabel = false)))
  }

  @Test
  fun `скрытое поле и кнопка не форма в человеческом смысле`() {
    assertEquals(emptyList(), rules(field(type = "hidden", label = "", hasLabel = false, name = "", autocomplete = "")))
    assertEquals(emptyList(), rules(field(type = "submit", label = "", hasLabel = false, name = "", autocomplete = "")))
  }

  @Test
  fun `знакомое браузеру значение без autocomplete`() {
    val found = DesignFormRules.autocomplete(page(field(autocomplete = ""))).single()
    assertEquals(DesignRuleCatalog.AUTOCOMPLETE_MISSING, found.rule)
    assertTrue(DesignFormRules.autocomplete(page(field(autocomplete = "email"))).isEmpty())
    // Незнакомое поле не трогаем: гадать, что человек вводит в «comment», не наше дело.
    assertTrue(DesignFormRules.autocomplete(page(field(name = "comment", label = "Комментарий", type = "text", autocomplete = ""))).isEmpty())
  }

  @Test
  fun `телефон текстовым полем — на телефоне это чужая клавиатура`() {
    val phone = field(name = "phone", label = "Телефон", type = "text", autocomplete = "tel")
    assertTrue(DesignRuleCatalog.INPUT_TYPE_GENERIC in rules(phone))
    assertTrue(DesignRuleCatalog.INPUT_TYPE_GENERIC !in rules(phone.copy(inputType = "tel")))
    // inputmode выбрали руками — клавиатура уже назначена, придираться не к чему.
    assertTrue(DesignRuleCatalog.INPUT_TYPE_GENERIC !in rules(phone.copy(inputMode = "tel")))
  }

  @Test
  fun `ошибка без текста и обязательное заблокированное поле`() {
    assertTrue(DesignRuleCatalog.ERROR_WITHOUT_EXPLANATION in rules(field(invalid = true)))
    assertTrue(DesignRuleCatalog.ERROR_WITHOUT_EXPLANATION !in rules(field(invalid = true, describedBy = "Неверный формат")))
    assertTrue(DesignRuleCatalog.REQUIRED_AND_DISABLED in
               DesignFormRules.requiredDisabled(page(field(required = true, disabled = true))).map { it.rule })
  }

  @Test
  fun `только для чтения, но с текстовым курсором`() {
    assertTrue(DesignRuleCatalog.READONLY_LOOKS_EDITABLE in rules(field(readOnly = true)))
    assertTrue(DesignRuleCatalog.READONLY_LOOKS_EDITABLE !in rules(field(readOnly = true, cursor = "default")))
  }

  @Test
  fun `узкое поле, поле без name и поле вне формы`() {
    assertTrue(DesignRuleCatalog.FIELD_TOO_NARROW in rules(field(width = 80.0)))
    // Нулевая ширина — элемент не разложен, а не узкий.
    assertTrue(DesignRuleCatalog.FIELD_TOO_NARROW !in rules(field(width = 0.0)))
    assertTrue(DesignRuleCatalog.FIELD_WITHOUT_NAME in rules(field(name = "", autocomplete = "email")))
    assertTrue(DesignRuleCatalog.FIELD_OUTSIDE_FORM in rules(field(insideForm = false)))
  }

  @Test
  fun `подсказка, повторяющая подпись`() {
    assertTrue(DesignRuleCatalog.PLACEHOLDER_DUPLICATES_LABEL in rules(field(placeholder = "Почта")))
    assertTrue(DesignRuleCatalog.PLACEHOLDER_DUPLICATES_LABEL !in rules(field(placeholder = "name@example.com")))
  }

  @Test
  fun `все выданные правила есть в каталоге`() {
    val produced = rules(field(label = "", hasLabel = false, name = "", autocomplete = "", width = 40.0,
                               readOnly = true, invalid = true, insideForm = false)).toSet()
    assertTrue(DesignRuleCatalog.ALL.containsAll(produced), "вне каталога: " + (produced - DesignRuleCatalog.ALL.toSet()))
  }
}
