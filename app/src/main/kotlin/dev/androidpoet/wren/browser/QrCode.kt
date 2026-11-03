/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a URL as a QR code so it can be handed to another device by pointing a camera at the
 * screen - the most private way to move a link between devices, since it never touches a network.
 */
object QrCode {

  fun encode(text: String, sizePx: Int): Bitmap? = runCatching {
    val hints = mapOf(
      EncodeHintType.MARGIN to 1,
      EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
      for (y in 0 until sizePx) {
        bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
      }
    }
    bitmap
  }.getOrNull()
}
