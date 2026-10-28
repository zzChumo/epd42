package app.chumo.epd42

import android.graphics.Bitmap

/** 常态刷新循环的共享状态（前台服务写入，界面读取）。 */
object LoopStatus {
    @Volatile var running: Boolean = false
    @Volatile var status: String = ""
    @Volatile var lastBitmap: Bitmap? = null
    const val INTERVAL_MS = 60_000L
}
