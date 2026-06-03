package com.example.snispoofing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProxyService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var proxyProcess: Process? = null

    private val _logs = MutableSharedFlow<String>(replay = 100)
    val logs = _logs.asSharedFlow()

    private val _isRunning = MutableSharedFlow<Boolean>(replay = 1)
    val isRunning = _isRunning.asSharedFlow()

    private var currentConfig: ProxyConfig? = null

    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "ProxyServiceChannel"
        private const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("config", ProxyConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<ProxyConfig>("config")
        }
        if (config != null) {
            startProxy(config)
        }
        return START_STICKY
    }

    private fun startProxy(config: ProxyConfig) {
        if (proxyProcess != null && config == currentConfig) return
        currentConfig = config

        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                mutex.withLock {
                    stopProxyProcessInternal()
                    _isRunning.emit(true)
                }
                val binaryPath = ProxyHelper.getBinaryPath(this@ProxyService)
                val args = mutableListOf(binaryPath, "-listen", config.listen, "-connect", config.connect)
                if (config.fakeSni.isNotBlank()) args.addAll(listOf("-fake-sni", config.fakeSni))
                if (config.utls.isNotBlank()) args.addAll(listOf("-utls", config.utls))
                args.addAll(listOf("-fake-repeat", config.fakeRepeat.toString()))
                args.addAll(listOf("-fake-delay", config.fakeDelay))
                args.addAll(listOf("-ack-timeout", config.ackTimeout))
                args.addAll(listOf("-injector", config.injector))
                if (config.enableFragment) {
                    args.add("-enable-fragment")
                    args.addAll(listOf("-fragment-delay", config.fragmentDelay))
                    args.addAll(listOf("-sni-chunk", config.sniChunk.toString()))
                }

                val cmd = mutableListOf("su", "-c", args.joinToString(" ") { "'$it'" })

                _logs.emit("Starting proxy with root...")

                val process = withContext(Dispatchers.IO) {
                    ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start()
                }
                mutex.withLock {
                    proxyProcess = process
                }

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                try {
                    var line: String?
                    while (isActive) {
                        line = withContext(Dispatchers.IO) { reader.readLine() }
                        if (line == null) break
                        Log.d(TAG, "Proxy: $line")
                        _logs.emit(line)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Log reading error", e)
                        _logs.emit("Log reading error: ${e.message}")
                    }
                } finally {
                    try { reader.close() } catch (ignored: Exception) {}
                }

                val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
                _logs.emit("Proxy exited with code $exitCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run proxy", e)
                _logs.emit("Error: ${e.message}")
            } finally {
                proxyProcess = null
                _isRunning.emit(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
    }

    fun stopProxy() {
        serviceScope.launch {
            mutex.withLock {
                stopProxyProcessInternal()
            }
            _isRunning.emit(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private suspend fun stopProxyProcessInternal() {
        val process = proxyProcess
        proxyProcess = null
        withContext(NonCancellable) {
            killAllProxyProcessesInternal()
            if (process != null) {
                withContext(Dispatchers.IO) {
                    process.destroy()
                }
            }
        }
    }

    private suspend fun killAllProxyProcessesInternal() {
        withContext(Dispatchers.IO) {
            try {
                val binaryPath = ProxyHelper.getBinaryPath(this@ProxyService)
                val binaryName = binaryPath.substringAfterLast('/')

                // Try graceful kill first then SIGKILL
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -SIGTERM $binaryName")).waitFor()
                delay(500)
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -SIGKILL $binaryName")).waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "Error killing orphaned proxy processes", e)
            }
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SNI Spoofing Active")
            .setContentText("The proxy is running in the background.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Proxy Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopProxy()
        serviceScope.cancel()
        super.onDestroy()
    }
}
