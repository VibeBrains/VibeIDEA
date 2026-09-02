// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesignDiffTest {
  private fun finding(
    rule: String,
    selector: String = ".btn",
    evidence: String = "2.9:1",
    viewport: Viewport = Viewport.DESKTOP,
    cls: RuleClass = RuleClass.FLOOR,
  ) = Finding(rule, Severity.ERROR, "сообщение $evidence", "почему", selector, evidence, cls, viewport)

  private val labels = object : DesignDiff.Labels {
    override fun appeared(count: Int) = "появилось $count"
    override fun fixed(count: Int) = "исправлено $count"
  }

  @Test
  fun `the question worth asking is what I just broke`() {
    val before = listOf(finding("contrast-text"), finding("tap-target-too-small", ".link"))
    val after = listOf(finding("contrast-text"), finding("text-too-small", ".hint"))
    val result = DesignDiff.compare(before, after)
    assertEquals(listOf("text-too-small"), result.appeared.map { it.rule })
    assertEquals(listOf("tap-target-too-small"), result.fixed.map { it.rule })
    assertEquals(listOf("contrast-text"), result.remained.map { it.rule })
    assertTrue(result.changed)
  }

  @Test
  fun `a drifted measurement is the same problem, not a new one`() {
    // The message carries the measured value, and contrast that moved from 2.9 to 2.7 is the same
    // problem. Counting it as new would fill «появилось» with noise on every re-measure.
    val before = listOf(finding("contrast-text", evidence = "2.9:1"))
    val after = listOf(finding("contrast-text", evidence = "2.7:1"))
    val result = DesignDiff.compare(before, after)
    assertTrue(result.appeared.isEmpty())
    assertTrue(result.fixed.isEmpty())
    assertEquals(1, result.remained.size)
    assertFalse(result.changed)
  }

  @Test
  fun `the same rule on another element is another finding`() {
    val result = DesignDiff.compare(listOf(finding("contrast-text", ".btn")), listOf(finding("contrast-text", ".title")))
    assertEquals(1, result.appeared.size)
    assertEquals(1, result.fixed.size)
  }

  @Test
  fun `the same rule in another viewport is another finding`() {
    // A page can be fine on the desktop and broken on a phone; merging the two would hide exactly
    // the case the mobile viewport was added for.
    val desktop = finding("tap-target-too-small", viewport = Viewport.DESKTOP)
    val phone = finding("tap-target-too-small", viewport = Viewport.MOBILE)
    assertEquals(1, DesignDiff.compare(listOf(desktop), listOf(desktop, phone)).appeared.size)
  }

  @Test
  fun `a first measurement is not a regression`() {
    // Calling it one would teach people that the word means nothing.
    val result = DesignDiff.compare(null, listOf(finding("contrast-text")))
    assertTrue(result.appeared.isEmpty())
    assertEquals(1, result.remained.size)
    assertFalse(result.changed)
  }

  @Test
  fun `only new floor findings count as a regression`() {
    val result = DesignDiff.compare(
      emptyList(),
      listOf(finding("contrast-text", cls = RuleClass.FLOOR), finding("purple-palette", ".hero", cls = RuleClass.STYLE)),
    )
    assertEquals(2, result.appeared.size)
    assertEquals(listOf("contrast-text"), result.floorAppeared.map { it.rule })
  }

  @Test
  fun `no change says nothing at all`() {
    // A measurement that produced the same list is the normal case; announcing it every time
    // trains people to skip the line that matters.
    val same = listOf(finding("contrast-text"))
    assertNull(DesignDiff.summary(DesignDiff.compare(same, same), labels))
    assertEquals("появилось 1", DesignDiff.summary(DesignDiff.compare(emptyList(), same), labels))
    assertEquals("исправлено 1", DesignDiff.summary(DesignDiff.compare(same, emptyList()), labels))
  }
}
