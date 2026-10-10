// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graphview

import javax.swing.JComponent

/**
 * Text that comes from a project (a file name, a folder, a document title) and is shown in a Swing component
 *
 * Swing takes a string that starts with `<html>` for markup, and a markup with an image in it fetches the address from the image
 * A cloned repository chooses its own file names, so a name is data and must not be read as a page
 */
object PlainText {
  private const val ZERO_WIDTH_SPACE = "​"

  /** A string that stays text in a tooltip or a list: a leading invisible character is enough, Swing looks at the first six characters */
  fun safe(text: String): String = if (text.startsWith("<")) ZERO_WIDTH_SPACE + text else text

  /** A label that shows its text as it is, whatever the text looks like */
  fun <T : JComponent> plain(label: T): T = label.also { it.putClientProperty(HTML_DISABLE, true) }

  /** The client property Swing's HTML renderer checks before it reads a string as markup */
  private const val HTML_DISABLE = "html.disable"
}
