package app.chumo.epd42

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 时间与时区工具：峰谷判断固定按北京时间，展示时间跟随系统时区。 */
object LocalTime {
    private val BEIJING = TimeZone.getTimeZone("Asia/Shanghai")

    /** 系统时区的 IANA 名称，如 Asia/Shanghai。 */
    fun tzName(): String = TimeZone.getDefault().id ?: "Local"

    fun nowLocal(): Date = Date()

    fun nowBeijing(): Calendar = Calendar.getInstance(BEIJING).apply { time = Date() }

    /** 是否谷时：北京时间 00:30–08:30。 */
    fun isOffpeak(): Boolean {
        val c = nowBeijing()
        val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return minutes >= 30 && minutes < 8 * 60 + 30
    }

    /** 把北京时间的峰/谷时段换算成系统本地时间显示，例如「峰时 09:30–01:30」。 */
    fun windowTextLocal(peak: Boolean): String {
        val now = System.currentTimeMillis()
        val bjOffset = BEIJING.getOffset(now)
        val localOffset = TimeZone.getDefault().getOffset(now)
        val shiftMinutes = (localOffset - bjOffset) / 60000
        fun fmt(hour: Int, minute: Int): String {
            var total = ((hour * 60 + minute + shiftMinutes) % 1440 + 1440) % 1440
            val h = total / 60
            val m = total % 60
            return String.format(Locale.US, "%02d:%02d", h, m)
        }
        return if (peak) {
            "峰时 ${fmt(8, 30)}–${fmt(0, 30)}"
        } else {
            "谷时 ${fmt(0, 30)}–${fmt(8, 30)}"
        }
    }

    fun stampNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun todayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 本地当天 0 点的时间戳（秒）。 */
    fun dayStartEpochSec(): Long {
        val c = Calendar.getInstance().apply {
            time = Date()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis / 1000
    }

    fun nowEpochSec(): Long = System.currentTimeMillis() / 1000

    /** 本地时区相对 UTC 的偏移（秒）。 */
    fun localOffsetSec(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
}
