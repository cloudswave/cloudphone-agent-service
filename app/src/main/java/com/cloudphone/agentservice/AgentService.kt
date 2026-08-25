package com.cloudphone.agentservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service: reads config from /sdcard/cloudphone/agent.config,
 * deploys agent binary to /data/local/tmp/, and runs via su.
 */
class AgentService : Service() {

    companion object {
        const val CHANNEL_ID = "cloudphone_agent_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.cloudphone.agentservice.STOP"
        private const val TAG = "AgentService"
        private const val TMP_AGENT = "/data/local/tmp/cloudphone-agent"
        private const val TMP_JAR = "/data/local/tmp/ss.jar"

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private var configWatchJob: Job? = null
    private var isRunning = false
    private var currentConfig = AgentConfig()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification("启动中..."))
        acquireWakeLock()

        serviceScope.launch {
            initAgent()
            startConfigWatcher()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        monitorJob?.cancel()
        configWatchJob?.cancel()
        serviceScope.cancel()
        RootHelper.stopAgent()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun initAgent() {
        // 1. Check root
        if (!RootHelper.hasRoot()) {
            updateNotification("无 root 权限")
            Log.e(TAG, "No root access")
            return
        }
        Log.i(TAG, "Root access OK")
        updateNotification("Root 已就绪")

        // 2. Read config from /sdcard/cloudphone/agent.config
        val configContent = RootHelper.readConfigFromSdcard()
        if (configContent == null) {
            Log.w(TAG, "Config file not found at /sdcard/cloudphone/agent.config, waiting...")
            updateNotification("等待配置文件")
            return
        }
        currentConfig = AgentConfig.load(configContent)
        if (!currentConfig.isValid()) {
            Log.w(TAG, "Invalid config, need AGENT_ID + SIGNALING")
            updateNotification("配置不完整")
            return
        }
        Log.i(TAG, "Config loaded: id=${currentConfig.agentId} signaling=${currentConfig.signaling}")
        updateNotification("配置已加载")

        // 3. Deploy assets
        deployAssets()

        // 4. Start agent
        startAgentProcess()
    }

    private fun deployAssets() {
        val extDir = getExternalFilesDir(null) ?: filesDir
        val agentFile = File(extDir, "cloudphone-agent-arm64")
        val jarFile = File(extDir, "scrcpy-server.jar")

        try {
            if (!agentFile.exists() || agentFile.length() == 0L) {
                Log.i(TAG, "Extracting cloudphone-agent-arm64 from assets...")
                assets.open("cloudphone-agent-arm64").use { input ->
                    FileOutputStream(agentFile).use { output -> input.copyTo(output) }
                }
                agentFile.setExecutable(true)
            }
            if (!jarFile.exists() || jarFile.length() == 0L) {
                Log.i(TAG, "Extracting scrcpy-server.jar from assets...")
                assets.open("scrcpy-server.jar").use { input ->
                    FileOutputStream(jarFile).use { output -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract assets", e)
            updateNotification("释放文件失败")
            return
        }

        Log.i(TAG, "Deploying to /data/local/tmp/ ...")
        RootHelper.stopAgent()

        if (!RootHelper.deployAgent(agentFile.absolutePath, TMP_AGENT)) {
            Log.e(TAG, "Failed to deploy agent binary")
            updateNotification("部署二进制失败")
            return
        }
        if (!RootHelper.deployJar(jarFile.absolutePath, TMP_JAR)) {
            Log.e(TAG, "Failed to deploy scrcpy-server.jar")
            updateNotification("部署 jar 失败")
            return
        }
        Log.i(TAG, "Deployment complete")
    }

    private suspend fun startAgentProcess() {
        Log.i(TAG, "Starting agent via su...")
        updateNotification("启动 Agent")

        val started = RootHelper.startAgent(
            agentId = currentConfig.agentId,
            signaling = currentConfig.signaling,
            jarPath = TMP_JAR,
            iceServers = currentConfig.iceServers
        )

        if (started) {
            delay(2000)
            if (RootHelper.isAgentRunning()) {
                isRunning = true
                updateNotification("已连接 - ${currentConfig.agentId}")
                Log.i(TAG, "Agent started successfully")
                startMonitor()
            } else {
                updateNotification("Agent 启动后退出，查看日志")
                Log.e(TAG, "Agent exited immediately")
            }
        } else {
            updateNotification("Agent 启动失败")
            Log.e(TAG, "Failed to start agent")
        }
    }

    private fun startMonitor() {
        monitorJob = serviceScope.launch {
            while (isRunning) {
                delay(10000)
                if (!RootHelper.isAgentRunning()) {
                    Log.w(TAG, "Agent died, restarting...")
                    updateNotification("重连中...")
                    val configContent = RootHelper.readConfigFromSdcard()
                    if (configContent != null) {
                        currentConfig = AgentConfig.load(configContent)
                    }
                    if (currentConfig.isValid()) {
                        delay(2000)
                        startAgentProcess()
                    } else {
                        updateNotification("配置无效，等待有效配置")
                        break
                    }
                }
            }
        }
    }

    private fun startConfigWatcher() {
        configWatchJob = serviceScope.launch {
            var lastContent = RootHelper.readConfigFromSdcard() ?: ""
            while (true) {
                delay(15000)
                val newContent = RootHelper.readConfigFromSdcard()
                if (newContent != null) {
                    // Config appeared for the first time
                    if (lastContent == "" && newContent.isNotBlank()) {
                        lastContent = newContent
                        currentConfig = AgentConfig.load(newContent)
                        if (currentConfig.isValid()) {
                            Log.i(TAG, "Config appeared, starting agent...")
                            deployAssets()
                            startAgentProcess()
                        }
                        continue
                    }
                    // Config changed
                    if (newContent != lastContent) {
                        Log.i(TAG, "Config file changed, re-reading...")
                        lastContent = newContent
                        val newConfig = AgentConfig.load(newContent)
                        if (newConfig.isValid() && newConfig != currentConfig) {
                            currentConfig = newConfig
                            if (isRunning) {
                                Log.i(TAG, "Config changed, restarting agent...")
                                RootHelper.stopAgent()
                                delay(1000)
                                startAgentProcess()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloudphone:agentservice").apply {
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CloudPhone Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CloudPhone Agent 后台服务"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, AgentService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("上云助手")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .addAction(Notification.Action.Builder(null, "停止", stopPendingIntent).build())
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("上云助手")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .addAction(Notification.Action.Builder(null, "停止", stopPendingIntent).build())
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(content: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, createNotification(content))
        } catch (_: Exception) {}
    }
}