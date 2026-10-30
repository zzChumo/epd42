package app.chumo.epd42

import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** /storage/emulated/0/epd42 下的 .dstoken / .dptoken 读写（与桌面版一致）。 */
object FileStore {
    const val DIR_NAME = "epd42"
    const val TOKEN_FILE = ".dstoken"
    const val PLATFORM_TOKEN_FILE = ".dptoken"
    const val STATE_FILE = "dsstore.json"

    fun dir(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    fun tokenFile(): File = File(dir(), TOKEN_FILE)
    fun platformTokenFile(): File = File(dir(), PLATFORM_TOKEN_FILE)
    fun stateFile(): File = File(dir(), STATE_FILE)

    /** 解码文本：兼容 Windows 的 UTF-16(BOM)、UTF-8(BOM)、GBK。 */
    private fun decode(data: ByteArray): String {
        return when {
            data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte() ->
                String(data, 3, data.size - 3, Charsets.UTF_8)
            data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() ->
                String(data, 2, data.size - 2, Charsets.UTF_16LE)
            data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() ->
                String(data, 2, data.size - 2, Charsets.UTF_16BE)
            else -> {
                val text = runCatching { String(data, Charsets.UTF_8) }.getOrNull()
                val hasReplacement = text?.contains('\uFFFD') == true
                if (text != null && !hasReplacement) text
                else String(data, Charset.forName("GBK"))
            }
        }.replace("\u0000", "")
    }

    /** 读取 token：忽略空行与注释行（# 或 // 开头）；只返回第一行有效内容；无效返回 null。 */
    fun readTokenValue(file: File): String? {
        if (!file.exists()) return null
        val text = runCatching { decode(file.readBytes()) }.getOrNull() ?: return null
        for (raw in text.lineSequence()) {
            val line = raw.trim().trim('\uFEFF')
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("//")) continue
            return line.trim('"').trim('\'').trim()
        }
        return null
    }

    fun writeToken(file: File, value: String) {
        file.parentFile?.mkdirs()
        file.writeText(value.trim(), Charsets.UTF_8)
    }

    // ── 今日使用量状态（dsstore.json，字段与 CLI 的 .dsstate.json 保持一致）──
    data class StoreState(
        val date: String,
        val todayUsed: Double,
        val lastBalance: Double?,
    )

    /** 读取 dsstore.json；不存在/损坏返回 null。 */
    fun readState(): StoreState? {
        val file = stateFile()
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(decode(file.readBytes()))
            StoreState(
                date = json.optString("date", ""),
                todayUsed = json.optDouble("today_used", 0.0),
                lastBalance = if (json.has("last_balance")) json.optDouble("last_balance", 0.0) else null,
            )
        }.getOrNull()
    }

    /** 覆盖写 dsstore.json（保留已存在的 displayed_* / last_balance 等字段）。 */
    fun writeState(date: String, todayUsed: Double, lastBalance: Double? = null) {
        val file = stateFile()
        file.parentFile?.mkdirs()
        val json = runCatching {
            if (file.exists()) JSONObject(decode(file.readBytes())) else JSONObject()
        }.getOrDefault(JSONObject())
        json.put("date", date)
        json.put("today_used", round6(todayUsed))
        if (lastBalance != null) json.put("last_balance", round6(lastBalance))
        json.put("last_refresh", LocalTime.stampNow())
        file.writeText(json.toString(2), Charsets.UTF_8)
    }

    /** 读取屏幕上已显示的 (余额, 今日使用量)；没有记录时返回 null。 */
    fun readDisplayed(): Pair<Double, Double>? {
        val file = stateFile()
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(decode(file.readBytes()))
            if (json.has("displayed_balance") && json.has("displayed_used")) {
                json.optDouble("displayed_balance") to json.optDouble("displayed_used")
            } else null
        }.getOrNull()
    }

    /** 推送成功后记录「屏幕正在显示的值」（CLI 同名字段）。 */
    fun markDisplayed(balance: Double, used: Double) {
        val file = stateFile()
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(decode(file.readBytes()))
            json.put("displayed_balance", round6(balance))
            json.put("displayed_used", round6(used))
            file.writeText(json.toString(2), Charsets.UTF_8)
        }
    }

    private fun round6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0

    fun dateStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
