package com.lotuswallet.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 二维码读写。
 *
 * 编码用 ZXing 的 [QRCodeWriter]，解码用 [MultiFormatReader] —— 全本地、无网络、无 Google 依赖。
 */
object QrCode {

    private const val DEFAULT_SIZE = 720

    /** 生成二维码位图。内容过长或非法时返回 null（由 UI 回退为纯文本展示）。 */
    fun encode(text: String, sizePx: Int = DEFAULT_SIZE, marginModules: Int = 1): Bitmap? {
        if (text.isEmpty()) return null
        return try {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(
                com.google.zxing.EncodeHintType.MARGIN to marginModules,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
            ))
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 从任意位图解码（相册选图 / 截图二维码）。失败返回 null。 */
    fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        return tryDecode(source)
    }

    /** 从 YUV_420_888 的 Y 平面解码（相机预览帧）。 */
    fun decodeYuv(data: ByteArray, dataWidth: Int, dataHeight: Int, width: Int, height: Int): String? {
        if (dataWidth <= 0 || dataHeight <= 0) return null
        val source = PlanarYUVLuminanceSource(
            data, dataWidth, dataHeight, 0, 0, width.coerceAtMost(dataWidth), height.coerceAtMost(dataHeight), false,
        )
        return tryDecode(source)
    }

    /**
     * 依次尝试多种二值化与旋转变体。
     *
     * 手机相机出来的帧可能带旋转、反相、光照不均，单次 decode 命中率很低，
     * 这里把常见的几组组合都试一遍，命中即返回。
     */
    private fun tryDecode(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        }
        val variants = buildList {
            add(source)
            runCatching { add(source.rotateCounterClockwise()) }
            runCatching { add(source.rotateCounterClockwise().rotateCounterClockwise()) }
            runCatching { add(source.invert()) }
        }
        for (variant in variants) {
            for (binarizer in listOf(HybridBinarizer(variant), GlobalHistogramBinarizer(variant))) {
                val result = try {
                    reader.decodeWithState(BinaryBitmap(binarizer))
                } catch (_: Throwable) {
                    null
                } finally {
                    reader.reset()
                }
                if (result != null) return result.text
            }
        }
        return null
    }

    /** 位图 → Compose ImageBitmap。 */
    fun Bitmap.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap = asImageBitmap()
}
