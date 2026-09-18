// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Our own icons, loaded once.
 *
 * Own rather than the platform's wherever the platform's means something else: the composer's
 * microphone used `AllIcons.Ide.Macro.Recording_1`, which draws a tape cassette — on the button
 * that records the voice it reads as a broken icon, not as «press to speak».
 */
object VibeIcons {
  val MIC: Icon = load("/icons/vibeMic.svg")
  val MIC_ON: Icon = load("/icons/vibeMicOn.svg")

  private fun load(path: String): Icon = IconLoader.getIcon(path, VibeIcons::class.java)
}
