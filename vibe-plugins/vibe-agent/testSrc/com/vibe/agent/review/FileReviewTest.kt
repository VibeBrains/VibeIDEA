// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reader's place in a review, walked the way a person walks it: buttons on hunks, keys, steps, the agent writing meanwhile
 * No editor here: the model is the whole of the rule, the editor only shows it
 */
class FileReviewTest {
  /** Thirteen lines; the even ones up to the tenth are rewritten, so there are six hunks with untouched lines between them */
  private val base = (0..12).map { "line$it" }
  private val before = base.joinToString("\n")
  private val current = base.mapIndexed { i, line -> if (i % 2 == 0 && i <= 10) "$line changed" else line }.joinToString("\n")

  /** The first changed line of the hunk the reader is on, which is how a person tells which hunk it is */
  private fun FileReview.here(): String = EditHunks.lines(current)[hunks[place!!].afterStart]

  @Test
  fun `a review starts on the first hunk`() {
    val review = FileReview(before, current)
    assertEquals(6, review.size)
    assertEquals(0, review.place)
    assertEquals("line0 changed", review.here())
  }

  @Test
  fun `six hunks - the first by the button, the next ones by the keyboard - the counter goes without gaps`() {
    val review = FileReview(before, current)
    val seen = ArrayList<String>()
    val counter = ArrayList<String>()
    fun note() { counter.add("${review.place!! + 1} of ${review.size}") }

    note()
    // The button on the first hunk resolves hunk 0 and says nothing about the place
    seen.add(review.here())
    assertTrue(review.accept(0) != null)
    note()
    // The key resolves the current hunk and does NOT step once more: the list has already moved
    repeat(3) {
      seen.add(review.here())
      assertTrue(review.acceptCurrent() != null)
      note()
    }
    seen.add(review.here())
    assertTrue(review.rejectCurrent() != null)
    note()
    seen.add(review.here())
    assertTrue(review.acceptCurrent() != null)

    assertEquals(
      listOf("line0 changed", "line2 changed", "line4 changed", "line6 changed", "line8 changed", "line10 changed"),
      seen, "every hunk was seen once, in order, none skipped")
    assertEquals(listOf("1 of 6", "1 of 5", "1 of 4", "1 of 3", "1 of 2", "1 of 1"), counter)
    assertEquals(0, review.size)
    assertNull(review.place)
  }

  @Test
  fun `the button on a hunk other than the current one puts the reader where they clicked`() {
    val review = FileReview(before, current)
    assertEquals("line0 changed", review.here())
    review.accept(3)
    assertEquals(5, review.size)
    assertEquals(3, review.place)
    assertEquals("line8 changed", review.here(), "the hunk that followed the resolved one, not the one that was current")
  }

  @Test
  fun `the last hunk resolved leaves the reader on the one before it`() {
    val review = FileReview(before, current)
    review.reject(5)
    assertEquals(4, review.place)
    assertEquals("line8 changed", review.here())
    assertEquals(5, review.size)
  }

  @Test
  fun `after a step with the keys the key resolves the hunk the reader stepped to`() {
    val review = FileReview(before, current)
    review.step(forward = true)
    review.step(forward = true)
    assertEquals("line4 changed", review.here())
    review.acceptCurrent()
    assertEquals(2, review.place)
    assertEquals("line6 changed", review.here())
  }

  @Test
  fun `steps go round the end of the list`() {
    val review = FileReview(before, current)
    review.step(forward = false)
    assertEquals(5, review.place)
    review.step(forward = true)
    assertEquals(0, review.place)
  }

  @Test
  fun `accepting everything leaves a baseline equal to the text, rejecting everything leaves a text equal to the baseline`() {
    val accepting = FileReview(before, current)
    while (accepting.size > 0) accepting.acceptCurrent()
    assertEquals(current, accepting.before)

    val rejecting = FileReview(before, current)
    while (rejecting.size > 0) rejecting.rejectCurrent()
    assertEquals(before, rejecting.current)
  }

  @Test
  fun `the text a resolve returns is what the document and the journal echo, and the echo changes nothing`() {
    val review = FileReview(before, current)
    review.step(forward = true)
    val newText = review.reject(1)!!
    assertEquals(1, review.place)
    assertFalse(review.updateCurrent(newText), "the echo of our own write is no change")
    assertEquals(1, review.place)
    val newBefore = review.accept(0)!!
    assertFalse(review.updateBefore(newBefore))
    assertEquals(0, review.place)
    assertEquals(4, review.size)
  }

  @Test
  fun `a person typing keeps the place by number, and clamps it when hunks disappear`() {
    val review = FileReview(before, current)
    review.select(4)
    // A person puts the first five hunks back by hand: one hunk is left, the place cannot stay at 4
    val lines = EditHunks.lines(current).toMutableList()
    base.indices.filter { it < 9 }.forEach { lines[it] = base[it] }
    assertTrue(review.updateCurrent(lines.joinToString("\n")))
    assertEquals(1, review.size)
    assertEquals(0, review.place)
  }

  @Test
  fun `new edits from the agent start the review over from the first hunk`() {
    val review = FileReview(before, current)
    review.select(3)
    assertTrue(review.updateCurrent(current + "\nappended by the agent"))
    assertEquals(3, review.place, "an edit that is not a resolve keeps the place")
    review.restart()
    assertEquals(0, review.place)
  }

  @Test
  fun `a file with nothing to review has no place, and one that gets hunks starts at the first`() {
    val review = FileReview("same", "same")
    assertNull(review.place)
    assertNull(review.acceptCurrent())
    assertNull(review.accept(0))
    review.updateCurrent("different")
    assertEquals(0, review.place)
  }

  @Test
  fun `a created file is one hunk, so accepting it settles the file`() {
    val review = FileReview("", "x\ny\n")
    assertEquals(1, review.size)
    assertEquals("x\ny\n", review.acceptCurrent())
    assertEquals(0, review.size)
    assertNull(review.place)
  }
}
