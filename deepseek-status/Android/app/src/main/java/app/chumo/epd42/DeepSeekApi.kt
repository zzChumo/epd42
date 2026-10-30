package app.chumo.epd42

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** DeepSeek balance / usage API (OkHttp + fallback DNS so background requests survive Doze). */
object DeepSeekApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(FallbackDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun get(url: String, bearer: String, platform: Boolean = false): String {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .header("User-Agent", "epd42-android/0.2")
        if (platform) builder.header("x-client-platform", "web")
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(200)}")
            }
            return body
        }
    }

    /** @return (raw response, CNY balance) */
    fun fetchBalance(apiKey: String): Pair<String, Double> {
        val raw = get("https://api.deepseek.com/user/balance", apiKey)
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

    /** Optional: platform web session token, returns today's cost. null = not found. */
    fun fetchTodayCost(
        userToken: String,
        dayStartSec: Long,
        nowSec: Long,
        tzOffsetSec: Int,
    ): Pair<String, Double?>? {
        val url = "https://platform.deepseek.com/api/v0/usage/by_api_key/cost" +
                "?start=$dayStartSec&end=$nowSec&tz=$tzOffsetSec"
        val raw = get(url, userToken, platform = true)
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
