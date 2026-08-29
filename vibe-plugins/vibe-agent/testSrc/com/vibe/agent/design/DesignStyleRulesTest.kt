// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignStyleRulesTest {
  private fun doc(vararg elements: ElementSnapshot) =
    DocumentSnapshot(viewportWidthPx = 1280.0, viewportHeightPx = 800.0, elements = elements.toList())

  @Test
  fun `a gradient headline is a hint, a plain one is silent`() {
    val gradient = ElementSnapshot(selector = "h1", tag = "h1", text = "Заголовок",
                                   backgroundClip = "text", backgroundImage = "linear-gradient(90deg, #a0f, #0af)")
    assertEquals(1, DesignStyleRules.gradientText(doc(gradient)).size)
    assertEquals(Severity.HINT, DesignStyleRules.gradientText(doc(gradient)).single().severity)
    assertTrue(DesignStyleRules.gradientText(doc(gradient.copy(backgroundClip = "border-box"))).isEmpty())
  }

  @Test
  fun `a shadow without offset is a glow, a normal shadow is not`() {
    val glow = ElementSnapshot(selector = "div", tag = "div", boxShadow = "rgba(120,0,255,0.6) 0px 0px 40px 0px")
    assertEquals(1, DesignStyleRules.glow(doc(glow)).size)
    val shadow = glow.copy(boxShadow = "rgba(0,0,0,0.2) 0px 4px 12px 0px")
    assertTrue(DesignStyleRules.glow(doc(shadow)).isEmpty())
  }

  @Test
  fun `purple fires only when it IS the palette, not when it is an accent`() {
    val purple = ElementSnapshot(selector = "a", tag = "div", ownBackgroundAlpha = 1.0, backgroundColor = Rgb(120, 60, 220))
    val neutral = ElementSnapshot(selector = "b", tag = "div", ownBackgroundAlpha = 1.0, backgroundColor = Rgb(20, 140, 90))
    assertEquals(1, DesignStyleRules.purple(doc(purple, purple.copy(selector = "c"), purple.copy(selector = "d"))).size)
    assertTrue(DesignStyleRules.purple(doc(purple, neutral, neutral.copy(selector = "e"), neutral.copy(selector = "f"))).isEmpty(),
               "один фиолетовый акцент — выбор, а не примета")
  }

  @Test
  fun `three identical cards read as a template, two do not`() {
    fun card(name: String) = ElementSnapshot(selector = name, tag = "div", parentId = 0, ownBackgroundAlpha = 1.0,
                                             childTags = listOf("h3", "p", "a"), widthPx = 300.0, heightPx = 200.0)
    assertEquals(1, DesignStyleRules.clones(doc(card("a"), card("b"), card("c"))).size)
    assertTrue(DesignStyleRules.clones(doc(card("a"), card("b"))).isEmpty())
  }

  @Test
  fun `a pill button escapes the extreme radius rule`() {
    // radius >= height/2 is a pill or an avatar: legitimate shapes, not a stray value.
    val pill = ElementSnapshot(selector = "button", tag = "button", borderRadiusPx = 48.0, heightPx = 96.0)
    assertTrue(DesignStyleRules.extremeRadius(doc(pill)).isEmpty())
    val stray = pill.copy(borderRadiusPx = 44.0, heightPx = 300.0)
    assertEquals(1, DesignStyleRules.extremeRadius(doc(stray)).size)
  }

  @Test
  fun `animating a layout property is reported, transform is not`() {
    val layout = ElementSnapshot(selector = "div", tag = "div", transitionProperty = "width, opacity")
    assertEquals(1, DesignStyleRules.animatedLayout(doc(layout)).size)
    assertTrue(DesignStyleRules.animatedLayout(doc(layout.copy(transitionProperty = "transform, opacity"))).isEmpty())
  }

  @Test
  fun `russian typography rules use MEASURED line breaking, not a guess`() {
    val hanging = ElementSnapshot(selector = "p", tag = "p", text = "…", textLineCount = 4, linesEndingWithShortWord = 2)
    assertEquals(1, DesignStyleRules.hangingPreposition(doc(hanging)).size)
    assertTrue(DesignStyleRules.hangingPreposition(doc(hanging.copy(textLineCount = 1))).isEmpty())

    val orphan = ElementSnapshot(selector = "p", tag = "p", textLineCount = 3, lastLineWordCount = 1)
    assertEquals(1, DesignStyleRules.orphanWord(doc(orphan)).size)
    assertTrue(DesignStyleRules.orphanWord(doc(orphan.copy(lastLineWordCount = 4))).isEmpty())
  }

  @Test
  fun `hover is a hint and stays silent when stylesheets were unreadable`() {
    val button = ElementSnapshot(selector = "button", tag = "button", interactive = true, hasHoverRule = false)
    assertEquals(Severity.HINT, DesignStyleRules.hoverResponse(doc(button)).single().severity)
    assertTrue(DesignStyleRules.hoverResponse(doc(button.copy(styleRulesUnreadable = true))).isEmpty())
  }
}
