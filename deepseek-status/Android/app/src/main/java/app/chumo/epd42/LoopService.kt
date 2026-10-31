package app.chumo.epd42

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Keep-alive foreground service:
 * - refreshes every 60 s (network + BLE)
 * - tries specialUse (Android 14+) so the system never time-limits it;
 *   falls back to dataSync / connectedDevice / plain if unavailable
 * - holds a partial wake lock and keeps a persistent notification
 */
class LoopService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStatus: String = "常驻刷新运行中（每 60 秒）"

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统因 START_STICKY 重启服务时 intent 为 null：只有用户仍希望常驻时才恢复
        if (intent == null && !isLoopRequested()) {
            LoopStatus.running = false
            LoopStatus.status = "常驻刷新已停止"
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoop()
                return START_NOT_STICKY
            }
            ACTION_PUSH_IMAGE -> {
                startCustomPush(intent.getStringExtra(EXTRA_IMAGE_PATH))
                return START_NOT_STICKY
            }
        }
        startLoop()
        return START_STICKY
    }

    /** 自定义图像一次性发送：同样用前台服务 + 通知保活，发送完自动结束。 */
    private fun startCustomPush(path: String?) {
        if (path.isNullOrEmpty()) {
            CustomPushStatus.running = false
            CustomPushStatus.status = "图片路径无效"
            stopSelf()
            return
        }
        val notification = buildNotification("正在发送自定义图像…", "dsEPD42 自定义图像", withStopAction = false)
        if (!startForegroundCompat(notification)) {
            CustomPushStatus.running = false
            CustomPushStatus.status = "前台服务启动失败"
            stopSelf()
            return
        }
        acquireWakeLock()
        CustomPushStatus.running = true
        CustomPushStatus.status = "正在发送自定义图像…"
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
                    ?: throw IllegalStateException("无法读取临时图片")
                val planes = withContext(Dispatchers.Default) { StatusRenderer.toPlanes(bmp) }
                val result = Epd42Ble.push(this@LoopService, planes.first, planes.second) { msg ->
                    CustomPushStatus.status = msg
                    notifyCustom(msg)
                }
                CustomPushStatus.status = "已发送：${result.deviceName}"
                notifyCustom(CustomPushStatus.status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CustomPushStatus.status = "发送失败：${e.javaClass.simpleName}: ${e.message}"
                notifyCustom(CustomPushStatus.status)
            } finally {
                CustomPushStatus.running = false
                runCatching { java.io.File(path).delete() }
                if (job?.isActive == true) {
                    // 常驻循环同时还在跑：把前台通知恢复成循环状态，不要停服务
                    runCatching { startForegroundCompat(buildNotification(lastStatus)) }
                } else {
                    releaseWakeLock()
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                        else @Suppress("DEPRECATION") stopForeground(true)
                    }
                    stopSelf()
                }
            }
        }
    }

    private fun notifyCustom(text: String) {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, buildNotification(text, "dsEPD42 自定义图像", withStopAction = false))
        }
    }

    private fun startLoop() {
        setLoopRequested(true)
        if (job?.isActive == true) {
            // already running: make sure we are still foreground and refresh the notification
            runCatching { startForegroundCompat(buildNotification(lastStatus)) }
            return
        }

        val foregroundOk = startForegroundCompat(buildNotification(lastStatus))
        if (!foregroundOk) {
            LoopStatus.running = false
            LoopStatus.status = "前台服务启动失败，无法后台联网刷新"
            stopSelf()
            return
        }

        acquireWakeLock()
        LoopStatus.running = true
        updateStatus("常驻刷新已启动（每 60 秒）")

        job = scope.launch {
            while (isActive) {
                try {
                    if (!hasNetwork()) {
                        updateStatus("等待网络恢复…")
                    } else {
                        // 单轮最多 70 秒，避免网络/蓝牙异常时整条循环卡死
                        withTimeout(70_000L) {
                            val cfg = readConfig()
                            val rendered = RefreshEngine.run(
                                context = this@LoopService,
                                mode = cfg.mode,
                                localUsedText = cfg.localUsedText,
                                userTokenText = cfg.userTokenText,
                                apiKeyOverride = null,
                            ) { msg ->
                                updateStatus(msg)
                            }
                            if (rendered.skipped) {
                                updateStatus(
                                    "余额与用量均未变化，跳过刷屏（余额 ￥${
                                        String.format(java.util.Locale.US, "%.2f", rendered.balance)
                                    } / 今日使用 ￥${
                                        String.format(java.util.Locale.US, "%.2f", rendered.used)
                                    }）"
                                )
                            } else {
                                LoopStatus.lastBitmap = rendered.bitmap
                                updateStatus(
                                    "已推送：${rendered.deviceName}（余额 ￥${
                                        String.format(java.util.Locale.US, "%.2f", rendered.balance)
                                    } / 今日使用 ￥${
                                        String.format(java.util.Locale.US, "%.2f", rendered.used)
                                    }）"
                                )
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    updateStatus("本轮刷新超时，稍后重试…")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val reason = if (e is java.net.UnknownHostException) {
                        "DNS 解析失败（后台网络受限），重试中…"
                    } else {
                        "刷新失败（${e.javaClass.simpleName}: ${e.message}），稍后重试…"
                    }
                    updateStatus(reason)
                }
                renewWakeLock()
                // 与 CLI 完全一致：本轮（无论成功/失败/跳过）结束后固定等待 60 秒
                delay(LoopStatus.INTERVAL_MS)
            }
        }
    }

    private data class Config(val mode: Int, val localUsedText: String, val userTokenText: String)

    private fun readConfig(): Config {
        val platformToken = FileStore.readTokenValue(FileStore.platformTokenFile())
        return if (platformToken != null) {
            Config(1, "", platformToken)
        } else {
            Config(0, "", "")
        }
    }

    private fun hasNetwork(): Boolean {
        return runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    /** Tries FGS types from most to least privileged; returns true if foreground started. */
    private fun startForegroundCompat(notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            return runCatching { startForeground(NOTIF_ID, notification) }.isSuccess
        }
        val candidates = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= 34) {
            candidates += ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        candidates += ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        candidates += ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        for (type in candidates) {
            val ok = runCatching { startForeground(NOTIF_ID, notification, type) }.isSuccess
            if (ok) return true
        }
        return runCatching { startForeground(NOTIF_ID, notification) }.isSuccess
    }

    private fun updateStatus(text: String) {
        lastStatus = text
        LoopStatus.status = text
        notifyStatus(text)
    }

    private fun notifyStatus(text: String) {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(
        text: String,
        title: String = "dsEPD42 常驻刷新",
        withStopAction: Boolean = true,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LoopService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)
        if (withStopAction) builder.addAction(0, "结束", stopIntent)
        return builder.build()
    }

    private fun isLoopRequested(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOOP_REQUESTED, false)

    private fun setLoopRequested(value: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOOP_REQUESTED, value).apply()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "常驻刷新",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "dsEPD42 每分钟刷新墨水屏时显示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsEPD42:loop").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        }
    }

    private fun renewWakeLock() {
        runCatching {
            val lock = wakeLock ?: return@runCatching
            if (!lock.isHeld) lock.acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun stopLoop() {
        setLoopRequested(false)
        job?.cancel()
        job = null
        LoopStatus.running = false
        LoopStatus.status = "常驻刷新已结束"
        releaseWakeLock()
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        LoopStatus.running = false
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "dsEPD42_loop"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "app.chumo.epd42.STOP_LOOP"
        const val ACTION_PUSH_IMAGE = "app.chumo.epd42.PUSH_IMAGE"
        const val PREFS = "epd42_loop"
        const val KEY_LOOP_REQUESTED = "loop_requested"
        const val EXTRA_IMAGE_PATH = "image_path"

        fun start(context: Context) {
            val intent = Intent(context, LoopService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pushImage(context: Context, path: String) {
            val intent = Intent(context, LoopService::class.java)
                .setAction(ACTION_PUSH_IMAGE)
                .putExtra(EXTRA_IMAGE_PATH, path)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LoopService::class.java)) }
            LoopStatus.running = false
        }
    }
}
