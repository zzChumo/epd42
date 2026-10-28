package app.chumo.epd42

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * 常态刷新前台服务（保活）：
 * 每分钟执行一轮刷新，通知栏常驻；带「结束」按钮，可随时停止。
 */
class LoopService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStatus: String = "常态刷新中（每 60 秒）"

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
        if (job?.isActive == true) return

        // 允许慢速 BLE/网络在屏幕熄灭后继续跑
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsEPD42:loop").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        startForegroundSafely()

        LoopStatus.running = true
        LoopStatus.status = "常态刷新已启动（每 60 秒）"

        job = scope.launch {
            while (isActive) {
                try {
                    val cfg = readConfig()
                    val rendered = RefreshEngine.run(
                        context = this@LoopService,
                        mode = cfg.mode,
                        localUsedText = cfg.localUsedText,
                        userTokenText = cfg.userTokenText,
                        apiKeyOverride = null,
                    ) { msg ->
                        LoopStatus.status = msg
                        lastStatus = msg
                        notifyStatus(msg)
                    }
                    LoopStatus.lastBitmap = rendered.bitmap
                    LoopStatus.status = "已推送：${rendered.deviceName}（余额 ￥${
                        String.format(java.util.Locale.US, "%.2f", rendered.balance)
                    } / 今日使用 ￥${
                        String.format(java.util.Locale.US, "%.2f", rendered.used)
                    }）"
                    lastStatus = LoopStatus.status
                    notifyStatus(lastStatus)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LoopStatus.status = "本轮刷新失败：${e.javaClass.simpleName}: ${e.message}"
                    lastStatus = LoopStatus.status
                    notifyStatus(lastStatus)
                }
                if (!isActive) break
                delay(LoopStatus.INTERVAL_MS)
            }
        }
    }

    private data class Config(val mode: Int, val localUsedText: String, val userTokenText: String)

    /** 后台没有输入框：userToken 有值就走 userToken 模式，否则用 dsstore.json 记录继续累计。 */
    private fun readConfig(): Config {
        val platformToken = FileStore.readTokenValue(FileStore.platformTokenFile())
        return if (platformToken != null) {
            Config(1, "", platformToken)
        } else {
            Config(0, "", "")
        }
    }

    private fun startForegroundSafely() {
        try {
            val notification = buildNotification(lastStatus)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // Android 14+ 后台启动前台服务受限时兜底：直接停掉，避免崩溃
            LoopStatus.status = "前台服务启动失败：${e.message}"
            LoopStatus.running = false
            stopSelf()
        }
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
            .setContentTitle("dsEPD42 常态刷新")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "结束", stopIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "常态刷新",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "dsEPD42 每分钟刷新墨水屏时显示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopLoop() {
        job?.cancel()
        job = null
        LoopStatus.running = false
        LoopStatus.status = "常态刷新已结束"
        runCatching { wakeLock?.release() }
        wakeLock = null
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
        runCatching { wakeLock?.release() }
        wakeLock = null
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
            context.stopService(Intent(context, LoopService::class.java))
            LoopStatus.running = false
        }
    }
}
