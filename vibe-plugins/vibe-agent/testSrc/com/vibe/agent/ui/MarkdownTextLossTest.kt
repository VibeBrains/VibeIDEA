// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import org.junit.jupiter.api.Test
import javax.swing.JEditorPane
import kotlin.test.assertEquals

/**
 * Renders agent answers through the real path (fence split, markdown to HTML, Swing HTML view) and compares
 * the text a person SEES with what the model wrote, minus the markup that is meant to disappear.
 */
class MarkdownTextLossTest {
  private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()

  private fun visibleProse(text: String): String {
    val html = MarkdownInline.toHtml(text) ?: return squash(text)
    val pane = JEditorPane("text/html", "<html><body>$html</body></html>")
    return squash(pane.document.getText(0, pane.document.length))
  }

  /** What the feed shows for [markdown]: prose through the HTML view, code blocks verbatim */
  private fun visible(markdown: String): String = squash(
    MessageSegments.parse(markdown).joinToString("\n") {
      when (it) {
        is MessageSegment.Prose -> visibleProse(it.text)
        is MessageSegment.Code -> it.code
      }
    },
  )

  private fun check(markdown: String, expected: String) = assertEquals(squash(expected), visible(markdown), markdown)

  @Test
  fun `generics and angle brackets survive`() {
    check("Returns `IPageResponse<IDocResponse>` and **bold** Map<String, List<Int>>", "Returns IPageResponse<IDocResponse> and bold Map<String, List<Int>>")
    check("**x**: a < b and c > d", "x: a < b and c > d")
    check("**x** <details><summary>S</summary></details> and <br> tag", "x <details><summary>S</summary></details> and <br> tag")
    check("**x** <input type=\"file\" accept=\"image/*\">", "x <input type=\"file\" accept=\"image/*\">")
  }

  @Test
  fun `entities and backslashes stay literal`() {
    check("**x** &lt;T&gt; &amp; &nbsp; C:\\dir\\file \\*not italic\\*", "x &lt;T&gt; &amp; &nbsp; C:\\dir\\file \\*not italic\\*")
  }

  @Test
  fun `lone asterisks in globs and math are not emphasis`() {
    check("**x** files src/*.kt and lib/*.kt", "x files src/*.kt and lib/*.kt")
    check("**x** accept=\"image/*\" or \"video/*\"", "x accept=\"image/*\" or \"video/*\"")
    check("**x** 2 * 3 * 4 = 24", "x 2 * 3 * 4 = 24")
  }

  @Test
  fun `brackets that are not links keep their brackets`() {
    check("**x** arr[0](1) and list[i](j)", "x arr[0](1) and list[i](j)")
  }

  @Test
  fun `tables, quotes and nested lists keep every cell`() {
    check("| a | `x|y` | <T> |\n|---|---|---|\n| **b** | c | Map<K,V> |", "| a | x|y | <T> | |---|---|---| | b | c | Map<K,V> |")
    check("> quote with <b>\n**x**", "> quote with <b> x")
    check("**x**\n- one\n    - nested\n    - nested2\n- two", "x one - nested - nested2 two")
  }

  @Test
  fun `fenced code indented inside a list item is not eaten by markdown`() {
    val md = "**Steps**\n- install:\n    ```bash\n    # comment\n    * glob *.kt\n    - dash\n    ```\n- done"
    check(md, "Steps install: # comment * glob *.kt - dash done")
  }

  @Test
  fun `many code spans keep their order`() {
    val spans = (0..12).joinToString(" ") { "`c$it<T>`" }
    check("**x** $spans", "x " + (0..12).joinToString(" ") { "c$it<T>" })
  }

  @Test
  fun `a streamed angle bracket split across chunks is whole`() {
    val turn = TurnSegments()
    listOf("Use List", "<", "Str", "ing>", " now").forEach { turn.append(it) }
    check(turn.current.text.toString(), "Use List<String> now")
  }
}
