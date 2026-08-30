// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.preview

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * The preview address as a square one can point a phone at.
 *
 * Copying `http://192.168.1.42:3000` to the clipboard solves nothing: the clipboard is on the
 * COMPUTER, and the address is needed on the phone — so the address gets retyped by hand, digit by
 * digit, which is exactly the obstacle we set out to remove.
 *
 * The encoder is the platform's ZXing rather than our own: a hand-written QR encoder is several
 * hundred lines of Reed-Solomon whose only possible outcomes are «works» and «a square that some
 * phones read».
 */
object QrCode {
  /** Quiet zone in modules, as the QR spec requires — a code without it is unreliably scanned. */
  const val QUIET_ZONE_MODULES = 2

  /** Rendered side in pixels: big enough to scan across a desk, small enough for a dialog. */
  const val PREFERRED_SIZE_PX = 320

  /**
   * The module matrix, `true` where the module is dark.
   *
   * Split out from painting so that the encoding can be tested at all: a [BufferedImage] can only
   * be compared pixel-wise, while the matrix says plainly whether the code has the finder patterns
   * and the requested quiet zone.
   */
  fun matrix(text: String, sizePx: Int = PREFERRED_SIZE_PX): BitMatrix? {
    if (text.isBlank()) return null
    val hints = mapOf(
      // Medium correction: the code hangs on a screen, not on a dusty box, and a higher level
      // would only make the modules smaller for no gain.
      EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
      EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
      EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    return runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints) }.getOrNull()
  }

  /**
   * Painted black on white regardless of the IDE theme.
   *
   * A theme-coloured QR code is the one failure mode that looks fine and scans badly: cameras
   * expect dark-on-light with real contrast, and «почти белый на почти чёрном» is not that.
   */
  fun image(text: String, sizePx: Int = PREFERRED_SIZE_PX): BufferedImage? {
    val bits = matrix(text, sizePx) ?: return null
    val image = BufferedImage(bits.width, bits.height, BufferedImage.TYPE_INT_RGB)
    val dark = Color.BLACK.rgb
    val light = Color.WHITE.rgb
    for (y in 0 until bits.height) {
      for (x in 0 until bits.width) {
        image.setRGB(x, y, if (bits.get(x, y)) dark else light)
      }
    }
    return image
  }
}
