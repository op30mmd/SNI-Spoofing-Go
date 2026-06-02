package com.example.snispoofing

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "SNISpoofing"
    }

    private var proxyService: ProxyService? = null
    private var isBound by mutableStateOf(false)
    private var isProxyRunning by mutableStateOf(false)
    private val logs = mutableStateListOf<String>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ProxyService.LocalBinder
            proxyService = binder.getService()
            isBound = true

            lifecycleScope.launch {
                proxyService?.isRunning?.collectLatest { running ->
                    isProxyRunning = running
                }
            }

            lifecycleScope.launch {
                proxyService?.logs?.collectLatest { log ->
                    logs.add(log)
                    if (logs.size > 1000) logs.removeAt(0)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Intent(this, ProxyService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            val darkTheme = isSystemInDarkTheme()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (!isGranted) {
                        Toast.makeText(this, "Notification permission is required for the foreground service", Toast.LENGTH_LONG).show()
                    }
                }
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

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
        val intent = Intent(this, ProxyService::class.java).apply {
            putExtra("config", config)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopProxy() {
        proxyService?.stopProxy()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Parcelize
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
) : Parcelable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SNISpoofingApp(
    isRunning: Boolean,
    logs: List<String>,
    onStart: (ProxyConfig) -> Unit,
    onStop: () -> Unit
) {
    var config by remember { mutableStateOf(ProxyConfig()) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SNI Spoofing (Root)") },
                actions = {
                    IconButton(onClick = {
                        val allLogs = logs.joinToString("\n")
                        clipboardManager.setText(AnnotatedString(allLogs))
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs")
                    }
                }
            )
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
