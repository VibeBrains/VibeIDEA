// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

/**
 * The one place that says which rule is a quality floor and which is taste.
 *
 * The split matters more than any single rule. A floor finding may block a turn in strict mode; a
 * taste finding may not, ever — bouncing a model over "the halo is a cliché" turns an opinion into
 * a blocker and produces loops about style. Keeping the classification here, rather than letting
 * each rule name its own class, means a rule cannot disagree with the catalogue the project reads
 * when it accepts a drift.
 */
object DesignRuleCatalog {
  // --- floor: defects at any taste ---
  const val CONTRAST_TEXT = "contrast-text"
  const val TEXT_TOO_SMALL = "text-too-small"
  const val TAP_TARGET_TOO_SMALL = "tap-target-too-small"
  const val CONTENT_CLIPPED = "content-clipped"
  const val ELEMENT_OCCLUDED = "element-occluded"
  const val PAGE_WIDER_THAN_VIEWPORT = "page-wider-than-viewport"
  const val BROKEN_IMAGE = "broken-image"
  const val FOCUS_RING_REMOVED = "focus-ring-removed"
  const val DISABLED_LOOKS_ENABLED = "disabled-looks-enabled"
  const val ICON_BUTTON_WITHOUT_NAME = "icon-button-without-name"
  const val PLACEHOLDER_AS_LABEL = "placeholder-as-label"
  const val IMAGE_WITHOUT_ALT = "image-without-alt"
  const val ERROR_NOT_LINKED_TO_FIELD = "error-not-linked-to-field"
  const val REQUIRED_ONLY_VISUAL = "required-only-visual"
  const val HEADING_LEVEL_SKIPPED = "heading-level-skipped"

  // --- taste: reads as machine-made ---
  const val NO_HOVER_RESPONSE = "no-hover-response"
  const val GRADIENT_TEXT = "gradient-text"
  const val GLOW_INSTEAD_OF_SHADOW = "glow-instead-of-shadow"
  const val GLASSMORPHISM = "glassmorphism"
  const val PURPLE_PALETTE = "purple-palette"
  const val EYEBROW_CHIP = "eyebrow-chip"
  const val CLONED_CARDS = "cloned-cards"
  const val RADIUS_SCALE_DRIFT = "radius-scale-drift"
  const val EXTREME_RADIUS = "extreme-radius"
  const val ANIMATED_LAYOUT_PROPERTY = "animated-layout-property"
  const val OVERSHOOT_ANIMATION = "overshoot-animation"
  const val HANGING_PREPOSITION = "hanging-preposition"
  const val ORPHAN_WORD = "orphan-word"
  const val MARKETING_PROMISE = "marketing-promise"

  // Findability: the only category invisible both on a screenshot and in a preview — a page can be
  // perfect to look at and impossible to find.
  const val TITLE_MISSING = "title-missing"
  const val TITLE_TOO_SHORT = "title-too-short"
  const val TITLE_TOO_LONG = "title-too-long"
  const val H1_MISSING = "h1-missing"
  const val H1_MULTIPLE = "h1-multiple"
  const val DESCRIPTION_MISSING = "description-missing"
  const val DESCRIPTION_TOO_SHORT = "description-too-short"
  const val DESCRIPTION_TOO_LONG = "description-too-long"
  const val LANG_MISSING = "lang-missing"
  const val VIEWPORT_MISSING = "viewport-missing"
  const val VIEWPORT_BLOCKS_ZOOM = "viewport-blocks-zoom"
  const val CANONICAL_MISSING = "canonical-missing"
  const val CANONICAL_RELATIVE = "canonical-relative"
  const val ROBOTS_NOINDEX = "robots-noindex"
  const val CHARSET_NOT_UTF8 = "charset-not-utf8"
  const val FAVICON_MISSING = "favicon-missing"
  const val OG_TITLE_MISSING = "og-title-missing"

  // Rhythm and typography: what makes a page feel wrong before anyone can say why.
  const val LINE_TOO_LONG = "line-too-long"
  const val LINE_HEIGHT_OFF = "line-height-off"
  const val FONT_SCALE_DRIFT = "font-scale-drift"
  const val SPACING_OFF_GRID = "spacing-off-grid"
  const val TOO_MANY_FONTS = "too-many-fonts"
  const val SHOUTING_TEXT = "shouting-text"

  // --- forms: where a defect costs the data already typed ---
  const val FIELD_WITHOUT_LABEL = "field-without-label"
  const val AUTOCOMPLETE_MISSING = "autocomplete-missing"
  const val INPUT_TYPE_GENERIC = "input-type-generic"
  const val ERROR_WITHOUT_EXPLANATION = "error-without-explanation"
  const val REQUIRED_AND_DISABLED = "required-and-disabled"
  const val READONLY_LOOKS_EDITABLE = "readonly-looks-editable"
  const val FIELD_TOO_NARROW = "field-too-narrow"
  const val FIELD_WITHOUT_NAME = "field-without-name"
  const val PLACEHOLDER_DUPLICATES_LABEL = "placeholder-duplicates-label"
  const val FIELD_OUTSIDE_FORM = "field-outside-form"

  // --- motion: judged by feel, so measured in milliseconds ---
  const val ANIMATION_TOO_SLOW = "animation-too-slow"
  const val TRANSITION_TOO_SLOW = "transition-too-slow"
  const val ANIMATION_TOO_FAST = "animation-too-fast"
  const val INFINITE_ANIMATION = "infinite-animation"
  const val NO_REDUCED_MOTION = "no-reduced-motion"
  const val TRANSITION_ALL = "transition-all"
  const val HOVER_WITHOUT_TRANSITION = "hover-without-transition"
  const val LINEAR_EASING = "linear-easing"
  const val NO_PRESS_FEEDBACK = "no-press-feedback"

  // --- colour: counted, because nobody counts it by eye ---
  const val TOO_MANY_TEXT_COLORS = "too-many-text-colors"
  const val TOO_MANY_ACCENT_HUES = "too-many-accent-hues"
  const val PURE_BLACK_ON_WHITE = "pure-black-on-white"
  const val SATURATED_LARGE_AREA = "saturated-large-area"
  const val LINK_BY_COLOR_ONLY = "link-by-color-only"
  const val LINK_SAME_COLOR_AS_TEXT = "link-same-color-as-text"
  const val BORDER_INVISIBLE = "border-invisible"
  const val ICON_LOW_CONTRAST = "icon-low-contrast"
  const val TEXT_ON_IMAGE_WITHOUT_SCRIM = "text-on-image-without-scrim"
  const val FOCUS_RING_LOW_CONTRAST = "focus-ring-low-contrast"

  private val FLOOR = setOf(
    CONTRAST_TEXT, TEXT_TOO_SMALL, TAP_TARGET_TOO_SMALL, CONTENT_CLIPPED, ELEMENT_OCCLUDED,
    PAGE_WIDER_THAN_VIEWPORT, BROKEN_IMAGE, FOCUS_RING_REMOVED, DISABLED_LOOKS_ENABLED,
    ICON_BUTTON_WITHOUT_NAME, PLACEHOLDER_AS_LABEL, IMAGE_WITHOUT_ALT, ERROR_NOT_LINKED_TO_FIELD,
    REQUIRED_ONLY_VISUAL, HEADING_LEVEL_SKIPPED,
    // Floor, not taste: a page without a title, without a language or with zoom blocked is broken
    // for a person, not merely unfashionable.
    TITLE_MISSING, H1_MISSING, LANG_MISSING, VIEWPORT_MISSING, VIEWPORT_BLOCKS_ZOOM, CANONICAL_RELATIVE,
    // A form that cannot be filled in is broken, not unfashionable: an unnamed field, an error
    // without words, a required field that is disabled, a phone box with a text keyboard.
    FIELD_WITHOUT_LABEL, ERROR_WITHOUT_EXPLANATION, REQUIRED_AND_DISABLED, INPUT_TYPE_GENERIC,
    // Motion the system asked to stop is nausea for a person with vestibular disorder, not taste.
    NO_REDUCED_MOTION,
    // Colour as the ONLY signal, and an icon at decoration contrast: these are about people who
    // cannot see the colour at all, which is the opposite of a matter of taste.
    LINK_BY_COLOR_ONLY, LINK_SAME_COLOR_AS_TEXT, ICON_LOW_CONTRAST, FOCUS_RING_LOW_CONTRAST,
  )

  fun classOf(ruleId: String): RuleClass = if (ruleId in FLOOR) RuleClass.FLOOR else RuleClass.STYLE

  fun isFloor(ruleId: String): Boolean = ruleId in FLOOR

  /** Every id the engine can produce — the settings page and the acceptance file check against it. */
  val ALL: List<String> = listOf(
    CONTRAST_TEXT, TEXT_TOO_SMALL, TAP_TARGET_TOO_SMALL, CONTENT_CLIPPED, ELEMENT_OCCLUDED,
    PAGE_WIDER_THAN_VIEWPORT, BROKEN_IMAGE, FOCUS_RING_REMOVED, DISABLED_LOOKS_ENABLED,
    ICON_BUTTON_WITHOUT_NAME, PLACEHOLDER_AS_LABEL, IMAGE_WITHOUT_ALT, ERROR_NOT_LINKED_TO_FIELD,
    REQUIRED_ONLY_VISUAL, HEADING_LEVEL_SKIPPED,
    NO_HOVER_RESPONSE, GRADIENT_TEXT, GLOW_INSTEAD_OF_SHADOW, GLASSMORPHISM, PURPLE_PALETTE,
    EYEBROW_CHIP, CLONED_CARDS, RADIUS_SCALE_DRIFT, EXTREME_RADIUS, ANIMATED_LAYOUT_PROPERTY,
    OVERSHOOT_ANIMATION, HANGING_PREPOSITION, ORPHAN_WORD, MARKETING_PROMISE,
    TITLE_MISSING, TITLE_TOO_SHORT, TITLE_TOO_LONG, H1_MISSING, H1_MULTIPLE,
    DESCRIPTION_MISSING, DESCRIPTION_TOO_SHORT, DESCRIPTION_TOO_LONG, LANG_MISSING,
    VIEWPORT_MISSING, VIEWPORT_BLOCKS_ZOOM, CANONICAL_MISSING, CANONICAL_RELATIVE,
    ROBOTS_NOINDEX, CHARSET_NOT_UTF8, FAVICON_MISSING, OG_TITLE_MISSING,
    LINE_TOO_LONG, LINE_HEIGHT_OFF, FONT_SCALE_DRIFT, SPACING_OFF_GRID, TOO_MANY_FONTS, SHOUTING_TEXT,
    FIELD_WITHOUT_LABEL, AUTOCOMPLETE_MISSING, INPUT_TYPE_GENERIC, ERROR_WITHOUT_EXPLANATION,
    REQUIRED_AND_DISABLED, READONLY_LOOKS_EDITABLE, FIELD_TOO_NARROW, FIELD_WITHOUT_NAME,
    PLACEHOLDER_DUPLICATES_LABEL, FIELD_OUTSIDE_FORM,
    ANIMATION_TOO_SLOW, TRANSITION_TOO_SLOW, ANIMATION_TOO_FAST, INFINITE_ANIMATION,
    NO_REDUCED_MOTION, TRANSITION_ALL, HOVER_WITHOUT_TRANSITION, LINEAR_EASING, NO_PRESS_FEEDBACK,
    TOO_MANY_TEXT_COLORS, TOO_MANY_ACCENT_HUES, PURE_BLACK_ON_WHITE, SATURATED_LARGE_AREA,
    LINK_BY_COLOR_ONLY, LINK_SAME_COLOR_AS_TEXT, BORDER_INVISIBLE, ICON_LOW_CONTRAST,
    TEXT_ON_IMAGE_WITHOUT_SCRIM, FOCUS_RING_LOW_CONTRAST,
  )
}
