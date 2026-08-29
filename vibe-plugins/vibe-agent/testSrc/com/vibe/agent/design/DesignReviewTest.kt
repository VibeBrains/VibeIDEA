// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesignReviewTest {
  private val glassy = ElementSnapshot(selector = "div.card", tag = "div", backdropFilter = "blur(12px)")
  private val unreadable = ElementSnapshot(selector = "p", tag = "p", text = "текст", color = Rgb(200, 200, 200), fontSizePx = 16.0)

  private fun doc(vararg elements: ElementSnapshot, viewport: Viewport = Viewport.DESKTOP) =
    DocumentSnapshot(viewportWidthPx = if (viewport == Viewport.MOBILE) 390.0 else 1280.0,
                     viewportHeightPx = 800.0, viewport = viewport, elements = elements.toList())

  @Test
  fun `the class always comes from the catalogue`() {
    val report = DesignReview.run(doc(unreadable, glassy))
    assertEquals(RuleClass.FLOOR, report.findings.first { it.rule == DesignRuleCatalog.CONTRAST_TEXT }.ruleClass)
    assertEquals(RuleClass.STYLE, report.findings.first { it.rule == DesignRuleCatalog.GLASSMORPHISM }.ruleClass)
  }

  @Test
  fun `a project can accept a style drift, with the reason kept`() {
    val accepted = listOf(DesignReview.Accepted(DesignRuleCatalog.GLASSMORPHISM, "стекло — часть фирменного стиля"))
    val report = DesignReview.run(doc(glassy), accepted)
    val finding = report.findings.single { it.rule == DesignRuleCatalog.GLASSMORPHISM }
    assertEquals("стекло — часть фирменного стиля", finding.acceptedReason)
    assertTrue(report.floor.isEmpty())
  }

  @Test
  fun `the floor cannot be accepted away — unreadable text is not an identity`() {
    val accepted = listOf(DesignReview.Accepted(DesignRuleCatalog.CONTRAST_TEXT, "у нас так принято"))
    val report = DesignReview.run(doc(unreadable), accepted)
    val finding = report.findings.single { it.rule == DesignRuleCatalog.CONTRAST_TEXT }
    assertNull(finding.acceptedReason)
    assertEquals(1, report.floor.size)
  }

  @Test
  fun `floor findings come first — that is the order a person acts in`() {
    val report = DesignReview.run(doc(glassy, unreadable))
    assertEquals(RuleClass.FLOOR, report.findings.first().ruleClass)
  }

  @Test
  fun `the same defect in both viewports is one finding, a mobile-only one survives`() {
    val desktop = DesignReview.run(doc(unreadable))
    val mobile = DesignReview.run(doc(unreadable, glassy, viewport = Viewport.MOBILE))
    val merged = DesignReview.merge(listOf(desktop, mobile))
    assertEquals(1, merged.count { it.rule == DesignRuleCatalog.CONTRAST_TEXT })
    assertEquals(1, merged.count { it.rule == DesignRuleCatalog.GLASSMORPHISM })
  }

  @Test
  fun `a clean page says so instead of printing an empty list`() {
    assertTrue(DesignReview.summary(emptyList()).contains("находок нет"))
    assertTrue(DesignReview.run(doc()).isClean)
  }

  @Test
  fun `the summary counts floor, style and accepted apart`() {
    val accepted = listOf(DesignReview.Accepted(DesignRuleCatalog.GLASSMORPHISM, "фирменный стиль"))
    val text = DesignReview.summary(DesignReview.run(doc(unreadable, glassy), accepted).findings)
    assertTrue(text.contains("пол качества — 1"), text)
    assertTrue(text.contains("принято проектом — 1"), text)
  }
}
