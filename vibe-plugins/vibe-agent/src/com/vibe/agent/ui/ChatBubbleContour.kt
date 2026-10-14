// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import java.awt.geom.Path2D
import kotlin.math.max
import kotlin.math.min

/**
 * The outline of the user's bubble: a rectangle with a radius of its own at every corner
 *
 * `fillRoundRect` knows one radius for all four corners, and the bubble's tail is exactly a smaller radius at the bottom right
 * So the outline is a path
 * Fill and stroke use the same contour, which keeps the border on the edge
 */
internal object ChatBubbleContour {
  /** Control-point distance that makes a cubic Bezier follow a quarter circle (error under 0.03%) */
  private const val QUARTER_CIRCLE = 0.5522847498f

  /**
   * The path of the box ([x], [y], [width], [height]) with the given corner radii
   *
   * Radii that would not fit are scaled down together, the way CSS does it: a side never carries two corners longer than itself
   */
  fun path(x: Float, y: Float, width: Float, height: Float, radii: BubbleRadii): Path2D.Float =
    build(x, y, width, height, radii.topLeft.toFloat(), radii.topRight.toFloat(), radii.bottomRight.toFloat(), radii.bottomLeft.toFloat())

  /** The contour of a box of [width] x [height] pulled in by [inset] on every side, for a stroke that stays inside it */
  fun inset(width: Float, height: Float, radii: BubbleRadii, inset: Float): Path2D.Float = build(
    inset, inset, width - 2 * inset, height - 2 * inset,
    max(0f, radii.topLeft - inset), max(0f, radii.topRight - inset),
    max(0f, radii.bottomRight - inset), max(0f, radii.bottomLeft - inset),
  )

  private fun build(x: Float, y: Float, width: Float, height: Float, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float): Path2D.Float {
    val fit = fitFactor(width, height, topLeft, topRight, bottomRight, bottomLeft)
    val tl = topLeft * fit
    val tr = topRight * fit
    val br = bottomRight * fit
    val bl = bottomLeft * fit
    val right = x + width
    val bottom = y + height
    val path = Path2D.Float()
    path.moveTo(x + tl, y)
    path.lineTo(right - tr, y)
    corner(path, right - tr, y, right, y + tr, tr, horizontalFirst = true)
    path.lineTo(right, bottom - br)
    corner(path, right, bottom - br, right - br, bottom, br, horizontalFirst = false)
    path.lineTo(x + bl, bottom)
    corner(path, x + bl, bottom, x, bottom - bl, bl, horizontalFirst = true)
    path.lineTo(x, y + tl)
    corner(path, x, y + tl, x + tl, y, tl, horizontalFirst = false)
    path.closePath()
    return path
  }

  private fun fitFactor(width: Float, height: Float, tl: Float, tr: Float, br: Float, bl: Float): Float {
    fun side(length: Float, a: Float, b: Float) = if (a + b > length && a + b > 0f) length / (a + b) else 1f
    return min(
      min(side(width, tl, tr), side(width, bl, br)),
      min(side(height, tl, bl), side(height, tr, br)),
    ).coerceIn(0f, 1f)
  }

  /** One quarter of a circle from (x0, y0) to (x1, y1); the first leg runs along x when [horizontalFirst] */
  private fun corner(path: Path2D.Float, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, horizontalFirst: Boolean) {
    if (radius <= 0f) {
      path.lineTo(x1, y1)
      return
    }
    val k = radius * QUARTER_CIRCLE
    val dx = if (x1 > x0) 1f else -1f
    val dy = if (y1 > y0) 1f else -1f
    if (horizontalFirst) path.curveTo(x0 + dx * k, y0, x1, y1 - dy * k, x1, y1)
    else path.curveTo(x0, y0 + dy * k, x1 - dx * k, y1, x1, y1)
  }
}
