package app.chumo.epd42

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.launch

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
        if (intent?.action == ACTION_STOP) {
            stopLoop()
            return START_NOT_STICKY
        }
        startLoop()
        return START_STICKY
    }

    private fun startLoop() {
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
                val startedAt = System.currentTimeMillis()
                var delayMs = LoopStatus.INTERVAL_MS
                try {
                    if (!hasNetwork()) {
                        updateStatus("等待网络恢复…")
                        delayMs = 10_000L
                    } else {
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
                        LoopStatus.lastBitmap = rendered.bitmap
                        updateStatus(
                            "已推送：${rendered.deviceName}（余额 ￥${
                                String.format(java.util.Locale.US, "%.2f", rendered.balance)
                            } / 今日使用 ￥${
                                String.format(java.util.Locale.US, "%.2f", rendered.used)
                            }）"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delayMs = 10_000L
                    val reason = if (e is java.net.UnknownHostException) {
                        "DNS 解析失败（后台网络受限），重试中…"
                    } else {
                        "刷新失败（${e.javaClass.simpleName}: ${e.message}），稍后重试…"
                    }
                    updateStatus(reason)
                }
                renewWakeLock()
                val elapsed = System.currentTimeMillis() - startedAt
                val remain = (delayMs - elapsed).coerceAtLeast(2_000L)
                delay(remain)
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

    private fun buildNotification(text: String): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("dsEPD42 常驻刷新")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)
            .addAction(0, "结束", stopIntent)
            .build()
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

        fun start(context: Context) {
            val intent = Intent(context, LoopService::class.java)
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
