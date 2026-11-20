// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.watch

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The frames as one picture: a grid with a time code on every cell.
 *
 * Twelve attachments cost twelve images' worth of tokens and arrive in an order the model has to
 * remember; one sheet costs one image, and the time is printed on the frame it belongs to. The grid
 * is fitted to the number of frames — a fixed 4x3 sheet for one frame writes eleven empty cells
 * (checked on ffmpeg 6.0 and 8.1, 03.11.2026).
 *
 * Drawn in Java rather than with ffmpeg's `tile`/`drawtext`: a static ffmpeg build may lack both, and
 * `drawtext` without fontconfig fails on a missing font. Here the only inputs are the images already
 * extracted and a logical font every JRE has.
 */
object ContactSheet {
  const val BACKGROUND = 0x202020
  const val MARGIN = 2
  const val PADDING = 2

  /** Longest side of the sheet: a model resizes larger pictures anyway, and the saved bytes are ours. */
  const val MAX_SIDE = 2048

  /** Columns and rows for [count] frames, with as few empty cells as the shape allows. */
  fun grid(count: Int): Pair<Int, Int> {
    val n = count.coerceAtLeast(1)
    val columns = when {
      n <= 1 -> 1
      n <= 2 -> 2
      n <= 4 -> 2
      n <= 6 -> 3
      n <= 9 -> 3
      n <= 12 -> 4
      else -> 4
    }
    return columns to (n + columns - 1) / columns
  }

  /**
   * Writes the sheet to [out] and returns it, or null when a frame cannot be read or the file cannot
   * be written — the caller then sends the frames separately, which is still a working answer.
   */
  fun compose(frames: List<Path>, seconds: List<Double>, out: Path): Path? {
    val images = frames.mapNotNull { runCatching { ImageIO.read(it.toFile()) }.getOrNull() }
    if (images.isEmpty()) return null
    val (columns, rows) = grid(images.size)
    val cellWidth = images.maxOf { it.width }
    val cellHeight = images.maxOf { it.height }
    val rawWidth = MARGIN * 2 + columns * cellWidth + (columns - 1) * PADDING
    val rawHeight = MARGIN * 2 + rows * cellHeight + (rows - 1) * PADDING
    val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(rawWidth, rawHeight))
    val width = (rawWidth * scale).toInt().coerceAtLeast(1)
    val height = (rawHeight * scale).toInt().coerceAtLeast(1)
    val font = Font(Font.SANS_SERIF, Font.PLAIN, (cellHeight * scale / 22).toInt().coerceAtLeast(12))
    return runCatching {
      val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
      val g = sheet.createGraphics()
      try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(BACKGROUND)
        g.fillRect(0, 0, width, height)
        g.font = font
        val cw = (cellWidth * scale).toInt()
        val ch = (cellHeight * scale).toInt()
        val pad = (PADDING * scale).toInt().coerceAtLeast(1)
        val margin = (MARGIN * scale).toInt()
        for ((index, image) in images.withIndex()) {
          val column = index % columns
          val row = index / columns
          val x = margin + column * (cw + pad)
          val y = margin + row * (ch + pad)
          g.drawImage(image, x, y, cw, ch, null)
          val label = seconds.getOrNull(index)?.let { Subtitles.stamp(it) } ?: continue
          stamp(g, label, x, y, cw, ch, font)
        }
      }
      finally {
        g.dispose()
      }
      writeJpeg(sheet, out)
      out.takeIf { Files.isRegularFile(it) }
    }.getOrNull()
  }

  private fun stamp(g: java.awt.Graphics2D, label: String, x: Int, y: Int, width: Int, height: Int, font: Font) {
    val metrics = g.fontMetrics
    val textWidth = metrics.stringWidth(label)
    val textHeight = metrics.height
    val pad = (font.size / 3).coerceAtLeast(2)
    val boxX = x + 4
    val boxY = y + height - textHeight - pad * 2 - 4
    g.color = Color(0, 0, 0, 170)
    g.fillRoundRect(boxX, boxY, textWidth + pad * 2, textHeight + pad, pad, pad)
    g.color = Color.WHITE
    g.drawString(label, boxX + pad, boxY + pad + metrics.ascent)
  }

  private fun writeJpeg(image: BufferedImage, out: Path) {
    Files.createDirectories(out.parent)
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next() ?: error("no JPEG writer")
    ImageIO.createImageOutputStream(out.toFile()).use { stream ->
      writer.output = stream
      val params = writer.defaultWriteParam
      if (params.canWriteCompressed()) {
        params.compressionMode = ImageWriteParam.MODE_EXPLICIT
        params.compressionQuality = 0.85f
      }
      writer.write(null, IIOImage(image, null, null), params)
    }
    writer.dispose()
  }
}
