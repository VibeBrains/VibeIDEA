// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBThinOverlappingScrollBar
import java.awt.Adjustable
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JScrollPane

/**
 * Thin, overlapping scrollbars everywhere in our UI (owner's call, 2026-08-28): the default bars
 * eat a visible strip of width in narrow surfaces — the chat feed, settings pages, the model
 * popup — and look heavy next to the rest of the panel.
 *
 * `JBThinOverlappingScrollBar` is the platform's own thin variant (`isThin() = true`), the same one
 * the navigation bar and code-review lists use, so themes and hover behaviour stay native.
 */
object VibeScroll {
  /** Our standard scroll pane: same as [JBScrollPane], only with thin bars. */
  fun pane(view: Component): JBScrollPane = JBScrollPane(view).also { thin(it) }

  /** Swaps both bars of an existing pane for thin ones. */
  fun thin(pane: JScrollPane): JScrollPane = pane.apply {
    verticalScrollBar = JBThinOverlappingScrollBar(Adjustable.VERTICAL)
    horizontalScrollBar = JBThinOverlappingScrollBar(Adjustable.HORIZONTAL)
  }

  /**
   * Makes every scroll pane inside [root] thin — for surfaces we do not construct ourselves,
   * such as the searchable popup built by `JBPopupFactory` (its scroll pane is created deep
   * inside the builder and never exposed).
   */
  fun thinAllIn(root: Component?) {
    if (root == null) return
    if (root is JScrollPane) thin(root)
    if (root is Container) root.components.forEach { thinAllIn(it) }
  }

  /** Convenience for builders that hand back a [JComponent] root. */
  fun thinAllIn(root: JComponent) = thinAllIn(root as Component)
}
