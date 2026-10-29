package app.chumo.epd42

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EpdApp() }
    }
}

private data class Snapshot(
    val apiKey: String,
    val localUsed: String,
    val userToken: String,
    val mode: Int,
)

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EpdApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dark = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    var apiKey by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) }                 // 0 = 本地记录使用量, 1 = userToken
    var localUsed by remember { mutableStateOf("") }
    var userToken by remember { mutableStateOf("") }
    var savedSnapshot by remember { mutableStateOf(Snapshot("", "", "", 0)) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("长按「运行」可每分钟自动刷新") }
    var showAbout by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var looping by remember { mutableStateOf(false) }

    var lastStorageReady by remember { mutableStateOf(false) }
    val reloadRef = remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 授权完成后立刻重新读一次（此时才能访问 /storage/emulated/0/epd42）
        reloadRef.value?.invoke()
    }

    fun requestPermissions() {
        val missing = requiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        }
    }

    fun loadTokensFromDisk() {
        scope.launch {
            val cfg = withContext(Dispatchers.IO) {
                var loaded = LoadedConfig(
                    dstoken = FileStore.readTokenValue(FileStore.tokenFile()),
                    dptoken = FileStore.readTokenValue(FileStore.platformTokenFile()),
                    store = FileStore.readState(),
                )
                if (loaded.store == null && storageReady(context)) {
                    // 启动时若 dsstore.json 不存在，先建一个初始状态文件（与 CLI 同构）
                    runCatching { FileStore.writeState(LocalTime.todayDate(), 0.0, null) }
                    loaded = loaded.copy(store = FileStore.readState())
                }
                loaded
            }
            apiKey = cfg.dstoken ?: ""
            if (cfg.dptoken != null) {
                userToken = cfg.dptoken
                mode = 1
            } else {
                mode = 0
            }
            // 今日使用量：从 dsstore.json 恢复（与 CLI 状态文件一致）
            val store = cfg.store
            val sameDay = store != null && store.date == LocalTime.todayDate()
            if (sameDay) {
                localUsed = formatUsedForInput(store!!.todayUsed)
                status = "已从 dsstore.json 恢复今日使用量：￥${formatUsedForInput(store.todayUsed)}"
            }
            savedSnapshot = Snapshot(apiKey, localUsed, userToken, mode)
        }
    }
    androidx.compose.runtime.SideEffect {
        reloadRef.value = { loadTokensFromDisk() }
    }

    LaunchedEffect(Unit) {
        lastStorageReady = storageReady(context)
        requestPermissions()
        loadTokensFromDisk()
        loadedOnce = true
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // 从「所有文件访问」设置页返回、或首次授予权限后，重新读取配置
        val ready = storageReady(context)
        if (ready && !lastStorageReady) {
            loadTokensFromDisk()
        }
        lastStorageReady = ready
        // 与前台服务状态保持一致（例如从后台回来、或服务自己停了）
        looping = LoopStatus.running
    }

    LaunchedEffect(looping) {
        while (looping) {
            val s = LoopStatus.status
            if (s.isNotEmpty()) status = s
            LoopStatus.lastBitmap?.let { bmp -> if (bmp !== preview) preview = bmp }
            if (!LoopStatus.running) {
                looping = false
                status = "常态刷新已结束"
            }
            delay(1_000L)
        }
    }

    val snapshot = Snapshot(apiKey, localUsed, userToken, mode)
    val saveEnabled = snapshot != savedSnapshot && !running

    val currentLocalUsed = localUsed
    val currentUserToken = userToken
    val currentMode = mode

    fun saveConfig() {
        if (!storageReady(context)) {
            requestPermissions()
            scope.launch { snackbar.showSnackbar("请先授予存储权限，然后重试保存") }
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    FileStore.writeToken(FileStore.tokenFile(), apiKey.trim())
                    FileStore.writeToken(
                        FileStore.platformTokenFile(),
                        if (currentMode == 1) currentUserToken.trim() else ""
                    )
                    // 今日使用量也随配置一起保存到 dsstore.json（与 CLI 同目录同构）
                    val existing = FileStore.readState()
                    val used = if (currentMode == 1) {
                        existing?.takeIf { it.date == LocalTime.todayDate() }?.todayUsed ?: 0.0
                    } else {
                        currentLocalUsed.trim().toDoubleOrNull() ?: 0.0
                    }
                    FileStore.writeState(LocalTime.todayDate(), used, null)
                }.isSuccess
            }
            if (ok) {
                savedSnapshot = Snapshot(apiKey, localUsed, userToken, mode)
                snackbar.showSnackbar("已保存到 ${FileStore.dir().absolutePath}")
            } else {
                snackbar.showSnackbar("保存失败，请检查存储权限")
            }
        }
    }

    suspend fun runCycle() {
        val rendered = RefreshEngine.run(
            context = context,
            mode = currentMode,
            localUsedText = currentLocalUsed,
            userTokenText = currentUserToken,
            apiKeyOverride = apiKey.trim(),
        ) { msg -> status = msg }
        preview = rendered.bitmap
        status = "已推送：${rendered.deviceName}（余额 ￥${
            String.format(java.util.Locale.US, "%.2f", rendered.balance)
        } / 今日使用 ￥${
            String.format(java.util.Locale.US, "%.2f", rendered.used)
        }）"
    }

    fun runNow() {
        if (running || looping) return
        if (apiKey.isBlank()) {
            scope.launch { snackbar.showSnackbar("请先填写 DeepSeek API Key") }
            return
        }
        if (!btReady(context)) {
            requestPermissions()
            scope.launch { snackbar.showSnackbar("请先授予蓝牙权限并打开蓝牙") }
            return
        }
        running = true
        scope.launch {
            var error: Exception? = null
            try {
                runCycle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                status = "运行失败：${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
            }
            error?.let { snackbar.showSnackbar("运行失败：${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** 长按「运行」：交给前台服务每分钟刷新一次（保活，切后台/锁屏也不停） */
    fun startLoop() {
        if (looping) return
        if (apiKey.isBlank()) {
            scope.launch { snackbar.showSnackbar("请先填写 DeepSeek API Key") }
            return
        }
        if (!btReady(context)) {
            requestPermissions()
            scope.launch { snackbar.showSnackbar("请先授予蓝牙权限并打开蓝牙") }
            return
        }
        LoopStatus.lastBitmap = null
        LoopStatus.status = "常态刷新已启动（每 60 秒）"
        LoopStatus.running = true
        LoopService.start(context)
        looping = true
        status = "常态刷新已启动（前台服务保活，每 60 秒）"
    }

    /** 点「结束」：停掉前台服务，立即终止循环 */
    fun stopLoop() {
        LoopService.stop(context)
        looping = false
        status = "常态刷新已结束"
    }

    MaterialTheme(colorScheme = colorScheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("DeepSeek EPD42", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { showAbout = true }) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = "关于")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("DeepSeek API Key") },
                    placeholder = {},
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == 0,
                            onClick = { mode = 0 },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("本地记录使用量")
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == 1,
                            onClick = { mode = 1 },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("userToken")
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (mode == 0) {
                    TextField(
                        value = localUsed,
                        onValueChange = { input ->
                            localUsed = sanitizeDecimal(input)
                        },
                        label = { Text("当前今日使用量") },
                        prefix = { Text("￥") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    TextField(
                        value = userToken,
                        onValueChange = { input -> userToken = input.filter { it.isLetterOrDigit() } },
                        label = { Text("userToken") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = { saveConfig() },
                        enabled = saveEnabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }
                    // 运行 / 结束：单击运行，长按（约 0.5s）进入每分钟自动刷新；运行中单击立即结束
                    val runInteraction = remember { MutableInteractionSource() }
                    var longPressFired by remember { mutableStateOf(false) }
                    LaunchedEffect(runInteraction) {
                        // collectLatest：一旦松手（Release/Cancel）会取消 delay，避免“短按也触发长按”
                        runInteraction.interactions.collectLatest { event ->
                            if (event is PressInteraction.Press) {
                                longPressFired = false
                                delay(500L)
                                longPressFired = true
                                Haptics.longPress(context)   // 长按震动反馈（适配线性马达）
                                if (!looping) startLoop()
                            }
                        }
                    }
                    Button(
                        onClick = {
                            if (longPressFired) {
                                longPressFired = false   // 长按已触发，忽略这次点击
                            } else if (looping) {
                                stopLoop()
                            } else {
                                runNow()
                            }
                        },
                        enabled = looping || !running,
                        interactionSource = runInteraction,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (looping) "结束" else "运行")
                    }
                }

                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                preview?.let { bmp ->
                    Spacer(Modifier.height(16.dp))
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "墨水屏预览",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(400f / 300f)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(6.dp)
                            )
                    )
                }
            }
        }

        if (showAbout) {
            val linkColor = if (Build.VERSION.SDK_INT >= 31) {
                Color(
                    context.resources.getColor(
                        if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_500,
                        context.theme
                    )
                )
            } else {
                MaterialTheme.colorScheme.primary
            }
            val aboutText = buildAnnotatedString {
                append("本应用由")
                withLink(
                    LinkAnnotation.Url(
                        "https://github.com/zzChumo",
                        TextLinkStyles(style = SpanStyle(color = linkColor))
                    )
                ) {
                    append("zzChumo")
                }
                append("制作，DeepSeek辅助完成。")
            }
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("关于", style = MaterialTheme.typography.headlineSmall) },
                text = {
                    Text(
                        aboutText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                },
                confirmButton = {
                    FilledTonalButton(onClick = { showAbout = false }) { Text("关闭") }
                }
            )
        }
    }
}

private data class LoadedConfig(
    val dstoken: String?,
    val dptoken: String?,
    val store: FileStore.StoreState?,
)

/** 把今日使用量格式化成可放进输入框的形式（去掉无意义的末尾 0）。 */
private fun formatUsedForInput(value: Double): String {
    val text = String.format(java.util.Locale.US, "%.2f", value)
    return text.trimEnd('0').trimEnd('.')
}

private fun sanitizeDecimal(input: String): String {
    val sb = StringBuilder()
    var dot = false
    for (ch in input) {
        if (ch.isDigit()) sb.append(ch)
        else if (ch == '.' && !dot) {
            dot = true
            sb.append('.')
        }
    }
    return sb.toString()
}

private fun storageReady(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@SuppressLint("MissingPermission")
private fun btReady(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager ?: return false
    val adapter = manager.adapter ?: return false
    val permsOk = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    return permsOk && adapter.isEnabled
}

private fun requiredRuntimePermissions(): List<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 31) {
        list += Manifest.permission.BLUETOOTH_SCAN
        list += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        list += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT < 30) {
        list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        list += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (Build.VERSION.SDK_INT >= 33) {
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    return list
}
