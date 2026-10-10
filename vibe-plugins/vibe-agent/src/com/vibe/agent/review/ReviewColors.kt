// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Colors of the in-editor review, all theme tokens (`ui.Vibe.Review` in every theme file)
 *
 * The keys are written out in full: a key assembled from parts cannot be found by searching the code, and the gate cannot check it
 * The defaults are what a theme that does not know the token falls back to
 */
internal object ReviewColors {
  /** Background of lines the agent added */
  val added: Color = JBColor.namedColor("Vibe.Review.added", JBColor(0xE4EBE0, 0x223027))

  /** Background of lines the agent rewrote */
  val changed: Color = JBColor.namedColor("Vibe.Review.changed", JBColor(0xE2EAEE, 0x123A42))

  /** Background of the old text shown above a hunk */
  val removed: Color = JBColor.namedColor("Vibe.Review.removed", JBColor(0xF1E3E6, 0x3E2526))

  /** Background of the toolbar row of the hunk the reader is on */
  val current: Color = JBColor.namedColor("Vibe.Review.current", JBColor(0xC7D7E2, 0x0F5964))

  /** The Accept button, and the ruler mark of an added hunk */
  val accept: Color = JBColor.namedColor("Vibe.Review.accept", JBColor(0x3F7A34, 0x5FAD5F))

  /** The Reject button, and the ruler mark of a removed hunk */
  val reject: Color = JBColor.namedColor("Vibe.Review.reject", JBColor(0xA03A5D, 0xFF6B5A))
}
