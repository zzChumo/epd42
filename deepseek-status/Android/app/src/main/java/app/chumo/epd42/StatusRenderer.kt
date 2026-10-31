package app.chumo.epd42

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** 400×300、4 级灰阶（0/85/170/255）渲染，并编码为 EPD_4in2 双平面。 */
object StatusRenderer {
    const val WIDTH = 400
    const val HEIGHT = 300

    private val LEVELS = intArrayOf(0, 85, 170, 255)

    /** 随包附带的中文粗体（系统 Noto 字重被裁剪，墨屏上会太细）。 */
    private var boldFont: Typeface? = null

    fun loadFont(context: Context) {
        if (boldFont == null) {
            boldFont = runCatching {
                Typeface.createFromAsset(context.assets, "fonts/status-bold.otf")
            }.getOrNull()
        }
    }
    private val COLORS = intArrayOf(Color.rgb(0, 0, 0), Color.rgb(85, 85, 85), Color.rgb(170, 170, 170), Color.WHITE)

    fun render(
        balance: Double,
        todayUsed: Double,
        refreshed: String,
        peak: Boolean,
        windowText: String,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(COLORS[3])

        val bold = boldFont ?: Typeface.create("sans-serif", Typeface.BOLD)
        val pad = 18f
        val black = COLORS[0]

        // 字重分三档：
        //  Main  = 梁文峰/谷、金额：系统粗体（保持原字重）
        //  Small = 余额/今日使用 标签：随包粗体 + 轻微补笔厚（墨屏可读）
        //  Thin  = 顶部计费时段、峰谷窗口、底部刷新时间：系统常规（避免抢主内容焦点）
        val systemBold = Typeface.create("sans-serif", Typeface.BOLD)
        val systemRegular = Typeface.create("sans-serif", Typeface.NORMAL)

        fun paint(size: Float, weight: String = "small", align: Paint.Align = Paint.Align.LEFT): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = black
                textSize = size
                textAlign = align
                when (weight) {
                    "main" -> {
                        typeface = systemBold
                        style = Paint.Style.FILL
                    }
                    "thin" -> {
                        typeface = systemRegular
                        style = Paint.Style.FILL
                    }
                    else -> {
                        typeface = bold
                        style = Paint.Style.FILL_AND_STROKE
                        strokeWidth = (size * 0.018f).coerceIn(0.25f, 1.0f)
                        strokeJoin = Paint.Join.ROUND
                        strokeCap = Paint.Cap.ROUND
                    }
                }
            }

        // 第一行
        canvas.drawText("DeepSeek 计费时段", pad, 18f + 15f, paint(15f, weight = "thin"))
        val name = if (peak) "梁文峰" else "梁文谷"
        canvas.drawText(name, pad, 48f + 58f, paint(58f, weight = "main"))
        canvas.drawText(windowText, WIDTH - pad, 22f + 14f, paint(14f, weight = "thin", align = Paint.Align.RIGHT))

        // 分隔线
        val linePaint = Paint().apply {
            color = COLORS[2]
            strokeWidth = 1f
            isAntiAlias = true
        }
        canvas.drawLine(pad, 128f, WIDTH - pad, 128f, linePaint)

        // 第二行：余额
        canvas.drawText("余额", pad, 150f + 21f, paint(21f))
        canvas.drawText(
            String.format(java.util.Locale.US, "￥%.2f", balance),
            WIDTH - pad, 144f + 34f, paint(34f, weight = "main", align = Paint.Align.RIGHT)
        )

        // 第三行：今日使用
        canvas.drawText("今日使用", pad, 206f + 21f, paint(21f))
        canvas.drawText(
            String.format(java.util.Locale.US, "￥%.2f", todayUsed),
            WIDTH - pad, 200f + 34f, paint(34f, weight = "main", align = Paint.Align.RIGHT)
        )

        // 第四行：上次刷新（字号自适应）
        val footerText = "上次刷新 $refreshed"
        var footerPaint = paint(15f, weight = "thin")
        for (size in listOf(15f, 14f, 13f, 12f, 11f, 10f)) {
            footerPaint = paint(size, weight = "thin", align = Paint.Align.CENTER)
            if (footerPaint.measureText(footerText) <= WIDTH - pad * 2) break
        }
        canvas.drawLine(pad, 252f, WIDTH - pad, 252f, linePaint)
        canvas.drawText(footerText, WIDTH / 2f, 272f + footerPaint.textSize * 0.5f, footerPaint)

        return quantize4(bmp)
    }

    /** 8bit 灰度 → 4 级灰阶（最近邻）。 */
    private fun quantize4(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(WIDTH * HEIGHT)
        src.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val gray = (r * 77 + g * 150 + b * 29) shr 8
            val idx = nearestLevel(gray)
            pixels[i] = COLORS[idx]
        }
        out.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        return out
    }

    private fun nearestLevel(v: Int): Int {
        var best = 0
        var bestDiff = Int.MAX_VALUE
        for (i in LEVELS.indices) {
            val d = kotlin.math.abs(LEVELS[i] - v)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return best
    }

    /** 返回 (plane10, plane13)，各 15000 字节；white(1,1) gray1(1,0) gray2(0,1) black(0,0)。 */
    fun toPlanes(bmp: Bitmap): Pair<ByteArray, ByteArray> {
        val pixels = IntArray(WIDTH * HEIGHT)
        bmp.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val out10 = ByteArray(WIDTH * HEIGHT / 8)
        val out13 = ByteArray(WIDTH * HEIGHT / 8)
        var bit = 0
        var b10 = 0
        var b13 = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val gray = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            val lvl = nearestLevel(gray)
            val bit10 = if (lvl >= 2) 1 else 0
            val bit13 = if (lvl == 1 || lvl == 3) 1 else 0
            b10 = (b10 shl 1) or bit10
            b13 = (b13 shl 1) or bit13
            bit++
            if (bit == 8) {
                out10[i / 8] = b10.toByte()
                out13[i / 8] = b13.toByte()
                bit = 0
                b10 = 0
                b13 = 0
            }
        }
        return out10 to out13
    }

    /** 4 级灰阶 LUT（与桌面版 / 参考固件一致）。 */
    val LUT_4GRAY: List<Pair<Int, ByteArray>> = listOf(
        0x20 to hex("000A0000000160141400000100140000000100130A010001000000000000000000000000000000000000"),
        0x21 to hex("400A0000000190141400000110140A000001A01301000001000000000000000000000000000000000000"),
        0x22 to hex("400A0000000190141400000100140A000001990C01030401000000000000000000000000000000000000"),
        0x23 to hex("400A0000000190141400000100140A000001990B04040101000000000000000000000000000000000000"),
        0x24 to hex("800A0000000190141400000120140A000001501301000001000000000000000000000000000000000000"),
        0x25 to hex("400A00000001101414000001A01301000001000000000000000000000000000000000000000000000000"),
    )

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
