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

  private val FLOOR = setOf(
    CONTRAST_TEXT, TEXT_TOO_SMALL, TAP_TARGET_TOO_SMALL, CONTENT_CLIPPED, ELEMENT_OCCLUDED,
    PAGE_WIDER_THAN_VIEWPORT, BROKEN_IMAGE, FOCUS_RING_REMOVED, DISABLED_LOOKS_ENABLED,
    ICON_BUTTON_WITHOUT_NAME, PLACEHOLDER_AS_LABEL, IMAGE_WITHOUT_ALT, ERROR_NOT_LINKED_TO_FIELD,
    REQUIRED_ONLY_VISUAL, HEADING_LEVEL_SKIPPED,
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
  )
}
