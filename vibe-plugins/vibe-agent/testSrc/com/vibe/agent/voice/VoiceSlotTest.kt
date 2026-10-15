// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The dictation writes into an input the person also writes in, and never makes copies of its own words */
class VoiceSlotTest {
  /** The input and its caret, changed the way the composer changes them */
  private class Input(var text: String, var caret: Int = text.length) {
    fun apply(edit: VoiceSlot.Edit?) {
      edit ?: return
      val before = caret
      text = text.substring(0, edit.from) + edit.text + text.substring(edit.to)
      val delta = edit.text.length - (edit.to - edit.from)
      caret = when {
        before >= edit.to -> before + delta
        before >= edit.from -> edit.from + edit.text.length
        else -> before
      }
    }
  }

  private val slot = VoiceSlot()

  private fun Input.draft(text: String) = apply(slot.draft(text, this.text, caret))
  private fun Input.phrase(text: String) = apply(slot.phrase(text, this.text, caret))
  private fun Input.close(keep: Boolean) = apply(slot.close(keep, text))

  @Test
  fun `drafts replace each other and the phrase replaces them, in an empty input`() {
    val input = Input("")
    input.draft("при")
    input.draft("привет ми")
    assertEquals("привет ми", input.text)
    input.phrase("привет мир")
    input.draft("как")
    assertEquals("привет мир как", input.text)
    input.phrase("как дела")
    input.close(keep = true)
    assertEquals("привет мир как дела", input.text)
    assertEquals(input.text.length, input.caret)
  }

  @Test
  fun `after text on the same line the words are set apart by a space, after a space or a new line they are not`() {
    Input("запусти").also { it.draft("тесты"); assertEquals("запусти тесты", it.text) }
    VoiceSlot().let { other ->
      val input = Input("запусти ")
      input.apply(other.draft("тесты", input.text, input.caret))
      assertEquals("запусти тесты", input.text)
    }
    VoiceSlot().let { other ->
      val input = Input("строка\n")
      input.apply(other.draft("тесты", input.text, input.caret))
      assertEquals("строка\nтесты", input.text)
    }
  }

  @Test
  fun `text the person types after the dictated words survives every draft, and so does their caret`() {
    val input = Input("")
    input.draft("открой файл")
    input.text += " — срочно"
    input.caret = input.text.length
    input.draft("открой файл настроек")
    assertEquals("открой файл настроек — срочно", input.text)
    assertEquals(input.text.length, input.caret, "the caret of the person stays at the end of their own words")
  }

  @Test
  fun `text typed before the dictated words moves them, and the next draft finds them where they went`() {
    val input = Input("")
    input.draft("добавь кнопку")
    input.text = "сначала: " + input.text
    input.caret = 0
    input.draft("добавь кнопку справа")
    assertEquals("сначала: добавь кнопку справа", input.text)
  }

  @Test
  fun `words the person deleted are not written back, and writing goes on at the caret without a copy`() {
    val input = Input("")
    input.phrase("первая фраза")
    input.draft("вто")
    input.text = "своё"
    input.caret = input.text.length
    input.draft("вторая")
    assertEquals("своё вторая", input.text)
    input.phrase("вторая фраза")
    assertEquals("своё вторая фраза", input.text)
  }

  @Test
  fun `the stop keeps the phrases and drops a draft that never became one`() {
    val input = Input("")
    input.phrase("готово")
    input.draft("ещё")
    input.close(keep = true)
    assertEquals("готово", input.text)
  }

  @Test
  fun `a cancel takes out everything the dictation wrote and nothing the person did`() {
    val input = Input("моё ")
    input.phrase("первое")
    input.draft("втор")
    input.close(keep = false)
    assertEquals("моё ", input.text)
  }

  @Test
  fun `the input as it may be sent has no draft in it, and keeps the phrases`() {
    val input = Input("задача:")
    input.phrase("собери проект")
    input.draft("и запу")
    assertEquals("задача: собери проект и запу", input.text)
    assertEquals("задача: собери проект", slot.withoutDraft(input.text))
    input.phrase("и запусти тесты")
    assertEquals(input.text, slot.withoutDraft(input.text))
  }

  @Test
  fun `the same draft twice is no edit, and a closed slot writes nothing`() {
    val input = Input("")
    input.draft("раз")
    assertNull(slot.draft("раз", input.text, input.caret))
    input.close(keep = true)
    assertNull(slot.close(keep = false, input.text), "a second close finds nothing of its own")
    assertEquals("", input.text, "a draft that never became a phrase goes at the stop")
  }
}
