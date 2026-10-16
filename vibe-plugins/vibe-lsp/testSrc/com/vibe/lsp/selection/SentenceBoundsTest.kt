// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The fourth click: the sentence from dot to dot, kept inside the string or the comment the click landed in. */
class SentenceBoundsTest {
  /** Marks the click with `|` and returns the selected text, or null for no sentence. */
  private fun pick(marked: String): String? {
    val offset = marked.indexOf('|')
    val text = marked.removeRange(offset, offset + 1)
    val span = SentenceBounds.find(text, offset) ?: return null
    return text.substring(span.start, span.end)
  }

  @Test
  fun `середина предложения — от точки до точки`() {
    assertEquals("Second one here.", pick("First one. Sec|ond one here. Third."))
  }

  @Test
  fun `первое и последнее предложение строки`() {
    assertEquals("First one.", pick("Fi|rst one. Second."))
    assertEquals("Second.", pick("First one. Sec|ond."))
  }

  @Test
  fun `восклицание и вопрос заканчивают предложение`() {
    assertEquals("Really?", pick("Stop! Real|ly? Yes."))
    assertEquals("Stop!", pick("St|op! Really? Yes."))
  }

  @Test
  fun `точка внутри слова ничего не заканчивает`() {
    assertEquals("Call obj.method() with 0.5 now.", pick("Call obj.me|thod() with 0.5 now."))
  }

  @Test
  fun `курсор сразу после точки — это конец этого предложения`() {
    assertEquals("One.", pick("One.| Two."))
  }

  @Test
  fun `курсор в пробеле между предложениями — следующее`() {
    assertEquals("Two.", pick("One. |Two."))
  }

  @Test
  fun `строка без точек — вся строка без отступа`() {
    assertEquals("val a = foo(bar)", pick("    val a = fo|o(bar)\nnext line. Another."))
  }

  @Test
  fun `предложение не выходит за строку`() {
    assertEquals("tail of the first", pick("head. tail of the f|irst\nsecond line. here"))
  }

  @Test
  fun `внутри шаблонной строки — только её содержимое`() {
    val line = "const q = `(drPurpose is not null and drOperationId = '\${operat|ionId}' and status = 'end')`; done."
    assertEquals(
      "(drPurpose is not null and drOperationId = '\${operationId}' and status = 'end')",
      pick(line),
    )
  }

  @Test
  fun `внутри строки с точками — предложение строки, не соседний код`() {
    assertEquals("Second sentence.", pick("log('First sentence. Sec|ond sentence.', x); other.call();"))
  }

  @Test
  fun `экранированная кавычка не закрывает строку`() {
    assertEquals("say \\\"hi\\\" now.", pick("x = \"say \\\"hi\\\" n|ow.\"; y"))
  }

  @Test
  fun `апостроф в слове не открывает строку`() {
    assertEquals("It doesn't matter.", pick("It doesn't mat|ter. Next."))
  }

  @Test
  fun `строчный комментарий — его текст`() {
    assertEquals("Second thing.", pick("call(); // First thing. Sec|ond thing."))
  }

  @Test
  fun `блочный комментарий в одну строку`() {
    assertEquals("Only this.", pick("a(); /* Only th|is. */ b();"))
  }

  @Test
  fun `строка javadoc — без звёздочки в начале`() {
    assertEquals("Does the thing.", pick("   * Does the t|hing. Then more."))
  }

  @Test
  fun `код рядом со строкой — вся строка`() {
    assertEquals("run('a b'); then.stop()", pick("run('a b'); th|en.stop()"))
  }

  @Test
  fun `пустая строка и пробелы — предложения нет`() {
    assertNull(pick("|"))
    assertNull(pick("   |   "))
    assertNull(pick("a\n|\nb"))
  }

  @Test
  fun `курсор в конце текста`() {
    assertEquals("Last one.", pick("Skip. Last one.|"))
  }
}
