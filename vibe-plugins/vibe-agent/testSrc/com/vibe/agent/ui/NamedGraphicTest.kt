// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import java.awt.Graphics
import kotlin.test.Test
import kotlin.test.assertEquals

/** Именованная картинка создаётся без падения — именно на этом умерла страница «Оформление». */
class NamedGraphicTest {
  private class Dot : NamedGraphic("точка") {
    override fun paintComponent(g: Graphics) = Unit
  }

  @Test
  fun `создаётся и называет себя`() {
    val dot = Dot()
    assertEquals("точка", dot.accessibleContext.accessibleName)
    assertEquals("точка", dot.toolTipText)
  }
}
