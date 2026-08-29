// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every rule is tested twice: it fires on the defect AND stays silent on the legitimate case.
 * The second half matters more — a detector that scolds correct work is one people stop reading,
 * and then it protects nothing at all.
 */
class DesignFloorRulesTest {
  private fun element(
    selector: String = "p",
    tag: String = "p",
    text: String = "текст",
    color: Rgb = Rgb(0, 0, 0),
    background: Rgb = Rgb(255, 255, 255),
    fontSizePx: Double = 16.0,
    fontWeight: Int = 400,
    width: Double = 100.0,
    height: Double = 40.0,
    interactive: Boolean = false,
    disabled: Boolean = false,
    parentId: Int = -1,
    zIndex: Int = 0,
    ownAlpha: Double = 0.0,
    left: Double = 0.0,
    top: Double = 0.0,
    scrollWidth: Double = 0.0,
    clientWidth: Double = 0.0,
    overflowX: String = "visible",
    outlineStyle: String = "solid",
    outlineWidth: Double = 2.0,
    hasFocusRule: Boolean = true,
    unreadable: Boolean = false,
    imgSrc: String = "",
    imgNatural: Double = 0.0,
  ) = ElementSnapshot(
    selector = selector, tag = tag, text = text, color = color, backgroundColor = background,
    fontSizePx = fontSizePx, fontWeight = fontWeight, widthPx = width, heightPx = height,
    interactive = interactive, disabled = disabled, parentId = parentId, zIndex = zIndex,
    ownBackgroundAlpha = ownAlpha, leftPx = left, topPx = top, scrollWidthPx = scrollWidth,
    clientWidthPx = clientWidth, overflowX = overflowX, outlineStyle = outlineStyle,
    outlineWidthPx = outlineWidth, hasFocusRule = hasFocusRule, styleRulesUnreadable = unreadable,
    imgSrc = imgSrc, imgNaturalWidthPx = imgNatural,
  )

  private fun doc(vararg elements: ElementSnapshot, scrollWidth: Double = 0.0, viewportWidth: Double = 1280.0) =
    DocumentSnapshot(viewportWidthPx = viewportWidth, viewportHeightPx = 800.0,
                     documentScrollWidthPx = scrollWidth, elements = elements.toList())

  // --- contrast ---

  @Test
  fun `grey on white below AA is reported with the measured ratio`() {
    val findings = DesignFloorRules.contrast(doc(element(color = Rgb(170, 170, 170))))
    assertEquals(1, findings.size)
    assertTrue(findings.single().evidence.contains(":1"), findings.single().evidence)
  }

  @Test
  fun `black on white is silent`() {
    assertTrue(DesignFloorRules.contrast(doc(element())).isEmpty())
  }

  @Test
  fun `a large headline is judged by the 3 to 1 threshold, not 4_5`() {
    // Applying the body-text rule to a headline is the fastest way to make people ignore the tool.
    val headline = element(text = "Заголовок", color = Rgb(130, 130, 130), fontSizePx = 32.0)
    assertTrue(DesignFloorRules.contrast(doc(headline)).isEmpty())
  }

  // --- size and reachability ---

  @Test
  fun `tiny text fires, ordinary body text does not`() {
    assertEquals(1, DesignFloorRules.tinyText(doc(element(fontSizePx = 10.0))).size)
    assertTrue(DesignFloorRules.tinyText(doc(element(fontSizePx = 14.0))).isEmpty())
  }

  @Test
  fun `a small tap target fires only for interactive elements that are laid out`() {
    assertEquals(1, DesignFloorRules.tapTargets(doc(element(interactive = true, width = 16.0, height = 16.0))).size)
    assertTrue(DesignFloorRules.tapTargets(doc(element(width = 16.0, height = 16.0))).isEmpty(), "не интерактивный")
    assertTrue(DesignFloorRules.tapTargets(doc(element(interactive = true, width = 0.0, height = 0.0))).isEmpty(),
               "нулевая коробка — элемент не в раскладке, а не мелкая цель")
    assertTrue(DesignFloorRules.tapTargets(doc(element(interactive = true, disabled = true, width = 10.0, height = 10.0))).isEmpty())
  }

  @Test
  fun `clipped content fires, a scrollable container does not`() {
    assertEquals(1, DesignFloorRules.clipped(doc(element(scrollWidth = 500.0, clientWidth = 300.0))).size)
    assertTrue(DesignFloorRules.clipped(doc(element(scrollWidth = 500.0, clientWidth = 300.0, overflowX = "auto"))).isEmpty())
    assertTrue(DesignFloorRules.clipped(doc(element(scrollWidth = 301.0, clientWidth = 300.0))).isEmpty(), "субпиксель — не дефект")
  }

  // --- occlusion: the rule that used to accuse ordinary nesting ---

  @Test
  fun `an opaque sibling on top hides the text`() {
    val text = element(selector = "p", zIndex = 0)
    val cover = element(selector = "div.overlay", text = "", zIndex = 5, ownAlpha = 1.0)
    assertEquals(1, DesignFloorRules.occluded(doc(text, cover)).size)
  }

  @Test
  fun `an opaque PARENT is not an occluding layer`() {
    // The bug this field exists for: kinship compared by selector strings called nesting an overlay.
    val parent = element(selector = "div.card", text = "", zIndex = 5, ownAlpha = 1.0, parentId = -1)
    val child = element(selector = "div.card > p", zIndex = 0, parentId = 0)
    assertTrue(DesignFloorRules.occluded(doc(parent, child)).isEmpty())
  }

  @Test
  fun `a transparent layer on top hides nothing`() {
    val text = element(selector = "p")
    val glassy = element(selector = "div.glass", text = "", zIndex = 5, ownAlpha = 0.2)
    assertTrue(DesignFloorRules.occluded(doc(text, glassy)).isEmpty())
  }

  // --- page and images ---

  @Test
  fun `a page wider than the window is reported once, for the document`() {
    val findings = DesignFloorRules.pageOverflow(doc(element(), scrollWidth = 1400.0))
    assertEquals(1, findings.size)
    assertEquals("html", findings.single().selector)
    assertTrue(DesignFloorRules.pageOverflow(doc(element(), scrollWidth = 1281.0)).isEmpty())
  }

  @Test
  fun `an image that never loaded is a defect, a loaded one is not`() {
    assertEquals(1, DesignFloorRules.brokenImages(doc(element(tag = "img", text = "", imgSrc = "/a.png"))).size)
    assertTrue(DesignFloorRules.brokenImages(doc(element(tag = "img", text = "", imgSrc = "/a.png", imgNatural = 800.0))).isEmpty())
  }

  // --- states ---

  @Test
  fun `a removed focus ring fires only when the stylesheets were readable`() {
    val bad = element(interactive = true, outlineStyle = "none", outlineWidth = 0.0, hasFocusRule = false)
    assertEquals(1, DesignFloorRules.focusRing(doc(bad)).size)
    assertTrue(DesignFloorRules.focusRing(doc(bad.copy(hasFocusRule = true))).isEmpty(), "замена есть")
    assertTrue(DesignFloorRules.focusRing(doc(bad.copy(styleRulesUnreadable = true))).isEmpty(),
               "стили не прочитаны — «правила нет» означало бы «не смогли посмотреть»")
  }

  @Test
  fun `a disabled control at full contrast looks clickable`() {
    assertEquals(1, DesignFloorRules.disabledLook(doc(element(disabled = true))).size)
    assertTrue(DesignFloorRules.disabledLook(doc(element(disabled = true, color = Rgb(190, 190, 190)))).isEmpty())
  }

  // --- structure ---

  @Test
  fun `a skipped heading level is reported, a proper hierarchy is not`() {
    val skipped = DocumentSnapshot(viewportWidthPx = 1280.0, viewportHeightPx = 800.0, headings = listOf(
      HeadingSnapshot("h1", "Заголовок", 32.0), HeadingSnapshot("h3", "Подзаголовок", 20.0)))
    assertEquals(1, DesignFloorRules.headings(skipped).size)

    val fine = DocumentSnapshot(viewportWidthPx = 1280.0, viewportHeightPx = 800.0, headings = listOf(
      HeadingSnapshot("h1", "Заголовок", 32.0), HeadingSnapshot("h2", "Раздел", 24.0), HeadingSnapshot("h3", "Пункт", 20.0)))
    assertTrue(DesignFloorRules.headings(fine).isEmpty())
  }
}
