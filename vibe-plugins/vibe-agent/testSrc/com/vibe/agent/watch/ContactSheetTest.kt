// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.watch

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ContactSheetTest {
  @Test
  fun `the grid is fitted to the number of frames`() {
    assertEquals(1 to 1, ContactSheet.grid(1))
    assertEquals(2 to 1, ContactSheet.grid(2))
    assertEquals(3 to 2, ContactSheet.grid(5))
    assertEquals(4 to 3, ContactSheet.grid(12))
    assertEquals(1 to 1, ContactSheet.grid(0))
  }

  @Test
  fun `a one-frame video gets a one-cell sheet, not eleven blanks`() {
    val dir = Files.createTempDirectory("sheet")
    try {
      val frame = dir.resolve("frame0001.jpg")
      ImageIO.write(BufferedImage(10, 8, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply { color = Color.RED; fillRect(0, 0, 10, 8); dispose() }
      }, "jpg", frame.toFile())
      val sheet = ContactSheet.compose(listOf(frame), listOf(1.0), dir.resolve("sheet.jpg"))
      assertNotNull(sheet)
      val read = ImageIO.read(sheet.toFile())
      // margin 2 + one 10 px cell + margin 2; margin 2 + one 8 px cell + margin 2
      assertEquals(14, read.width)
      assertEquals(12, read.height)
    }
    finally {
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `five frames make a 3x2 sheet with the background between cells`() {
    val dir = Files.createTempDirectory("sheet")
    try {
      val frames = (0 until 5).map { index ->
        val file = dir.resolve("frame%04d.jpg".format(index))
        val image = BufferedImage(10, 8, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, 10, 8)
        g.dispose()
        ImageIO.write(image, "jpg", file.toFile())
        file
      }
      val sheet = ContactSheet.compose(frames, frames.indices.map { it * 10.0 }, dir.resolve("sheet.jpg"))
      assertNotNull(sheet)
      val read = ImageIO.read(sheet.toFile())
      // 3 columns x 10 + 2 gaps x 2 + 2 margins x 2 = 38; 2 rows x 8 + 1 gap x 2 + 4 = 22.
      assertEquals(38, read.width)
      assertEquals(22, read.height)
    }
    finally {
      dir.toFile().deleteRecursively()
    }
  }
}
