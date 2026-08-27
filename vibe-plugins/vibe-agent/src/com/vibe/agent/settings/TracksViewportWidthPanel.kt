// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * Scroll view that follows the viewport width (vertical scroll only): long html hints
 * wrap instead of dictating the page width. Shared by the settings pages.
 */
class TracksViewportWidthPanel(content: JComponent) : JPanel(BorderLayout()), Scrollable {
  init {
    add(content, BorderLayout.NORTH)
  }

  override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
  override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(UNIT_INCREMENT)
  override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = visible.height
  override fun getScrollableTracksViewportWidth(): Boolean = true
  override fun getScrollableTracksViewportHeight(): Boolean = false

  private companion object {
    const val UNIT_INCREMENT = 16
  }
}
