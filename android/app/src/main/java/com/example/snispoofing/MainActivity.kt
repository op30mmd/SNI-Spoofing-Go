package com.example.snispoofing

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "SNISpoofing"
    }

    private var proxyProcess: Process? = null
    private var isProxyRunning by mutableStateOf(false)
    private val logs = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                SNISpoofingApp(
                    isRunning = isProxyRunning,
                    logs = logs,
                    onStart = { config ->
                        startProxy(config)
                    },
                    onStop = {
                        stopProxy()
                    }
                )
            }
        }
    }

    private fun startProxy(config: ProxyConfig) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val binaryPath = ProxyHelper.getBinaryPath(this@MainActivity)

                // Build command
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

                // Request root by running via su
                val cmd = mutableListOf("su", "-c", args.joinToString(" ") { "'$it'" })

                withContext(Dispatchers.Main) {
                    logs.clear()
                    logs.add("Starting proxy with root...")
                }

                stopProxy()

                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                proxyProcess = process

                withContext(Dispatchers.Main) {
                    isProxyRunning = true
                }

                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "Proxy: $line")
                    withContext(Dispatchers.Main) {
                        logs.add(line ?: "")
                        if (logs.size > 1000) logs.removeAt(0)
                    }
                }

                val exitCode = process.waitFor()
                withContext(Dispatchers.Main) {
                    logs.add("Proxy exited with code $exitCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run proxy", e)
                withContext(Dispatchers.Main) {
                    logs.add("Error: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isProxyRunning = false
                }
            }
        }
    }

    private fun stopProxy() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Since we ran via su, we might need to kill it specifically if destroy() doesn't work well on the su wrapper
            proxyProcess?.destroy()
            proxyProcess = null
            // Optional: run "su -c killall sni-spoofing" or similar if needed
        }
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }
}

data class ProxyConfig(
    val listen: String = "127.0.0.1:40443",
    val connect: String = "104.19.229.21:443",
    val fakeSni: String = "hcaptcha.com",
    val utls: String = "firefox",
    val fakeRepeat: Int = 1,
    val fakeDelay: String = "2ms",
    val ackTimeout: String = "2s",
    val injector: String = "active",
    val enableFragment: Boolean = false,
    val fragmentDelay: String = "500ms",
    val sniChunk: Int = 3
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SNISpoofingApp(
    isRunning: Boolean,
    logs: List<String>,
    onStart: (ProxyConfig) -> Unit,
    onStop: () -> Unit
) {
    var config by remember { mutableStateOf(ProxyConfig()) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("SNI Spoofing (Root)") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = config.listen,
                    onValueChange = { config = config.copy(listen = it) },
                    label = { Text("Listen Address") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                )
                OutlinedTextField(
                    value = config.connect,
                    onValueChange = { config = config.copy(connect = it) },
                    label = { Text("Connect Address") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                )
                OutlinedTextField(
                    value = config.fakeSni,
                    onValueChange = { config = config.copy(fakeSni = it) },
                    label = { Text("Fake SNI") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = config.utls,
                        onValueChange = { config = config.copy(utls = it) },
                        label = { Text("uTLS") },
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning
                    )
                    OutlinedTextField(
                        value = config.injector,
                        onValueChange = { config = config.copy(injector = it) },
                        label = { Text("Injector") },
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = config.fakeRepeat.toString(),
                        onValueChange = { config = config.copy(fakeRepeat = it.toIntOrNull() ?: 1) },
                        label = { Text("Repeat") },
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning
                    )
                    OutlinedTextField(
                        value = config.fakeDelay,
                        onValueChange = { config = config.copy(fakeDelay = it) },
                        label = { Text("Delay") },
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning
                    )
                    OutlinedTextField(
                        value = config.ackTimeout,
                        onValueChange = { config = config.copy(ackTimeout = it) },
                        label = { Text("ACK TO") },
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning
                    )
                }

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = config.enableFragment,
                        onCheckedChange = { config = config.copy(enableFragment = it) },
                        enabled = !isRunning
                    )
                    Text("Enable Fragmentation")
                }

                if (config.enableFragment) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = config.fragmentDelay,
                            onValueChange = { config = config.copy(fragmentDelay = it) },
                            label = { Text("Frag Delay") },
                            modifier = Modifier.weight(1f),
                            enabled = !isRunning
                        )
                        OutlinedTextField(
                            value = config.sniChunk.toString(),
                            onValueChange = { config = config.copy(sniChunk = it.toIntOrNull() ?: 3) },
                            label = { Text("SNI Chunk") },
                            modifier = Modifier.weight(1f),
                            enabled = !isRunning
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (!isRunning) {
                    Button(
                        onClick = { onStart(config) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Proxy (Root)")
                    }
                } else {
                    Button(
                        onClick = { onStop() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stop Proxy")
                    }
                }
            }

            // Logs area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color.Black)
                    .padding(8.dp)
            ) {
                val logScrollState = rememberScrollState()
                LaunchedEffect(logs.size) {
                    logScrollState.animateScrollTo(logScrollState.maxValue)
                }
                Text(
                    text = logs.joinToString("\n"),
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(logScrollState)
                )
            }

            // Padding for navigation bar
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
        }
    }
}
