// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownInlineTest {
  @Test
  fun `model output can never become markup — escaping happens first`() {
    // The whole security of this file: an answer containing a tag must render as characters.
    val html = MarkdownInline.toHtml("**важно**: <img src=x onerror=alert(1)>")!!
    assertFalse(html.contains("<img"), html)
    assertTrue(html.contains("&lt;img"), html)
    assertTrue(html.contains("<b>важно</b>"), html)
  }

  @Test
  fun `bold, italic and inline code render`() {
    val html = MarkdownInline.toHtml("**жирный**, *курсив* и `код`")!!
    assertTrue(html.contains("<b>жирный</b>"), html)
    assertTrue(html.contains("<i>курсив</i>"), html)
    assertTrue(html.contains("<code"), html)
  }

  @Test
  fun `emphasis inside backticks stays code, not emphasis`() {
    val html = MarkdownInline.toHtml("`**не жирный**` и **жирный**")!!
    assertTrue(html.contains("**не жирный**"), "внутри кода звёздочки — часть кода: $html")
    assertTrue(html.contains("<b>жирный</b>"), html)
  }

  @Test
  fun `a list becomes a list — an unrendered one reads as a paragraph`() {
    val html = MarkdownInline.toHtml("Шаги:\n- первый\n- второй\n\nдальше")!!
    assertEquals(2, Regex("<li>").findAll(html).count(), html)
    assertTrue(html.contains("<ul"), html)
    assertTrue(html.contains("</ul>"), "список обязан закрыться перед обычным текстом")
  }

  @Test
  fun `a heading becomes bold text, not a browser heading`() {
    // A chat bubble is not a document: <h1> would tower over the conversation around it.
    val html = MarkdownInline.toHtml("## Раздел\nтекст")!!
    assertTrue(html.contains("<b>Раздел</b>"), html)
    assertFalse(html.contains("<h1") || html.contains("<h2"), html)
  }

  @Test
  fun `only safe links become clickable`() {
    val safe = MarkdownInline.toHtml("[док](https://example.com/a) тут")!!
    assertTrue(safe.contains("<a href='https://example.com/a'>док</a>"), safe)

    val unsafe = MarkdownInline.toHtml("[клик](javascript:alert(1)) и **текст**")!!
    assertFalse(unsafe.contains("<a "), "javascript: не должен стать ссылкой: $unsafe")
    assertTrue(unsafe.contains("клик"), unsafe)
  }

  @Test
  fun `plain text is left alone — the HTML view flickers on a streaming answer`() {
    assertNull(MarkdownInline.toHtml("Обычный ответ без разметки."))
    assertNull(MarkdownInline.toHtml("Цена 5 * 3 звёздочки не разметка"))
  }

  @Test
  fun `a huge answer stays plain`() {
    assertNull(MarkdownInline.toHtml("**bold** " + "x".repeat(MarkdownInline.MAX_HTML_CHARS)))
  }
}
