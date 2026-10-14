// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.util.ui.JBUI
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every size of the message layout in one place, in logical pixels
 *
 * The values are VibeIDE's (its chat messages stylesheet): the same chat has to read as the same product in both
 * Only the arithmetic lives here; the components that draw it are in `ChatMessageViews.kt`
 */
internal object ChatLayoutSpec {
  /** The feed is at most this wide for the narrow layout */
  const val NARROW_MAX_WIDTH = 360

  /** The feed is at most this wide for the tight layout */
  const val TIGHT_MAX_WIDTH = 280

  /** Space around the feed's rows, the one the feed panel itself leaves */
  const val FEED_PAD = 6

  /** What a feed that has not been shown yet is taken to be: a row measured at no width at all would be a thousand pixels tall */
  const val DEFAULT_FEED_WIDTH = 500

  /** A reply never gets wider than this, however wide the panel is: a line of prose stays readable */
  const val ASSISTANT_MAX_WIDTH = 720

  const val ASSISTANT_GUTTER_PERCENT = 18
  const val ASSISTANT_RULE = 2
  const val ASSISTANT_RULE_GAP = 10

  const val USER_GUTTER_WIDE_PERCENT = 22
  const val USER_GUTTER_NARROW_PERCENT = 12

  const val USER_RADIUS_TOP_LEFT = 14
  const val USER_RADIUS_TOP_RIGHT = 14
  const val USER_RADIUS_BOTTOM_RIGHT = 4
  const val USER_RADIUS_BOTTOM_LEFT = 14
  const val USER_RADIUS_TIGHT = 8

  const val USER_OUTLINE = 1
  const val USER_BAR = 3

  const val USER_PAD_TOP = 8
  const val USER_PAD_SIDE = 12
  const val USER_PAD_BOTTOM = 6

  /** Gap between the time in the corner and the text above it */
  const val USER_TIME_GAP = 2

  /** Gap between the caption and the text below it, in the tight layout */
  const val USER_CAPTION_GAP = 2

  const val USER_META_FONT = 11f

  /** The icon chips of a message: size, inner padding, how far they stick out of the bubble's corner */
  const val CHIP_SIZE = 18
  const val CHIP_PAD = 2
  const val CHIP_GAP = 2
  const val CHIP_OFFSET = 4
  const val CHIP_RADIUS = 4

  /** Appearing and fading of the chips, milliseconds */
  const val CHIP_FADE_MS = 200

  /** A line that must not wrap needs a little room over its measured width: the wrapper breaks on equality */
  const val TEXT_WIDTH_SLACK = 2

  /** The row's own space around the message: top, sides, bottom */
  const val ROW_PAD_TOP = 4
  const val ROW_PAD_SIDE = 4
  const val ROW_PAD_BOTTOM = 8
}

/** Which of the three layouts the feed width selects */
internal enum class ChatLayoutMode { WIDE, NARROW, TIGHT }

/** Corner radii of the user's bubble, pixels */
internal data class BubbleRadii(val topLeft: Int, val topRight: Int, val bottomRight: Int, val bottomLeft: Int)

/**
 * What the feed width decides about the messages, already in pixels
 *
 * Wide (over 360): the reply sits behind a rule and leaves a gutter on the right, the request is a bubble with its time in the corner
 * Narrow (up to 360): the reply takes the whole width and loses the rule, the bubble's margin shrinks
 * Tight (up to 280): the bubble becomes a full-width plate with a bar on the left, and the time moves into a caption above the text
 */
internal data class ChatMessageLayout(
  val mode: ChatLayoutMode,
  val assistantRule: Int,
  val assistantRuleGap: Int,
  /** Free space the reply keeps on its right, after the width cap has taken its share */
  val assistantRightMargin: Int,
  val userLeftMargin: Int,
  val radii: BubbleRadii,
  /** Outline of the bubble all around, or 0 when the plate has a bar instead */
  val outline: Int,
  /** Bar on the left of the plate, or 0 when the bubble has an outline */
  val bar: Int,
  val padTop: Int,
  val padSide: Int,
  val padBottom: Int,
) {
  /** The bubble takes the whole row instead of fitting its text */
  val bubbleFillsRow: Boolean get() = mode == ChatLayoutMode.TIGHT

  /** Time in the corner of the bubble; the tight layout moves it into the caption */
  val timeInCorner: Boolean get() = mode != ChatLayoutMode.TIGHT

  val captionAbove: Boolean get() = mode == ChatLayoutMode.TIGHT

  /** Space between the bubble's edge and its content, per side */
  val insetLeft: Int get() = padSide + outline + bar
  val insetRight: Int get() = padSide + outline
  val insetTop: Int get() = padTop + outline
  val insetBottom: Int get() = padBottom + outline

  companion object {
    /**
     * [feedWidth] is the visible width of the feed, [scale] the UI scale
     * A width that is not known yet (the panel is not shown) lays out as wide with no margins, and the first real width replaces it
     */
    fun of(feedWidth: Int, scale: Float): ChatMessageLayout {
      fun px(logical: Int) = (logical * scale).roundToInt()
      fun share(percent: Int) = (max(feedWidth, 0) * percent / 100f).roundToInt()
      val mode = modeOf(feedWidth, scale)
      val slack = max(0, feedWidth - 2 * px(ChatLayoutSpec.FEED_PAD) - px(ChatLayoutSpec.ASSISTANT_MAX_WIDTH))
      val tight = mode == ChatLayoutMode.TIGHT
      val radius = px(ChatLayoutSpec.USER_RADIUS_TIGHT)
      return ChatMessageLayout(
        mode = mode,
        assistantRule = if (mode == ChatLayoutMode.WIDE) px(ChatLayoutSpec.ASSISTANT_RULE) else 0,
        assistantRuleGap = if (mode == ChatLayoutMode.WIDE) px(ChatLayoutSpec.ASSISTANT_RULE_GAP) else 0,
        assistantRightMargin = if (mode == ChatLayoutMode.WIDE) max(0, share(ChatLayoutSpec.ASSISTANT_GUTTER_PERCENT) - slack) else 0,
        userLeftMargin = when (mode) {
          ChatLayoutMode.WIDE -> share(ChatLayoutSpec.USER_GUTTER_WIDE_PERCENT)
          ChatLayoutMode.NARROW -> share(ChatLayoutSpec.USER_GUTTER_NARROW_PERCENT)
          ChatLayoutMode.TIGHT -> 0
        },
        radii = if (tight) BubbleRadii(radius, radius, radius, radius)
        else BubbleRadii(px(ChatLayoutSpec.USER_RADIUS_TOP_LEFT), px(ChatLayoutSpec.USER_RADIUS_TOP_RIGHT),
                         px(ChatLayoutSpec.USER_RADIUS_BOTTOM_RIGHT), px(ChatLayoutSpec.USER_RADIUS_BOTTOM_LEFT)),
        outline = if (tight) 0 else px(ChatLayoutSpec.USER_OUTLINE),
        bar = if (tight) px(ChatLayoutSpec.USER_BAR) else 0,
        padTop = px(ChatLayoutSpec.USER_PAD_TOP),
        padSide = px(ChatLayoutSpec.USER_PAD_SIDE),
        padBottom = px(ChatLayoutSpec.USER_PAD_BOTTOM),
      )
    }

    /** The thresholds are inclusive: a feed exactly 360 wide is narrow, exactly 280 is tight */
    fun modeOf(feedWidth: Int, scale: Float): ChatLayoutMode = when {
      feedWidth <= 0 -> ChatLayoutMode.WIDE
      feedWidth <= (ChatLayoutSpec.TIGHT_MAX_WIDTH * scale).roundToInt() -> ChatLayoutMode.TIGHT
      feedWidth <= (ChatLayoutSpec.NARROW_MAX_WIDTH * scale).roundToInt() -> ChatLayoutMode.NARROW
      else -> ChatLayoutMode.WIDE
    }
  }
}

/**
 * The bubble's width: it fits its text, up to what the row leaves it; the plate takes everything
 *
 * [naturalContentWidth] is the width of the longest line without wrapping, [available] the row's width less the bubble's left margin
 */
internal fun bubbleWidth(layout: ChatMessageLayout, naturalContentWidth: Int, available: Int): Int {
  val room = max(available, 0)
  if (layout.bubbleFillsRow) return room
  val wanted = naturalContentWidth + ChatLayoutSpec.TEXT_WIDTH_SLACK + layout.insetLeft + layout.insetRight
  return wanted.coerceIn(0, room)
}

/**
 * The feed's width and the layout it selects, shared by every message of one panel
 *
 * One holder per panel, not a global: two projects have two chats of different widths
 * The rows read [layout] while they lay themselves out, so a new width only has to be told here and the feed revalidated
 */
internal class ChatFeedMetrics(private val scale: () -> Float = { JBUI.scale(1f) }) {
  @Volatile var feedWidth: Int = ChatLayoutSpec.DEFAULT_FEED_WIDTH
    private set

  @Volatile var layout: ChatMessageLayout = ChatMessageLayout.of(feedWidth, scale())
    private set

  /** Takes the visible width of the feed; true when something a row draws has changed with it, and a width of nothing is not one */
  fun update(width: Int): Boolean {
    if (width <= 0) return false
    val next = ChatMessageLayout.of(width, scale())
    feedWidth = width
    if (next == layout) return false
    layout = next
    return true
  }

  /** The width a row gets inside the feed: the feed less the panel's own padding on both sides */
  fun rowWidth(): Int = max(0, feedWidth - (2 * ChatLayoutSpec.FEED_PAD * scale()).roundToInt())
}
