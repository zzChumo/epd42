package app.chumo.epd42

import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** DeepSeek 余额 / 用量接口（HttpURLConnection，无第三方依赖）。 */
object DeepSeekApi {

    private fun open(url: String, bearer: String, platform: Boolean = false): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Authorization", "Bearer $bearer")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "epd42-android/0.1")
        if (platform) conn.setRequestProperty("x-client-platform", "web")
        return conn
    }

    private fun readText(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val text = BufferedInputStream(stream).use { it.readBytes().toString(Charsets.UTF_8) }
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(200)}")
        return text
    }

    /** 返回 (原始响应, 人民币余额)。 */
    fun fetchBalance(apiKey: String): Pair<String, Double> {
        val conn = open("https://api.deepseek.com/user/balance", apiKey)
        val raw = readText(conn)
        val json = JSONObject(raw)
        val infos = json.optJSONArray("balance_infos")
            ?: throw IllegalStateException("余额接口未返回 balance_infos")
        var chosen: JSONObject? = null
        for (i in 0 until infos.length()) {
            val o = infos.getJSONObject(i)
            if (o.optString("currency") == "CNY") { chosen = o; break }
            if (chosen == null) chosen = o
        }
        val balance = chosen?.optString("total_balance")?.toDoubleOrNull() ?: 0.0
        return raw to balance
    }

    /** 可选：Platform 网页 userToken 查询今日消费（API Key 查不到用量）。返回 null 表示未找到可信金额。 */
    fun fetchTodayCost(userToken: String, dayStartSec: Long, nowSec: Long, tzOffsetSec: Int): Pair<String, Double?>? {
        val url = "https://platform.deepseek.com/api/v0/usage/by_api_key/cost" +
                "?start=$dayStartSec&end=$nowSec&tz=$tzOffsetSec"
        val conn = open(url, userToken, platform = true)
        val raw = readText(conn)
        val json = JSONObject(raw)
        var found = false
        var sum = 0.0
        fun walk(node: Any?, depth: Int) {
            if (depth > 12) return
            when (node) {
                is JSONObject -> {
                    val date = node.optString("date", node.optString("day", ""))
                    for (key in listOf("cost", "total_cost", "cost_cny", "total_cost_cny")) {
                        if (node.has(key)) {
                            val v = node.optDouble(key, Double.NaN)
                            if (!v.isNaN()) {
                                found = true
                                if (date.isEmpty() || date.contains(LocalTime.todayDate())) sum += v
                            }
                            break
                        }
                    }
                    for (k in node.keys()) walk(node.opt(k), depth + 1)
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
            }
        }
        walk(json, 0)
        return raw to if (found) sum else null
    }
}
