package app.chumo.epd42

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

/** 一次完整刷新：拉余额 → 计算今日使用量 → 渲染 400×300 → 推送墨水屏。 */
object RefreshEngine {

    data class Rendered(
        val balance: Double,
        val used: Double,
        val bitmap: Bitmap?,
        val deviceName: String?,
        val skipped: Boolean,
    )

    /**
     * 计算今日使用量（与 dsstore.json / CLI 语义一致）：
     * 本地模式：输入值作为基线，之后按余额下降累加；输入为空则沿用 dsstore 记录。
     * userToken 模式：优先平台真实消费。
     */
    fun computeTodayUsed(
        mode: Int,
        localUsedText: String,
        userTokenText: String,
        balance: Double,
    ): Double {
        val today = LocalTime.todayDate()
        val state = FileStore.readState()
        val sameDay = state != null && state.date == today

        if (mode == 1 && userTokenText.isNotBlank()) {
            val cost = runCatching {
                DeepSeekApi.fetchTodayCost(
                    userTokenText.trim(),
                    LocalTime.dayStartEpochSec(),
                    LocalTime.nowEpochSec(),
                    LocalTime.localOffsetSec(),
                )?.second
            }.getOrNull()
            val used = cost ?: if (sameDay) state!!.todayUsed else 0.0
            FileStore.writeState(today, used, balance)
            return used
        }

        val entered = localUsedText.trim().toDoubleOrNull()
        val used: Double = when {
            !sameDay -> entered ?: 0.0
            entered == null -> {
                val lastBalance = state!!.lastBalance ?: balance
                state.todayUsed + max(0.0, lastBalance - balance)
            }
            kotlin.math.abs(state!!.todayUsed - entered) > 1e-9 -> entered
            else -> {
                val lastBalance = state.lastBalance ?: balance
                state.todayUsed + max(0.0, lastBalance - balance)
            }
        }
        FileStore.writeState(today, used, balance)
        return used
    }

    /** 网络操作重试（后台/弱网时提高成功率）。 */
    private suspend fun <T> withNetworkRetry(times: Int = 2, block: suspend () -> T): T {
        var last: Exception? = null
        for (i in 0 until times) {
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (i < times - 1) delay(1_000L * (i + 1))
            }
        }
        throw last ?: IllegalStateException("网络请求失败")
    }

    /** 执行一轮刷新；apiKeyOverride 用于界面上「还没保存」的 Key，null 表示从 .dstoken 读。 */
    suspend fun run(
        context: Context,
        mode: Int,
        localUsedText: String,
        userTokenText: String,
        apiKeyOverride: String? = null,
        skipIfUnchanged: Boolean = true,
        onStatus: (String) -> Unit = {},
    ): Rendered {
        onStatus("正在获取余额…")
        val apiKey = (apiKeyOverride ?: withContext(Dispatchers.IO) {
            FileStore.readTokenValue(FileStore.tokenFile())
        })
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("未配置 DeepSeek API Key（.dstoken 为空）")
        // 网络请求必须离开主线程；后台网络偶发失败时自动重试
        val (_, balance) = withContext(Dispatchers.IO) {
            withNetworkRetry { DeepSeekApi.fetchBalance(apiKey) }
        }

        val peak = !LocalTime.isOffpeak()
        val window = LocalTime.windowTextLocal(peak)
        val used = withContext(Dispatchers.IO) {
            computeTodayUsed(mode, localUsedText, userTokenText, balance)
        }

        // 余额与今日使用量都没变 → 跳过刷屏（与桌面 CLI 行为一致）
        if (skipIfUnchanged) {
            val displayed = withContext(Dispatchers.IO) { FileStore.readDisplayed() }
            if (displayed != null &&
                kotlin.math.abs(displayed.first - balance) < 1e-9 &&
                kotlin.math.abs(displayed.second - used) < 1e-9
            ) {
                onStatus("余额与用量均未变化，跳过本次刷屏")
                return Rendered(balance, used, null, null, skipped = true)
            }
        }

        val stamp = LocalTime.stampNow() + " " + LocalTime.tzName()
        val bitmap = withContext(Dispatchers.Default) {
            StatusRenderer.render(balance, used, stamp, peak, window)
        }

        onStatus("预览已生成，正在查找蓝牙设备…")
        val planes = withContext(Dispatchers.Default) { StatusRenderer.toPlanes(bitmap) }
        val push = Epd42Ble.push(context, planes.first, planes.second, onLog = onStatus)
        withContext(Dispatchers.IO) { FileStore.markDisplayed(balance, used) }
        return Rendered(balance, used, bitmap, push.deviceName, skipped = false)
    }
}
