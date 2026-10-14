// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bubble's outline: a rectangle with a radius of its own at each corner
 *
 * What is asserted is where the outline is and is not, by points
 * The tail corner (radius 4) must cover a point that the big corners (radius 14) leave outside
 * That difference is what makes the bubble look like a bubble
 */
class ChatBubbleContourTest {
  private val tail = BubbleRadii(14, 14, 4, 14)

  @Test
  fun `the outline fills its box`() {
    val bounds = ChatBubbleContour.path(0f, 0f, 200f, 60f, tail).bounds2D
    assertEquals(0.0, bounds.minX, 0.01)
    assertEquals(0.0, bounds.minY, 0.01)
    assertEquals(200.0, bounds.maxX, 0.01)
    assertEquals(60.0, bounds.maxY, 0.01)
  }

  @Test
  fun `the middle and the straight edges are inside`() {
    val path = ChatBubbleContour.path(0f, 0f, 200f, 60f, tail)
    assertTrue(path.contains(100.0, 30.0))
    assertTrue(path.contains(100.0, 0.5))
    assertTrue(path.contains(0.5, 30.0))
    assertTrue(path.contains(199.5, 30.0))
    assertTrue(path.contains(100.0, 59.5))
  }

  @Test
  fun `the three big corners are cut away`() {
    val path = ChatBubbleContour.path(0f, 0f, 200f, 60f, tail)
    assertFalse(path.contains(1.0, 1.0), "top left")
    assertFalse(path.contains(199.0, 1.0), "top right")
    assertFalse(path.contains(1.0, 59.0), "bottom left")
  }

  @Test
  fun `the tail corner is nearly square, so it keeps a point the big corners lose`() {
    val path = ChatBubbleContour.path(0f, 0f, 200f, 60f, tail)
    // Two pixels in from the corner: inside the radius-4 curve, outside a radius-14 one
    assertTrue(path.contains(198.0, 58.0), "bottom right")
    assertFalse(path.contains(198.0, 2.0), "top right at the same offset")
    assertFalse(path.contains(2.0, 58.0), "bottom left at the same offset")
  }

  @Test
  fun `a corner is a quarter circle, not a diagonal`() {
    val path = ChatBubbleContour.path(0f, 0f, 200f, 60f, tail)
    // On a circle of radius 14 the point at 45 degrees is 14 - 14 / sqrt(2) = 4.1 from both edges
    assertTrue(path.contains(4.5, 4.5))
    assertFalse(path.contains(3.5, 3.5))
  }

  @Test
  fun `a box moved off the origin carries its outline with it`() {
    val path = ChatBubbleContour.path(10f, 20f, 100f, 40f, tail)
    assertTrue(path.contains(60.0, 40.0))
    assertFalse(path.contains(5.0, 40.0))
    assertEquals(10.0, path.bounds2D.minX, 0.01)
    assertEquals(20.0, path.bounds2D.minY, 0.01)
  }

  @Test
  fun `radii too large for a small box are scaled down together and never overlap`() {
    // 20 x 10 cannot carry two radius-14 corners on a side: they shrink to fit
    val path = ChatBubbleContour.path(0f, 0f, 20f, 10f, BubbleRadii(14, 14, 14, 14))
    assertTrue(path.contains(10.0, 5.0))
    assertEquals(20.0, path.bounds2D.width, 0.01)
    assertEquals(10.0, path.bounds2D.height, 0.01)
  }

  @Test
  fun `a plate with equal radii is symmetric`() {
    val path = ChatBubbleContour.path(0f, 0f, 100f, 40f, BubbleRadii(8, 8, 8, 8))
    for ((x, y) in listOf(1.0 to 1.0, 99.0 to 1.0, 1.0 to 39.0, 99.0 to 39.0)) assertFalse(path.contains(x, y), "corner $x,$y")
    assertTrue(path.contains(50.0, 20.0))
  }

  @Test
  fun `the inset contour lies inside the outer one by the stroke's half width`() {
    val outer = ChatBubbleContour.path(0f, 0f, 200f, 60f, tail).bounds2D
    val inner = ChatBubbleContour.inset(200f, 60f, tail, 0.5f).bounds2D
    assertEquals(outer.minX + 0.5, inner.minX, 0.01)
    assertEquals(outer.maxX - 0.5, inner.maxX, 0.01)
    assertEquals(outer.minY + 0.5, inner.minY, 0.01)
    assertEquals(outer.maxY - 0.5, inner.maxY, 0.01)
  }

  @Test
  fun `a zero radius is a square corner`() {
    val path = ChatBubbleContour.path(0f, 0f, 100f, 40f, BubbleRadii(0, 0, 0, 0))
    assertTrue(path.contains(0.5, 0.5))
    assertTrue(path.contains(99.5, 39.5))
  }
}
