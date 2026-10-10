// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The line arithmetic under the in-editor review: what a hunk is, and the texts accepting or rejecting it gives */
class EditHunksTest {
  private fun text(vararg lines: String) = lines.joinToString("\n")

  @Test
  fun `identical texts have no hunks, whatever their separators`() {
    assertEquals(emptyList(), EditHunks.between("a\nb\n", "a\nb\n"))
    assertEquals(emptyList(), EditHunks.between("a\r\nb\r\n", "a\nb\n"))
    assertEquals(emptyList(), EditHunks.between("", ""))
  }

  @Test
  fun `an added line is an addition, a missing one a removal, a rewritten one a replacement`() {
    val base = text("a", "b", "c")
    assertEquals(listOf(Hunk(2, 2, 2, 3)), EditHunks.between(base, text("a", "b", "X", "c")))
    assertEquals(listOf(Hunk(1, 2, 1, 1)), EditHunks.between(base, text("a", "c")))
    assertEquals(listOf(Hunk(1, 2, 1, 2)), EditHunks.between(base, text("a", "B", "c")))
    assertEquals(
      listOf(Hunk.Kind.ADDED, Hunk.Kind.REMOVED, Hunk.Kind.CHANGED),
      listOf(Hunk(2, 2, 2, 3), Hunk(1, 2, 1, 1), Hunk(1, 2, 1, 2)).map { it.kind })
  }

  @Test
  fun `separate edits are separate hunks, top to bottom`() {
    val base = text("one", "two", "three", "four", "five", "six")
    val now = text("ONE", "two", "three", "four", "five", "SIX", "seven")
    assertEquals(listOf(Hunk(0, 1, 0, 1), Hunk(5, 6, 5, 7)), EditHunks.between(base, now))
  }

  @Test
  fun `a created file is one addition over every line`() {
    val hunks = EditHunks.between("", "x\ny\n")
    assertEquals(listOf(Hunk(0, 0, 0, 2)), hunks)
    assertEquals("x\ny\n", EditHunks.accepted("", "x\ny\n", hunks.single()))
    assertEquals("", EditHunks.rejected("", "x\ny\n", hunks.single()))
  }

  @Test
  fun `whitespace is a change, as it is for a reader`() {
    assertEquals(listOf(Hunk(1, 2, 1, 2)), EditHunks.between(text("a", "  b", "c"), text("a", "b", "c")))
  }

  @Test
  fun `a newline added at the end is a hunk over the last empty line`() {
    assertEquals(listOf(Hunk(1, 1, 1, 2)), EditHunks.between("a", "a\n"))
  }

  @Test
  fun `accepting a hunk writes its lines into the baseline and leaves the other hunks`() {
    val before = text("one", "two", "three", "four", "five", "six")
    val now = text("ONE", "two", "three", "four", "five", "SIX", "seven")
    val hunks = EditHunks.between(before, now)
    val acceptedFirst = EditHunks.accepted(before, now, hunks[0])
    assertEquals(text("ONE", "two", "three", "four", "five", "six"), acceptedFirst)
    assertEquals(listOf(Hunk(5, 6, 5, 7)), EditHunks.between(acceptedFirst, now))
  }

  @Test
  fun `rejecting a hunk writes the baseline lines back into the text and leaves the other hunks`() {
    val before = text("one", "two", "three", "four", "five", "six")
    val now = text("ONE", "two", "three", "four", "five", "SIX", "seven")
    val hunks = EditHunks.between(before, now)
    val rejectedLast = EditHunks.rejected(before, now, hunks[1])
    assertEquals(text("ONE", "two", "three", "four", "five", "six"), rejectedLast)
    assertEquals(listOf(Hunk(0, 1, 0, 1)), EditHunks.between(before, rejectedLast))
  }

  @Test
  fun `rejecting an addition at the end and a removal at the end gives the baseline back`() {
    val addition = EditHunks.between("a\nb", "a\nb\nc").single()
    assertEquals("a\nb", EditHunks.rejected("a\nb", "a\nb\nc", addition))
    val removal = EditHunks.between("a\nb\nc", "a\nb").single()
    assertEquals("a\nb\nc", EditHunks.rejected("a\nb\nc", "a\nb", removal))
    assertEquals("a\nb", EditHunks.accepted("a\nb\nc", "a\nb", removal))
  }

  @Test
  fun `accepting keeps the separators of the baseline, the document text stays LF`() {
    val before = "a\r\nb\r\nc"
    val now = "a\nB\nc"
    val hunk = EditHunks.between(before, now).single()
    assertEquals("a\r\nB\r\nc", EditHunks.accepted(before, now, hunk))
    assertEquals("a\nb\nc", EditHunks.rejected(before, now, hunk))
  }

  @Test
  fun `the removed lines of a hunk are the baseline lines it covers`() {
    val before = text("a", "b", "c", "d")
    val hunk = EditHunks.between(before, text("a", "d")).single()
    assertEquals(listOf("b", "c"), EditHunks.removedLines(before, hunk))
  }

  @Test
  fun `a hunk that does not fit the texts is refused rather than applied to the wrong lines`() {
    val result = runCatching { EditHunks.accepted("a", "a", Hunk(0, 5, 0, 1)) }
    assertTrue(result.isFailure)
  }

  @Test
  fun `the preview spells out tabs, caps the lines and counts what did not fit`() {
    val preview = EditHunks.preview(listOf("\tone", "two", "three"), cap = 2, tabSize = 4)
    assertEquals(listOf("    one", "two"), preview.lines)
    assertEquals(1, preview.hidden)
    assertEquals(0, EditHunks.preview(listOf("x"), cap = 5, tabSize = 4).hidden)
  }

  @Test
  fun `a text gets the separators of the original`() {
    assertEquals("a\r\nb", EditHunks.likeOriginal("x\r\ny", "a\nb"))
    assertEquals("a\nb", EditHunks.likeOriginal("x\ny", "a\nb"))
    assertEquals("a\nb", EditHunks.likeOriginal("", "a\nb"))
  }

  @Test
  fun `the smallest edit turns the old text into the new one`() {
    assertNull(EditHunks.minimalEdit("same", "same"))
    assertEquals(TextEdit(2, 4, "XYZ"), EditHunks.minimalEdit("abcdef", "abXYZef"))
    assertEquals(TextEdit(3, 3, "d"), EditHunks.minimalEdit("abc", "abcd"))
    assertEquals(TextEdit(0, 3, ""), EditHunks.minimalEdit("abc", ""))
    assertEquals(TextEdit(1, 1, "b"), EditHunks.minimalEdit("ac", "abc"))
  }

  @Test
  fun `the smallest edit never cuts a surrogate pair in half`() {
    val old = "x😀y"
    val new = "x😁y"
    val edit = assertNotNull(EditHunks.minimalEdit(old, new))
    assertEquals(new, old.replaceRange(edit.start, edit.end, edit.replacement))
    assertTrue(!Character.isLowSurrogate(old[edit.start]) || edit.start == 0 || !Character.isHighSurrogate(old[edit.start - 1]))
  }

  @Test
  fun `the smallest edit applied to a random text always gives the new text`() {
    val random = Random(SEED)
    repeat(RANDOM_RUNS) {
      val old = randomText(random)
      val new = randomText(random)
      val edit = EditHunks.minimalEdit(old, new)
      val applied = if (edit == null) old else old.replaceRange(edit.start, edit.end, edit.replacement)
      assertEquals(new, applied)
    }
  }

  /** Resolving hunks one by one, in any order and either way, always ends where it should: nothing is lost on the way */
  @Test
  fun `accepting every hunk in a random order reaches the current text, rejecting every hunk reaches the baseline`() {
    val random = Random(SEED)
    repeat(RANDOM_RUNS) { run ->
      val before = randomFile(random)
      val now = mutate(before, random)
      var base = before
      var steps = 0
      while (true) {
        val hunks = EditHunks.between(base, now)
        if (hunks.isEmpty()) break
        base = EditHunks.accepted(base, now, hunks[random.nextInt(hunks.size)])
        assertTrue(++steps <= MAX_STEPS, "run $run does not converge")
      }
      assertEquals(EditHunks.normalize(now), EditHunks.normalize(base), "run $run: accepting everything")

      var text = now
      steps = 0
      while (true) {
        val hunks = EditHunks.between(before, text)
        if (hunks.isEmpty()) break
        text = EditHunks.rejected(before, text, hunks[random.nextInt(hunks.size)])
        assertTrue(++steps <= MAX_STEPS, "run $run does not converge")
      }
      assertEquals(EditHunks.normalize(before), EditHunks.normalize(text), "run $run: rejecting everything")
    }
  }

  /**
   * The remaining hunks are derived, not diffed again: a new diff of the same change can cut it differently
   * (repeated lines make alignments ambiguous), and the reader would watch hunks split under their hands
   */
  @Test
  fun `resolving one hunk removes exactly that one from the list and leaves a script that still turns the baseline into the text`() {
    val random = Random(SEED)
    repeat(RANDOM_RUNS) { run ->
      val before = randomFile(random)
      val now = mutate(before, random)
      var hunks = EditHunks.between(before, now)
      var base = before
      var text = now
      // A random mix of accepts and rejects, in a random order, down to the last hunk
      while (hunks.isNotEmpty()) {
        val index = random.nextInt(hunks.size)
        val size = hunks.size
        if (random.nextBoolean()) {
          base = EditHunks.accepted(base, text, hunks[index])
          hunks = EditHunks.afterAccept(hunks, index)
        }
        else {
          text = EditHunks.rejected(base, text, hunks[index])
          hunks = EditHunks.afterReject(hunks, index)
        }
        assertEquals(size - 1, hunks.size, "run $run")
        assertTrue(isScript(base, text, hunks), "run $run: the remaining hunks no longer describe the difference")
      }
      assertEquals(EditHunks.normalize(base), EditHunks.normalize(text), "run $run: nothing is left, so the texts are equal")
    }
  }

  /** Outside the hunks the two texts are the same lines, and the hunks lie in order without touching: that is a valid edit script */
  private fun isScript(before: String, current: String, hunks: List<Hunk>): Boolean {
    val old = EditHunks.lines(before)
    val now = EditHunks.lines(current)
    var oldAt = 0
    var nowAt = 0
    for (hunk in hunks) {
      if (hunk.beforeStart < oldAt || hunk.afterStart < nowAt) return false
      if (old.subList(oldAt, hunk.beforeStart) != now.subList(nowAt, hunk.afterStart)) return false
      oldAt = hunk.beforeEnd
      nowAt = hunk.afterEnd
    }
    return old.subList(oldAt, old.size) == now.subList(nowAt, now.size)
  }

  private fun randomText(random: Random): String =
    String(CharArray(random.nextInt(0, 12)) { "ab\nc".random(random) })

  private fun randomFile(random: Random): String =
    List(random.nextInt(1, 40)) { "line ${random.nextInt(0, 15)}" }.joinToString("\n") + if (random.nextBoolean()) "\n" else ""

  /** A few random edits spread over the lines: replaced, removed and inserted ones */
  private fun mutate(text: String, random: Random): String {
    val lines = text.split('\n').toMutableList()
    repeat(random.nextInt(0, 6)) {
      val at = random.nextInt(0, lines.size + 1)
      when (random.nextInt(3)) {
        0 -> if (at < lines.size) lines[at] = "changed ${random.nextInt(0, 100)}"
        1 -> if (at < lines.size && lines.size > 1) lines.removeAt(at)
        else -> lines.add(at, "inserted ${random.nextInt(0, 100)}")
      }
    }
    return lines.joinToString("\n")
  }

  private companion object {
    const val SEED = 20261010
    const val RANDOM_RUNS = 400
    const val MAX_STEPS = 200
  }
}
