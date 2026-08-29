// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

/**
 * Defects a screenshot cannot show: what the page sounds like to a screen reader.
 *
 * An icon button looks perfect and says nothing. A field with a placeholder instead of a label
 * looks tidy right up to the first character typed, after which the filled form is a row of
 * nameless rectangles. None of this is visible to the eye, which is precisely why it needs a
 * detector rather than a review.
 */
object DesignMarkupRules {
  /** Input types that are not labelled the usual way: hidden carries nothing, buttons name themselves. */
  private val SELF_NAMING_INPUTS = setOf("hidden", "submit", "button", "reset", "image")

  fun all(doc: DocumentSnapshot): List<Finding> =
    iconButtons(doc) + placeholderAsLabel(doc) + imagesWithoutAlt(doc) +
    errorsNotLinked(doc) + requiredOnlyVisual(doc)

  fun iconButtons(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.interactive) return@mapNotNull null
    if (element.text.isNotBlank()) return@mapNotNull null
    // No text and no icon either: an empty layout box, not a button a reader will meet.
    if (element.svgShapeCount == 0 && element.childTags.none { it == "svg" || it == "img" }) return@mapNotNull null
    if (element.accessibleName.isNotBlank()) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.ICON_BUTTON_WITHOUT_NAME, Severity.ERROR, element, doc,
      message = "Кнопка с иконкой без доступного имени",
      why = "Программа чтения произнесёт просто «кнопка» — что она делает, узнать неоткуда.",
      evidence = "нет aria-label, aria-labelledby и текста",
    )
  }

  fun placeholderAsLabel(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.isFormField) return@mapNotNull null
    if (element.inputType.lowercase() in SELF_NAMING_INPUTS) return@mapNotNull null
    if (element.accessibleName.isNotBlank()) return@mapNotNull null
    if (!element.hasPlaceholder) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.PLACEHOLDER_AS_LABEL, Severity.ERROR, element, doc,
      message = "У поля только плейсхолдер вместо подписи",
      why = "Плейсхолдер исчезает при вводе: заполненная форма превращается в набор безымянных прямоугольников.",
      evidence = "type=${element.inputType.ifBlank { "text" }}, подписи нет",
    )
  }

  fun imagesWithoutAlt(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.tag != "img") return@mapNotNull null
    // alt="" is a legitimate «изображение декоративное»; the missing ATTRIBUTE is the defect.
    if (element.hasAltAttribute) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.IMAGE_WITHOUT_ALT, Severity.ERROR, element, doc,
      message = "У изображения нет атрибута alt",
      why = "Программа чтения зачитает имя файла; пустой alt=\"\" — законное «картинка декоративная».",
      evidence = element.imgSrc.take(120).ifBlank { "src не указан" },
    )
  }

  fun errorsNotLinked(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.ariaInvalid) return@mapNotNull null
    // Empty text covers both "no link" and "link points at nothing" — for a listener the same thing.
    if (element.describedByText.isNotBlank()) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.ERROR_NOT_LINKED_TO_FIELD, Severity.ERROR, element, doc,
      message = "Поле помечено ошибочным, но объяснение к нему не привязано",
      why = "Программа чтения скажет «неверное значение» и замолчит: что именно не так — не прозвучит.",
      evidence = "aria-invalid=true, aria-describedby пуст или ведёт в никуда",
    )
  }

  fun requiredOnlyVisual(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.isFormField) return@mapNotNull null
    if (element.isRequiredField) return@mapNotNull null
    // The asterisk convention: required declared by a character in the label instead of an attribute.
    if (!element.accessibleName.contains('*')) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.REQUIRED_ONLY_VISUAL, Severity.WARNING, element, doc,
      message = "Обязательность поля объявлена только звёздочкой в подписи",
      why = "Звёздочка будет зачитана как символ посреди фразы — обязательность на слух не считывается.",
      evidence = "«${element.accessibleName.take(60)}» без атрибута required",
    )
  }
}
