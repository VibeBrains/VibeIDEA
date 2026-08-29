// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignMarkupRulesTest {
  private fun doc(vararg elements: ElementSnapshot) =
    DocumentSnapshot(viewportWidthPx = 1280.0, viewportHeightPx = 800.0, elements = elements.toList())

  private fun field(
    accessibleName: String = "", placeholder: Boolean = false, type: String = "text",
    required: Boolean = false, invalid: Boolean = false, describedBy: String = "",
  ) = ElementSnapshot(
    selector = "input", tag = "input", isFormField = true, inputType = type,
    accessibleName = accessibleName, hasPlaceholder = placeholder, isRequiredField = required,
    ariaInvalid = invalid, describedByText = describedBy,
  )

  @Test
  fun `an icon button without a name is reported, with a name is not`() {
    val nameless = ElementSnapshot(selector = "button", tag = "button", interactive = true, svgShapeCount = 3)
    assertEquals(1, DesignMarkupRules.iconButtons(doc(nameless)).size)
    assertTrue(DesignMarkupRules.iconButtons(doc(nameless.copy(accessibleName = "Закрыть"))).isEmpty())
  }

  @Test
  fun `a button with visible text is not an icon button`() {
    val labelled = ElementSnapshot(selector = "button", tag = "button", text = "Сохранить", interactive = true)
    assertTrue(DesignMarkupRules.iconButtons(doc(labelled)).isEmpty())
  }

  @Test
  fun `an interactive element with neither text nor icon is not accused`() {
    // An empty layout box is not a button a reader will ever meet.
    val empty = ElementSnapshot(selector = "div", tag = "div", interactive = true)
    assertTrue(DesignMarkupRules.iconButtons(doc(empty)).isEmpty())
  }

  @Test
  fun `a placeholder standing in for a label is reported`() {
    assertEquals(1, DesignMarkupRules.placeholderAsLabel(doc(field(placeholder = true))).size)
    assertTrue(DesignMarkupRules.placeholderAsLabel(doc(field(accessibleName = "Почта", placeholder = true))).isEmpty())
    assertTrue(DesignMarkupRules.placeholderAsLabel(doc(field(placeholder = true, type = "hidden"))).isEmpty())
  }

  @Test
  fun `a missing alt attribute is a defect, an empty one is a decision`() {
    val noAlt = ElementSnapshot(selector = "img", tag = "img", imgSrc = "/a.png", hasAltAttribute = false)
    assertEquals(1, DesignMarkupRules.imagesWithoutAlt(doc(noAlt)).size)
    assertTrue(DesignMarkupRules.imagesWithoutAlt(doc(noAlt.copy(hasAltAttribute = true))).isEmpty(),
               "alt=\"\" — законное «картинка декоративная»")
  }

  @Test
  fun `an invalid field without an explanation is reported`() {
    assertEquals(1, DesignMarkupRules.errorsNotLinked(doc(field(invalid = true))).size)
    assertTrue(DesignMarkupRules.errorsNotLinked(doc(field(invalid = true, describedBy = "Неверный формат почты"))).isEmpty())
    assertTrue(DesignMarkupRules.errorsNotLinked(doc(field())).isEmpty())
  }

  @Test
  fun `required declared by an asterisk only is reported`() {
    assertEquals(1, DesignMarkupRules.requiredOnlyVisual(doc(field(accessibleName = "Почта *"))).size)
    assertTrue(DesignMarkupRules.requiredOnlyVisual(doc(field(accessibleName = "Почта *", required = true))).isEmpty())
    assertTrue(DesignMarkupRules.requiredOnlyVisual(doc(field(accessibleName = "Почта"))).isEmpty())
  }
}
