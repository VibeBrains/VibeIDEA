// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JSON comes from a page the user opened — that is, from anyone's site. Everything here is
 * about surviving it: a missing field is "not measured", a broken element is dropped, and nothing
 * absent is allowed to arrive as a zero that looks like a measurement.
 */
class DesignSnapshotCodecTest {
  private val full = """
    {"url":"http://localhost:3000","viewportWidthPx":1280,"viewportHeightPx":800,
     "documentScrollWidthPx":1400,
     "elements":[{"selector":"main > p","parentId":0,"tag":"p","text":"привет",
       "color":[17,17,17],"backgroundColor":[255,255,255],"fontSizePx":16,"fontWeight":400,
       "widthPx":640,"heightPx":24,"interactive":false,"hasAltAttribute":true,
       "styleRulesUnreadable":true,"classes":["lead","muted"],"childTags":["span"]}],
     "headings":[{"tag":"h1","text":"Заголовок","fontSizePx":32}]}
  """.trimIndent()

  @Test
  fun `a full snapshot parses with its viewport stamped by the caller`() {
    val doc = DesignSnapshotCodec.parse(full, Viewport.MOBILE)!!
    assertEquals(Viewport.MOBILE, doc.viewport, "вьюпорт знает вызывающий, а не страница")
    assertEquals(1280.0, doc.viewportWidthPx)
    assertEquals(1400.0, doc.documentScrollWidthPx)
    val element = doc.elements.single()
    assertEquals("main > p", element.selector)
    assertEquals(Rgb(17, 17, 17), element.color)
    assertEquals(listOf("lead", "muted"), element.classes)
    assertTrue(element.styleRulesUnreadable)
    assertEquals("Заголовок", doc.headings.single().text)
  }

  @Test
  fun `a broken element is dropped, the rest of the page survives`() {
    val text = """{"viewportWidthPx":800,"viewportHeightPx":600,"elements":[
      {"tag":"p"}, {"selector":"div","tag":"div"}, "не объект"]}"""
    val doc = DesignSnapshotCodec.parse(text, Viewport.DESKTOP)!!
    assertEquals(listOf("div"), doc.elements.map { it.selector })
  }

  @Test
  fun `absent fields do not become measurements`() {
    val text = """{"viewportWidthPx":800,"viewportHeightPx":600,"elements":[{"selector":"p","tag":"p"}]}"""
    val element = DesignSnapshotCodec.parse(text, Viewport.DESKTOP)!!.elements.single()
    assertEquals(0.0, element.fontSizePx, "нет замера — ноль, и правило кегля на него не сработает")
    assertFalse(element.hasAltAttribute)
    assertFalse(element.styleRulesUnreadable)
    assertEquals(-1, element.parentId, "родство неизвестно — не нулевой индекс")
  }

  @Test
  fun `a malformed colour falls back instead of throwing`() {
    val text = """{"viewportWidthPx":800,"viewportHeightPx":600,"elements":[
      {"selector":"p","tag":"p","color":[1,2],"backgroundColor":"rgb(0,0,0)"}]}"""
    val element = DesignSnapshotCodec.parse(text, Viewport.DESKTOP)!!.elements.single()
    assertEquals(Rgb(0, 0, 0), element.color)
    assertEquals(Rgb(255, 255, 255), element.backgroundColor)
  }

  @Test
  fun `garbage is not a snapshot`() {
    assertNull(DesignSnapshotCodec.parse("не json", Viewport.DESKTOP))
    assertNull(DesignSnapshotCodec.parse("[1,2,3]", Viewport.DESKTOP))
  }

  @Test
  fun `a parsed snapshot goes straight into the rules`() {
    // The point of the codec: what the page reported must be judgeable without further shaping.
    val text = """{"viewportWidthPx":390,"viewportHeightPx":800,"documentScrollWidthPx":900,
      "elements":[{"selector":"p","tag":"p","text":"текст","fontSizePx":9,"color":[0,0,0],"backgroundColor":[255,255,255]}]}"""
    val doc = DesignSnapshotCodec.parse(text, Viewport.MOBILE)!!
    val report = DesignReview.run(doc)
    assertTrue(report.floor.any { it.rule == DesignRuleCatalog.TEXT_TOO_SMALL })
    assertTrue(report.floor.any { it.rule == DesignRuleCatalog.PAGE_WIDER_THAN_VIEWPORT })
    assertTrue(report.findings.all { it.viewport == Viewport.MOBILE })
  }
}
