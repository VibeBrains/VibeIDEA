// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Forms: the part of an interface where a defect costs the most.
 *
 * Everywhere else a mistake costs a scroll or a click back. In a form it costs the data the person
 * has already typed, and the second attempt is the one after which they leave. So the rules here
 * are about whether the form can be FILLED IN — not about how it looks.
 *
 * Every rule stays silent on a field it cannot judge: a hidden input, a button, a field the
 * collector could not measure. A form checker that accuses on a guess is one people disable, and a
 * disabled checker protects nothing.
 */
object DesignFormRules {
  /** Below this a field shows a few characters at a time and the value scrolls out of sight. */
  const val MIN_FIELD_WIDTH_PX = 120.0

  /** Field types that carry no user-typed value and are not forms in the human sense. */
  private val NON_FIELD_TYPES = setOf("hidden", "submit", "button", "reset", "image")

  // The name patterns live in DesignPhrases: they are detection data about somebody else's page,
  // and a translated search pattern searches for the wrong thing.
  private val AUTOFILLABLE = DesignPhrases.AUTOFILLABLE
  private val TYPED_INPUT = DesignPhrases.TYPED_INPUT

  fun all(doc: DocumentSnapshot): List<Finding> =
    labels(doc) + autocomplete(doc) + inputTypes(doc) + errorText(doc) + requiredDisabled(doc) +
    readOnlyLook(doc) + narrow(doc) + unnamed(doc) + duplicatePlaceholder(doc) + outsideForm(doc)

  // --- can it be filled in at all ---

  /** A field nobody named: an invisible label is the defect a screen reader hits, a missing one hits everybody. */
  fun labels(doc: DocumentSnapshot): List<Finding> = fields(doc).mapNotNull { element ->
    if (element.accessibleName.isNotBlank() || element.hasLabelElement) return@mapNotNull null
    finding(DesignRuleCatalog.FIELD_WITHOUT_LABEL, Severity.ERROR, element, doc,
            message = t("design.rule.fieldLabel.message"),
            why = t("design.rule.fieldLabel.why"),
            evidence = element.selector)
  }

  /** A required field that cannot be filled: the form can never be submitted, and nothing says why. */
  fun requiredDisabled(doc: DocumentSnapshot): List<Finding> = fields(doc, skipDisabled = false).mapNotNull { element ->
    if (!element.isRequiredField || !element.disabled) return@mapNotNull null
    finding(DesignRuleCatalog.REQUIRED_AND_DISABLED, Severity.ERROR, element, doc,
            message = t("design.rule.requiredDisabled.message"),
            why = t("design.rule.requiredDisabled.why"),
            evidence = element.selector)
  }

  /** Marked invalid with no explanation: a red border says «не так», never «что именно». */
  fun errorText(doc: DocumentSnapshot): List<Finding> = fields(doc).mapNotNull { element ->
    if (!element.ariaInvalid || element.describedByText.isNotBlank()) return@mapNotNull null
    finding(DesignRuleCatalog.ERROR_WITHOUT_EXPLANATION, Severity.ERROR, element, doc,
            message = t("design.rule.errorText.message"),
            why = t("design.rule.errorText.why"),
            evidence = element.selector)
  }

  /**
   * A read-only field that still shows a text caret.
   *
   * The caret is a promise: it says «печатайте здесь». Typing then does nothing, and the person
   * looks for their own mistake instead of the interface's.
   */
  fun readOnlyLook(doc: DocumentSnapshot): List<Finding> = fields(doc).mapNotNull { element ->
    if (!element.readOnly || element.disabled) return@mapNotNull null
    if (element.cursorStyle != "text") return@mapNotNull null
    finding(DesignRuleCatalog.READONLY_LOOKS_EDITABLE, Severity.WARNING, element, doc,
            message = t("design.rule.readOnlyLook.message"),
            why = t("design.rule.readOnlyLook.why"),
            evidence = element.cursorStyle)
  }

  // --- how much typing it costs ---

  /** A value the browser already knows, asked for by hand because no autocomplete token was set. */
  fun autocomplete(doc: DocumentSnapshot): List<Finding> = fields(doc).mapNotNull { element ->
    if (element.autocompleteAttr.isNotBlank()) return@mapNotNull null
    val haystack = element.fieldName + " " + element.accessibleName + " " + element.inputType
    val token = AUTOFILLABLE.entries.firstOrNull { it.value.containsMatchIn(haystack) }?.key
      ?: return@mapNotNull null
    finding(DesignRuleCatalog.AUTOCOMPLETE_MISSING, Severity.WARNING, element, doc,
            message = t("design.rule.autocomplete.message", "token" to token),
            why = t("design.rule.autocomplete.why"),
            evidence = element.fieldName.ifBlank { element.accessibleName })
  }

  /**
   * An email or phone field left as plain text.
   *
   * On a phone the type picks the keyboard. A text keyboard for a phone number means the digits are
   * two taps away each, and that is measured in abandoned forms rather than in taste.
   */
  fun inputTypes(doc: DocumentSnapshot): List<Finding> = fields(doc).mapNotNull { element ->
    if (element.tag != "input") return@mapNotNull null
    if (element.inputType.isNotBlank() && element.inputType != "text") return@mapNotNull null
    if (element.inputMode.isNotBlank()) return@mapNotNull null   // the keyboard was chosen explicitly
    val haystack = element.fieldName + " " + element.accessibleName
    val expected = TYPED_INPUT.entries.firstOrNull { it.value.containsMatchIn(haystack) }?.key
      ?: return@mapNotNull null
    finding(DesignRuleCatalog.INPUT_TYPE_GENERIC, Severity.ERROR, element, doc,
            message = t("design.rule.inputType.message", "type" to expected),
            why = t("design.rule.inputType.why"),
            evidence = element.fieldName.ifBlank { element.accessibleName })
  }

  /** A field so narrow the value scrolls while it is being typed. */
  fun narrow(doc: DocumentSnapshot): List<Finding> = fields(doc).mapNotNull { element ->
    // A zero box is an element that is not laid out, not a narrow one.
    if (element.widthPx <= 0 || element.widthPx >= MIN_FIELD_WIDTH_PX) return@mapNotNull null
    if (element.inputType == "checkbox" || element.inputType == "radio") return@mapNotNull null
    finding(DesignRuleCatalog.FIELD_TOO_NARROW, Severity.WARNING, element, doc,
            message = t("design.rule.fieldNarrow.message",
                        "width" to DesignFloorRules.format(element.widthPx), "min" to DesignFloorRules.format(MIN_FIELD_WIDTH_PX)),
            why = t("design.rule.fieldNarrow.why"),
            evidence = DesignFloorRules.format(element.widthPx) + "px")
  }

  /** No `name`: the browser cannot remember the value, and a plain form submit drops it. */
  fun unnamed(doc: DocumentSnapshot): List<Finding> = fields(doc).mapNotNull { element ->
    if (element.fieldName.isNotBlank() || element.readOnly) return@mapNotNull null
    finding(DesignRuleCatalog.FIELD_WITHOUT_NAME, Severity.WARNING, element, doc,
            message = t("design.rule.fieldName.message"),
            why = t("design.rule.fieldName.why"),
            evidence = element.selector)
  }

  /** Label and placeholder saying the same word twice — noise where the hint could have been. */
  fun duplicatePlaceholder(doc: DocumentSnapshot): List<Finding> = fields(doc).mapNotNull { element ->
    val placeholder = element.placeholderText.trim()
    if (placeholder.isBlank() || element.accessibleName.isBlank()) return@mapNotNull null
    if (!placeholder.equals(element.accessibleName.trim(), ignoreCase = true)) return@mapNotNull null
    finding(DesignRuleCatalog.PLACEHOLDER_DUPLICATES_LABEL, Severity.HINT, element, doc,
            message = t("design.rule.placeholderDuplicate.message"),
            why = t("design.rule.placeholderDuplicate.why"),
            evidence = placeholder.take(60))
  }

  /** A field outside any `<form>`: Enter submits nothing, and the browser offers nothing to save. */
  fun outsideForm(doc: DocumentSnapshot): List<Finding> = fields(doc).mapNotNull { element ->
    if (element.insideForm) return@mapNotNull null
    finding(DesignRuleCatalog.FIELD_OUTSIDE_FORM, Severity.HINT, element, doc,
            message = t("design.rule.outsideForm.message"),
            why = t("design.rule.outsideForm.why"),
            evidence = element.selector)
  }

  // --- helpers ---

  private fun fields(doc: DocumentSnapshot, skipDisabled: Boolean = true): List<ElementSnapshot> =
    doc.elements.filter {
      it.isFormField && it.inputType !in NON_FIELD_TYPES && (!skipDisabled || !it.disabled)
    }

  private fun finding(
    rule: String, severity: Severity, element: ElementSnapshot, doc: DocumentSnapshot,
    message: String, why: String, evidence: String,
  ) = DesignFloorRules.finding(rule, severity, element, doc, message, why, evidence)
}
