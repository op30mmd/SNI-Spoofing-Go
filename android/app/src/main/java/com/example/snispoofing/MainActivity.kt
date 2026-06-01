package com.example.snispoofing

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SNISpoofingApp(
                isRunning = isProxyRunning,
                onStart = { listen, connect, fakeSni, utls ->
                    startProxy(listen, connect, fakeSni, utls)
                },
                onStop = {
                    stopProxy()
                }
            )
        }
    }

    private fun startProxy(listen: String, connect: String, fakeSni: String, utls: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val binaryPath = ProxyHelper.getBinaryPath(this@MainActivity)
                val cmd = mutableListOf(binaryPath, "-listen", listen, "-connect", connect)
                if (fakeSni.isNotBlank()) {
                    cmd.add("-fake-sni")
                    cmd.add(fakeSni)
                }
                if (utls.isNotBlank()) {
                    cmd.add("-utls")
                    cmd.add(utls)
                }

                stopProxy() // Ensure previous process is killed

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
                }

                val exitCode = process.waitFor()
                Log.d(TAG, "Proxy exited with code $exitCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run proxy", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isProxyRunning = false
                }
            }
        }
    }

    private fun stopProxy() {
        proxyProcess?.destroy()
        proxyProcess = null
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SNISpoofingApp(
    isRunning: Boolean,
    onStart: (String, String, String, String) -> Unit,
    onStop: () -> Unit
) {
    var listen by remember { mutableStateOf("127.0.0.1:40443") }
    var connect by remember { mutableStateOf("104.19.229.21:443") }
    var fakeSni by remember { mutableStateOf("hcaptcha.com") }
    var utls by remember { mutableStateOf("firefox") }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("SNI Spoofing") })
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = listen,
                    onValueChange = { listen = it },
                    label = { Text("Listen Address") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                )
                OutlinedTextField(
                    value = connect,
                    onValueChange = { connect = it },
                    label = { Text("Connect Address") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                )
                OutlinedTextField(
                    value = fakeSni,
                    onValueChange = { fakeSni = it },
                    label = { Text("Fake SNI") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                )
                OutlinedTextField(
                    value = utls,
                    onValueChange = { utls = it },
                    label = { Text("uTLS Fingerprint") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!isRunning) {
                    Button(
                        onClick = {
                            onStart(listen, connect, fakeSni, utls)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Proxy")
                    }
                } else {
                    Button(
                        onClick = {
                            onStop()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stop Proxy")
                    }
                }

                if (isRunning) {
                    Text("Proxy is running...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
