package com.drawingtablet

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.drawingtablet.network.ConnectionState
import com.drawingtablet.network.ServiceDiscovery
import com.drawingtablet.network.NetworkClient
import com.drawingtablet.ui.ConnectionScreen
import com.drawingtablet.ui.StreamScreen
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Minimum gap between automatic reconnection attempts. */
private const val AUTO_RETRY_COOLDOWN_MS = 3_000L

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private var client: NetworkClient? = null
    private lateinit var serviceDiscovery: ServiceDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        // Switch from splash theme to regular theme
        setTheme(R.style.Theme_DrawingTablet)
        super.onCreate(savedInstanceState)
        
        serviceDiscovery = ServiceDiscovery(this)
        serviceDiscovery.start()

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system bars for immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DrawingTabletApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceDiscovery.stop()
        client?.close()
    }

    @Composable
    fun DrawingTabletApp() {
        var currentClient by remember { mutableStateOf<NetworkClient?>(null) }
        val connectionState by currentClient?.connectionState?.collectAsState()
            ?: remember { mutableStateOf(ConnectionState.Disconnected) }
        val discoveredServers by serviceDiscovery.servers.collectAsState()
        val scope = rememberCoroutineScope()
        
        val context = androidx.compose.ui.platform.LocalContext.current
        val prefs = remember { PreferencesManager(context) }

        fun connectTo(address: String, port: Int) {
            // Close existing client if any
            currentClient?.close()

            // Create new client and connect, telling it our display refresh
            // rate so the host can cap the stream rate to our panel.
            val refreshRate = this@MainActivity.display?.refreshRate?.roundToInt() ?: 0
            val newClient = NetworkClient(address, port, refreshRate)
            currentClient = newClient
            client = newClient

            scope.launch {
                newClient.connect()
            }
        }

        // Auto-connect logic (Triggers on app start or recovery)
        var nextAutoAttemptAt by remember { mutableLongStateOf(0L) }
        LaunchedEffect(discoveredServers, currentClient) {
            // Only auto-connect if no client exists AND auto-connect is enabled
            if (currentClient == null && prefs.shouldAutoConnect) {
                // Cooldown so a server that is listed but not accepting
                // connections is retried politely, not hammered.
                val waitMs = nextAutoAttemptAt - System.currentTimeMillis()
                if (waitMs > 0) {
                    android.util.Log.i(TAG, "auto-connect: cooling down ${waitMs}ms")
                    kotlinx.coroutines.delay(waitMs)
                }

                val savedHost = prefs.rememberedServerHost
                val savedPort = prefs.rememberedServerPort
                if (savedHost != null) {
                    val found = discoveredServers.find { it.host == savedHost && it.port == savedPort }
                    if (found != null) {
                        connectTo(found.host, found.port)
                    }
                }
            }
        }

        // Drop the dead client so the auto-connect effect above can retry.
        // The retry rate is bounded by ServiceDiscovery's refresh cycle (one
        // attempt per list emission), so the app recovers on its own after a
        // server restart instead of latching into a permanently disabled
        // state (the old "auto-connect disabled after 3 failures" behaviour).
        LaunchedEffect(connectionState) {
            when (connectionState) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    if (currentClient != null) {
                        nextAutoAttemptAt = System.currentTimeMillis() + AUTO_RETRY_COOLDOWN_MS
                        currentClient = null
                    }
                }
                else -> {}
            }
        }

        when (val state = connectionState) {
            is ConnectionState.Disconnected, is ConnectionState.Connecting, is ConnectionState.Error -> {
                ConnectionScreen(
                    connectionState = connectionState,
                    discoveredServers = discoveredServers,
                    onConnect = { address, port, remember ->
                        if (remember) {
                            prefs.rememberedServerHost = address
                            prefs.rememberedServerPort = port
                            prefs.shouldAutoConnect = true
                        } else {
                            // If manual connection without remember, disable auto-connect for future
                            prefs.shouldAutoConnect = false
                        }
                        connectTo(address, port)
                    }
                )
            }
            is ConnectionState.Connected -> {
                currentClient?.let { client ->
                    StreamScreen(
                        client = client,
                        videoPackets = client.videoPackets,
                        width = state.width,
                        height = state.height,
                        onDisconnect = {
                            client.disconnect()
                            currentClient = null
                        }
                    )
                }
            }
        }
    }
}
