// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.ui.composer.ComposerPanel
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How tall the composer is, as a pure function of the space it shares with the feed
 *
 * With no choice made by the person the composer takes what it asks for, capped at a share of the panel,
 * so a long paste never eats the feed. With a choice (a fraction of the panel) it takes that fraction
 * Either way the feed keeps [minFeed] and the composer never goes under [minComposer]; if both cannot hold, the composer wins
 */
internal object ComposerHeightPolicy {
  /** Largest share of the panel the composer takes by itself while it grows with the text */
  const val AUTO_MAX_SHARE = 0.4f

  /** The feed never gets smaller than this, logical pixels */
  const val MIN_FEED = 120

  /** A fraction outside this range is a drag that went past the edge */
  const val MIN_FRACTION = 0.05f
  const val MAX_FRACTION = 0.95f

  fun height(total: Int, preferred: Int, minComposer: Int, minFeed: Int, fraction: Float?): Int {
    if (total <= 0) return minComposer
    val wanted = if (fraction == null) min(preferred, (total * AUTO_MAX_SHARE).roundToInt()) else (total * fraction).roundToInt()
    val upper = max(minComposer, total - minFeed)
    return wanted.coerceIn(minComposer, min(upper, total))
  }

  /** The fraction that a composer of [height] makes of [total], or null when the panel has no height to measure against */
  fun fractionOf(height: Int, total: Int): Float? =
    if (total <= 0) null else (height.toFloat() / total).coerceIn(MIN_FRACTION, MAX_FRACTION)
}

/** Where the person's proportion lives: one value for the IDE, not per project */
internal interface ComposerFractionStore {
  fun load(): Float?
  fun save(fraction: Float?)
}

internal class PropertiesFractionStore(private val key: String = KEY) : ComposerFractionStore {
  override fun load(): Float? =
    PropertiesComponent.getInstance().getValue(key)?.toFloatOrNull()
      ?.takeIf { it in ComposerHeightPolicy.MIN_FRACTION..ComposerHeightPolicy.MAX_FRACTION }

  override fun save(fraction: Float?) {
    PropertiesComponent.getInstance().setValue(key, fraction?.toString())
  }

  companion object {
    const val KEY = "vibe.chat.composerFraction"
  }
}

/**
 * The feed over the composer with a divider between them that the person can drag
 *
 * Not a JBSplitter: the composer is sized by its text until the person says otherwise, and a splitter's
 * proportion has no such mode. The extra height the composer gets goes to its input, which is the elastic part
 * Double click on the divider gives the choice back to the text
 */
internal class ComposerSplit(
  private val feed: JComponent,
  private val composer: ComposerPanel,
  private val store: ComposerFractionStore,
) : JPanel(null) {
  private var fraction: Float? = store.load()
  private val divider = Divider()

  init {
    isOpaque = false
  }

  /** Takes the feed and the composer in: each may have gone to the empty chat's layout since, and adding moves it back */
  fun adopt() {
    add(divider)
    add(composer)
    add(feed)
  }

  /** The composer's height for a panel [total] tall, divider excluded */
  private fun composerHeight(total: Int): Int = ComposerHeightPolicy.height(
    total = total,
    preferred = composer.preferredSize.height,
    minComposer = composer.minimumHeight(),
    minFeed = JBUI.scale(ComposerHeightPolicy.MIN_FEED),
    fraction = fraction,
  )

  override fun getPreferredSize(): Dimension = Dimension(composer.preferredSize.width, feed.preferredSize.height + composer.preferredSize.height)

  override fun getMinimumSize(): Dimension = Dimension(0, 0)

  override fun doLayout() {
    val gap = JBUI.scale(DIVIDER_HEIGHT)
    val c = composerHeight(max(0, height - gap))
    val feedHeight = max(0, height - gap - c)
    feed.setBounds(0, 0, width, feedHeight)
    divider.setBounds(0, feedHeight, width, gap)
    composer.setBounds(0, feedHeight + gap, width, height - feedHeight - gap)
  }

  private fun choose(newFraction: Float?) {
    fraction = newFraction
    revalidate()
    repaint()
  }

  private inner class Divider : JComponent() {
    private var startY = 0
    private var startHeight = 0

    init {
      cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
      toolTipText = t("chat.composerSplit.hint")
      val drag = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
          startY = e.yOnScreen
          startHeight = composer.height
        }

        override fun mouseDragged(e: MouseEvent) {
          val total = this@ComposerSplit.height - height
          choose(ComposerHeightPolicy.fractionOf(startHeight - (e.yOnScreen - startY), total))
        }

        override fun mouseReleased(e: MouseEvent) {
          store.save(fraction)
        }

        override fun mouseClicked(e: MouseEvent) {
          if (e.clickCount == 2) {
            choose(null)
            store.save(null)
          }
        }
      }
      addMouseListener(drag)
      addMouseMotionListener(drag)
    }

    override fun paintComponent(g: Graphics) {
      g.color = ComposerPanel.SEPARATOR
      g.fillRect(0, height / 2, width, 1)
    }
  }

  companion object {
    /** Height of the divider's grip, logical pixels: the line is one pixel, the rest is room to grab */
    const val DIVIDER_HEIGHT = 5
  }
}
