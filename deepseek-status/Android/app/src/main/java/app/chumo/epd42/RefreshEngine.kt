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
     * 计算今日使用量（与桌面脚本 dsstore.json 语义一致）：
     * 本地模式：跨天后的第一次刷新以「当前余额」为起点重新开始（用量归 0，不沿用旧记录/旧输入）；
     *          当天内按余额下降累加，余额上升视为充值、不计入用量；用户手动输入的基线仅当天有效。
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
            // 当天第一次刷新（跨天）：以「当前余额」作为起点，用量从 0 开始，
            // 不再以上一次保存的余额/用量记录作为起点（否则充值后会把差额算进用量）。
            !sameDay -> if (state == null) (entered ?: 0.0) else 0.0
            // 用户手动填写的基线：仅当天有效
            entered != null && kotlin.math.abs(state!!.todayUsed - entered) > 1e-9 -> entered
            // 当天内：余额下降才累加，余额上升（充值）不计入
            else -> {
                val lastBalance = state!!.lastBalance ?: balance
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

        // 余额、用量、取模算法都没变 → 跳过刷屏（与桌面 CLI 行为一致）
        val effectiveMode = SettingsStore.effectiveDitherMode(context)
        if (skipIfUnchanged) {
            val displayed = withContext(Dispatchers.IO) { FileStore.readDisplayed() }
            if (displayed != null &&
                kotlin.math.abs(displayed.balance - balance) < 1e-9 &&
                kotlin.math.abs(displayed.used - used) < 1e-9 &&
                displayed.mode == effectiveMode
            ) {
                onStatus("余额与用量均未变化，跳过本次刷屏")
                return Rendered(balance, used, null, null, skipped = true)
            }
        }

        val stamp = LocalTime.stampNow() + " " + LocalTime.tzName()
        withContext(Dispatchers.IO) { StatusRenderer.loadFont(context) }
        val raw = withContext(Dispatchers.Default) {
            StatusRenderer.render(balance, used, stamp, peak, window)
        }

        onStatus("预览已生成，正在查找蓝牙设备…")
        val payload = withContext(Dispatchers.Default) { Dither.build(raw, effectiveMode) }
        val push = Epd42Ble.push(context, payload, onLog = onStatus)
        withContext(Dispatchers.IO) { FileStore.markDisplayed(balance, used, effectiveMode) }
        return Rendered(balance, used, payload.preview, push.deviceName, skipped = false)
    }
}
