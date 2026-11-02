package app.chumo.epd42

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 取模算法：与 pengwon/epd42 网页上位机 dithering.js 保持一致。
 *   - 4gray：4 级灰度（0/85/170/255），双平面 0x10/0x13
 *   - none / bayer / floydsteinberg / Atkinson：二值（黑白），单缓冲 0x13
 */
object Dither {
    const val MODE_NONE = "none"
    const val MODE_BAYER = "bayer"
    const val MODE_FLOYD = "floydsteinberg"
    const val MODE_ATKINSON = "Atkinson"
    const val MODE_4GRAY = "4gray"

    /** 与网页上位机默认阈值一致（125）。 */
    const val THRESHOLD = 125

    data class Payload(
        val mode: String,
        val plane10: ByteArray?,
        val plane13: ByteArray?,
        val bw: ByteArray?,
        val preview: Bitmap,
    )

    private val BAYER = arrayOf(
        intArrayOf(15, 135, 45, 165),
        intArrayOf(195, 75, 225, 105),
        intArrayOf(60, 180, 30, 150),
        intArrayOf(240, 120, 210, 90),
    )

    fun build(bitmap: Bitmap, mode: String): Payload {
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val px = IntArray(n)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val work = IntArray(n) { i -> grayOf(px[i]) }

        when (mode) {
            MODE_4GRAY -> {
                val factor = 255.0 / (4 - 1)
                for (i in 0 until n) {
                    work[i] = Math.round(work[i] / factor).toInt() * 85
                }
            }
            MODE_NONE -> {
                for (i in 0 until n) work[i] = if (work[i] < THRESHOLD) 0 else 255
            }
            MODE_BAYER -> {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        val map = (work[i] + BAYER[x % 4][y % 4]) / 2
                        work[i] = if (map < THRESHOLD) 0 else 255
                    }
                }
            }
            MODE_FLOYD -> {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        val v = work[i]
                        val newPixel = if (v < 129) 0 else 255
                        val err = Math.floor((v - newPixel) / 16.0).toInt()
                        work[i] = newPixel
                        distribute(work, n, i + 1, err * 7)
                        distribute(work, n, i + w - 1, err * 3)
                        distribute(work, n, i + w, err * 5)
                        distribute(work, n, i + w + 1, err * 1)
                    }
                }
            }
            MODE_ATKINSON -> {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        val v = work[i]
                        val newPixel = if (v < THRESHOLD) 0 else 255
                        val err = Math.floor((v - newPixel) / 8.0).toInt()
                        work[i] = newPixel
                        distribute(work, n, i + 1, err)
                        distribute(work, n, i + 2, err)
                        distribute(work, n, i + w - 1, err)
                        distribute(work, n, i + w, err)
                        distribute(work, n, i + w + 1, err)
                        distribute(work, n, i + 2 * w, err)
                    }
                }
            }
            else -> {
                // 未知模式按 4 级灰度兜底
                val factor = 255.0 / (4 - 1)
                for (i in 0 until n) work[i] = Math.round(work[i] / factor).toInt() * 85
            }
        }

        // 预览位图（4gray 为 0/85/170/255，二值为 0/255）
        val preview = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPx = IntArray(n)
        for (i in 0 until n) {
            val v = work[i].coerceIn(0, 255)
            outPx[i] = Color.rgb(v, v, v)
        }
        preview.setPixels(outPx, 0, w, 0, 0, w, h)

        return if (mode == MODE_4GRAY) {
            // canvas2gray 映射：white(1,1) gray1(1,0) gray2(0,1) black(0,0)
            val bytes = n / 8
            val p10 = ByteArray(bytes)
            val p13 = ByteArray(bytes)
            var bit = 0
            var b10 = 0
            var b13 = 0
            for (i in 0 until n) {
                val lvl = work[i] / 85
                val bit10 = if (lvl >= 2) 1 else 0
                val bit13 = if (lvl == 1 || lvl == 3) 1 else 0
                b10 = (b10 shl 1) or bit10
                b13 = (b13 shl 1) or bit13
                bit++
                if (bit == 8) {
                    p10[i / 8] = b10.toByte()
                    p13[i / 8] = b13.toByte()
                    bit = 0
                    b10 = 0
                    b13 = 0
                }
            }
            Payload(mode, p10, p13, null, preview)
        } else {
            // canvas2bytes('bw')：黑=0，其余=1，MSB 优先
            val bytes = n / 8
            val bw = ByteArray(bytes)
            var bit = 0
            var b = 0
            for (i in 0 until n) {
                val bitV = if (work[i] == 0) 0 else 1
                b = (b shl 1) or bitV
                bit++
                if (bit == 8) {
                    bw[i / 8] = b.toByte()
                    bit = 0
                    b = 0
                }
            }
            Payload(mode, null, null, bw, preview)
        }
    }

    private fun distribute(work: IntArray, n: Int, index: Int, value: Int) {
        if (index in 0 until n) work[index] += value
    }

    private fun grayOf(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 0.299 + g * 0.587 + b * 0.114).toInt()
    }
}
