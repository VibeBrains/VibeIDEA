// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

/**
 * What a measured page looks like to the rules — the contract between the page and the judgement.
 *
 * The page collects, the rules judge; this file holds no opinion of its own. Every field is a value
 * the browser ACTUALLY computed, which is the whole point: a finding can then be argued with by
 * re-measuring, not by taste. Styles say what was written, a snapshot says what won.
 */
enum class Viewport { DESKTOP, MOBILE }

/** Quality floor versus taste — the split that decides whether a finding may block a turn. */
enum class RuleClass {
  /** A defect at any taste: unreadable contrast, an untappable target, clipped content. */
  FLOOR,

  /** Reads as machine-made. A project may declare it its own identity, with a stated reason. */
  STYLE,
}

enum class Severity { ERROR, WARNING, HINT }

data class Rgb(val r: Int, val g: Int, val b: Int)

data class Box(val top: Double, val right: Double, val bottom: Double, val left: Double)

/**
 * One element as the page computed it. Only what a rule actually needs — a snapshot of a page is
 * carried over a bridge, and every field costs bytes on every measurement.
 */
data class ElementSnapshot(
  /** Stable pointer back to the element, e.g. `main > section:nth-child(2) > h1`. */
  val selector: String,
  /**
   * Index of the nearest ancestor present in the snapshot; `-1` when it is not in the sample.
   *
   * Kinship is stored as a NUMBER because selectors cannot restore it: an ancestor may be recorded
   * from `body` and a descendant from another node, with no prefix in common. The rule that
   * compared strings declared ordinary nesting an occluding layer (VibeIDE's lesson, carried over).
   */
  val parentId: Int = -1,
  val tag: String,
  val text: String = "",
  val classes: List<String> = emptyList(),
  val childTags: List<String> = emptyList(),
  val fontSizePx: Double = 0.0,
  val lineHeightPx: Double = 0.0,
  val letterSpacingPx: Double = 0.0,
  val fontFamily: String = "",
  val fontWeight: Int = 400,
  val fontStyle: String = "normal",
  val textTransform: String = "none",
  val color: Rgb = Rgb(0, 0, 0),
  /** Effective background behind the element (the collector walks up through transparent parents). */
  val backgroundColor: Rgb = Rgb(255, 255, 255),
  /** Alpha of the element's OWN background — an opaque layer can occlude what is under it. */
  val ownBackgroundAlpha: Double = 0.0,
  val backgroundImage: String = "",
  val backgroundClip: String = "",
  val boxShadow: String = "",
  /** `blur(…)` here is the glassmorphism tell. */
  val backdropFilter: String = "",
  val borderRadiusPx: Double = 0.0,
  val animationName: String = "",
  val animationTimingFunction: String = "",
  val animationDurationMs: Double = 0.0,
  val transitionProperty: String = "",
  val position: String = "static",
  val zIndex: Int = 0,
  val overflowX: String = "visible",
  val overflowY: String = "visible",
  val widthPx: Double = 0.0,
  val heightPx: Double = 0.0,
  val leftPx: Double = 0.0,
  val topPx: Double = 0.0,
  val scrollWidthPx: Double = 0.0,
  val clientWidthPx: Double = 0.0,
  val paddingPx: Box = Box(0.0, 0.0, 0.0, 0.0),
  val imgSrc: String = "",
  /** 0 for an `img` whose bitmap never arrived — a broken image looks fine in the markup. */
  val imgNaturalWidthPx: Double = 0.0,
  val svgShapeCount: Int = 0,
  /**
   * How the text actually broke into lines. Cannot be derived from the source: it depends on the
   * font, the box and the hyphenation the browser chose. Only the page knows, so the page counts.
   */
  val textLineCount: Int = 0,
  val linesEndingWithShortWord: Int = 0,
  val lastLineWordCount: Int = 0,
  val interactive: Boolean = false,

  // --- states: not derivable from a resting snapshot, read from the stylesheets ---
  val outlineStyle: String = "none",
  val outlineWidthPx: Double = 0.0,
  val hasFocusRule: Boolean = false,
  val hasHoverRule: Boolean = false,
  val disabled: Boolean = false,
  /**
   * Some stylesheets could not be read (cross-origin). Then "no rule" means "could not look", and
   * the state rules must stay silent rather than accuse.
   */
  val styleRulesUnreadable: Boolean = false,

  // --- markup: what a screen reader hears, none of which is visible on a screenshot ---
  val accessibleName: String = "",
  val isFormField: Boolean = false,
  val inputType: String = "",
  val hasPlaceholder: Boolean = false,
  /**
   * `alt` is PRESENT. Different from an empty value on purpose: `alt=""` is a legitimate "this
   * image is decorative", while a missing attribute makes the reader announce a file name.
   */
  val hasAltAttribute: Boolean = false,
  val ariaInvalid: Boolean = false,
  /**
   * Text the field points at via `aria-describedby`, already resolved by the page. Empty means both
   * "no link" and "the link goes nowhere" — for a listener the two are the same thing.
   */
  val describedByText: String = "",
  val isRequiredField: Boolean = false,
)

data class HeadingSnapshot(val tag: String, val text: String, val fontSizePx: Double)

data class DocumentSnapshot(
  val url: String = "",
  val viewportWidthPx: Double,
  val viewportHeightPx: Double,
  /** A finding is only true for the width it was measured at. */
  val viewport: Viewport = Viewport.DESKTOP,
  val documentScrollWidthPx: Double = 0.0,
  val elements: List<ElementSnapshot> = emptyList(),
  val headings: List<HeadingSnapshot> = emptyList(),
  /**
   * What the page says about itself, or null when the collector did not report it.
   *
   * NULL matters: «не собирали» and «пусто» are different statements, and a findability rule that
   * fires on a snapshot which never carried metadata accuses a page of missing a title it was never
   * asked about — the same discipline every other rule here follows.
   */
  val meta: PageMeta? = null,
)

/** Head metadata, as read from the page rather than from the source: what shipped is what counts. */
data class PageMeta(
  val title: String = "",
  val description: String = "",
  val lang: String = "",
  val viewportContent: String = "",
  val canonical: String = "",
  val h1Count: Int = 0,
  val robots: String = "",
  val ogTitle: String = "",
  val faviconHref: String = "",
  val charset: String = "",
)

/**
 * One finding. [ruleClass] is stamped by the catalogue, not by the rule: a rule allowed to name its
 * own class could disagree with the catalogue the project reads when it accepts a drift.
 */
data class Finding(
  val rule: String,
  val severity: Severity,
  val message: String,
  /** Why it hurts the reader — one sentence, no lecturing. */
  val why: String,
  val selector: String,
  /** The measured value that triggered the rule, so it can be argued with by number. */
  val evidence: String,
  val ruleClass: RuleClass = RuleClass.STYLE,
  val viewport: Viewport = Viewport.DESKTOP,
  /** Set when the project's design system accepted this drift — carries the stated reason. */
  val acceptedReason: String? = null,
)

/** Colour maths shared by every category that judges readability. */
object DesignColor {
  /** Relative luminance, WCAG 2.x. */
  fun luminance(color: Rgb): Double {
    fun channel(value: Int): Double {
      val c = value / 255.0
      return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b)
  }

  /** Contrast ratio 1..21, WCAG 2.x. */
  fun contrast(foreground: Rgb, background: Rgb): Double {
    val a = luminance(foreground)
    val b = luminance(background)
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05) / (darker + 0.05)
  }

  /**
   * Large text by WCAG: 24px, or 18.66px when bold. The threshold drops to 3:1 there, and applying
   * the small-text rule to a headline is the fastest way to teach people to ignore the detector.
   */
  fun isLargeText(fontSizePx: Double, fontWeight: Int): Boolean =
    fontSizePx >= 24.0 || (fontWeight >= 700 && fontSizePx >= 18.66)

  /** Hue in degrees, 0..360; used by the "everything is purple" tell. */
  fun hue(color: Rgb): Double {
    val r = color.r / 255.0
    val g = color.g / 255.0
    val b = color.b / 255.0
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta < 1e-6) return 0.0
    val h = when (max) {
      r -> 60 * (((g - b) / delta) % 6)
      g -> 60 * (((b - r) / delta) + 2)
      else -> 60 * (((r - g) / delta) + 4)
    }
    return if (h < 0) h + 360 else h
  }

  fun saturation(color: Rgb): Double {
    val r = color.r / 255.0
    val g = color.g / 255.0
    val b = color.b / 255.0
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    if (max <= 1e-6) return 0.0
    return (max - min) / max
  }
}
