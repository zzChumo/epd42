package app.chumo.epd42

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 时间与时区工具。
 * 峰谷规则：星期/节假日按北京日期，时段按 UTC 时刻；展示时间跟随系统时区。
 *   峰时：UTC 01:00–04:00 与 06:00–10:00，周一至周五（北京日期），不含中国法定节假日
 *   谷时：周六、周日全天；法定节假日全天；其余时间
 */
object LocalTime {
    private val BEIJING = TimeZone.getTimeZone("Asia/Shanghai")
    private val UTC = TimeZone.getTimeZone("UTC")

    /** 峰时段（UTC）：[sh, sm] 到 [eh, em]。 */
    private val PEAK_WINDOWS_UTC = listOf(
        intArrayOf(1, 0, 4, 0),
        intArrayOf(6, 0, 10, 0),
    )

    /** 中国法定节假日（放假日期；调休上班的周末不算，周六日本身就是谷时）。 */
    private val HOLIDAYS: Map<Int, List<String>> = mapOf(
        2025 to listOf(
            "01-01",
            "01-28", "01-29", "01-30", "01-31", "02-01", "02-02", "02-03", "02-04",
            "04-04", "04-05", "04-06",
            "05-01", "05-02", "05-03", "05-04", "05-05",
            "05-31", "06-01", "06-02",
            "10-01", "10-02", "10-03", "10-04", "10-05", "10-06", "10-07", "10-08",
        ),
        2026 to listOf(
            "01-01", "01-02", "01-03",
            "02-15", "02-16", "02-17", "02-18", "02-19", "02-20", "02-21", "02-22", "02-23",
            "04-04", "04-05", "04-06",
            "05-01", "05-02", "05-03", "05-04", "05-05",
            "06-19", "06-20", "06-21",
            "09-25", "09-26", "09-27",
            "10-01", "10-02", "10-03", "10-04", "10-05", "10-06", "10-07",
        ),
    )

    /** 系统时区的 IANA 名称，如 Asia/Shanghai。 */
    fun tzName(): String = TimeZone.getDefault().id ?: "Local"

    fun nowLocal(): Date = Date()

    fun nowBeijing(): Calendar = Calendar.getInstance(BEIJING).apply { time = Date() }

    /** 是否谷时（用当前 UTC 时刻判断）。 */
    fun isOffpeak(): Boolean = isOffpeakAt(Calendar.getInstance(UTC))

    fun isOffpeakAt(nowUtc: Calendar): Boolean {
        val bj = Calendar.getInstance(BEIJING).apply { timeInMillis = nowUtc.timeInMillis }
        val dow = bj.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return true
        val md = String.format(
            Locale.US, "%02d-%02d",
            bj.get(Calendar.MONTH) + 1, bj.get(Calendar.DAY_OF_MONTH)
        )
        if (HOLIDAYS[bj.get(Calendar.YEAR)]?.contains(md) == true) return true
        val hm = nowUtc.get(Calendar.HOUR_OF_DAY) * 60 + nowUtc.get(Calendar.MINUTE)
        for (w in PEAK_WINDOWS_UTC) {
            val start = w[0] * 60 + w[1]
            val end = w[2] * 60 + w[3]
            if (hm >= start && hm < end) return false   // 峰时
        }
        return true
    }

    /** 右上角时段文案：峰时显示各时段换算到系统本地时区的钟点；谷时显示 00:00-24:00。 */
    fun windowTextLocal(peak: Boolean): String {
        if (!peak) return "谷时 00:00-24:00"
        val shiftMinutes = localOffsetSec() / 60
        fun fmt(hour: Int, minute: Int): String {
            var total = ((hour * 60 + minute + shiftMinutes) % 1440 + 1440) % 1440
            return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
        }
        val parts = PEAK_WINDOWS_UTC.map { w ->
            "${fmt(w[0], w[1])}-${fmt(w[2], w[3])}"
        }
        return "峰时 " + parts.joinToString(", ")
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
