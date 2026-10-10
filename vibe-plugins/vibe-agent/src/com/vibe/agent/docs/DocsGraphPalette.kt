// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.docs

import com.intellij.ui.JBColor
import com.vibe.agent.graphview.GraphPalette

/**
 * Colour of a document category: a fixed palette plus a stable hash of the name
 *
 * Categories appear at runtime: `knowledge` and `manuals` today, `adr` and `runbooks` tomorrow
 * Colours cannot be handed out in order of traversal: an added folder would repaint all the others
 * And a person who remembered «the purple ones are knowledge» would learn the colours again every day
 * A hash of the name gives the same colour between runs and does not depend on how many folders are around
 *
 * The palette is the theme's own tokens (`Vibe.Docs.category*`, declared in [GraphPalette]):
 * The platform (263) has no `charts.blue/green/…` keys to take from the editor theme, checked by searching `platform/`
 * Our tokens override from a user theme the same way
 */
object DocsGraphPalette {
  /** Documents of the root have no category and take the second colour: there are few of them, and always the same ones */
  private const val ROOT_DOCUMENTS_COLOR = 1

  fun colorOf(category: String): JBColor {
    if (category.isEmpty()) return GraphPalette.base(ROOT_DOCUMENTS_COLOR)
    return GraphPalette.base(stableHash(category))
  }

  /**
   * Stable hash of a name
   *
   * Our own, not `String.hashCode()`: the platform one need not match between JVM versions
   * And a category colour would one day change for no reason, with not a single edit in the project
   */
  fun stableHash(text: String): Int {
    var hash = 2166136261u.toInt()
    for (ch in text) {
      hash = hash xor ch.code
      hash *= 16777619
    }
    return hash
  }
}
