package dev.housecontrol.android.core

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.util.Base64

/** QR codes for the Smart Life login, which the Smart Life app has to scan. */
object QrCode {
    /** Black modules on white, `scale` pixels each, with the standard quiet zone. */
    fun bitmap(text: String, scale: Int = 12): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = matrix.width * scale
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x / scale, y / scale)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    fun png(text: String, scale: Int = 12): ByteArray =
        ByteArrayOutputStream().also { bitmap(text, scale).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    fun dataUrl(text: String): String = "data:image/png;base64," + Base64.getEncoder().encodeToString(png(text, 10))
}
