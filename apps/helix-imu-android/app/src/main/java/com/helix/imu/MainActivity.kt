package com.helix.imu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: HelixBleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = HelixBleManager(this)
        enableEdgeToEdge()
        setContent {
            HelixIMUApp(bleManager)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HelixIMUApp(bleManager: HelixBleManager) {
    val connectionState by bleManager.connectionState.collectAsState()
    val orientation by bleManager.orientation.collectAsState()
    val deviceAddress by bleManager.deviceAddress.collectAsState()
    val log by bleManager.log.collectAsState()

    val blePermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    )

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0D0D0D),
            surface = Color(0xFF1A1A1A),
            primary = Color(0xFF00D4FF),
            onBackground = Color.White,
            onSurface = Color.White,
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .statusBarsPadding()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Helix IMU",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatusDot(connectionState)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = when (connectionState) {
                        ConnectionState.DISCONNECTED -> "Not connected"
                        ConnectionState.SCANNING -> "Scanning..."
                        ConnectionState.CONNECTING -> "Connecting..."
                        ConnectionState.CONNECTED -> deviceAddress ?: "Connected"
                    },
                    fontSize = 13.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Orientation cards
                if (connectionState == ConnectionState.CONNECTED) {
                    OrientationDisplay(orientation)
                    Spacer(modifier = Modifier.height(16.dp))
                    LogView(log, onClear = { bleManager.clearLog() })
                }

                Spacer(modifier = Modifier.weight(1f))

                // Connect / Disconnect button
                if (!blePermissions.allPermissionsGranted) {
                    Button(
                        onClick = { blePermissions.launchMultiplePermissionRequest() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Bluetooth Permissions")
                    }
                } else {
                    Button(
                        onClick = {
                            if (connectionState == ConnectionState.DISCONNECTED) {
                                bleManager.startScan()
                            } else {
                                bleManager.disconnect()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (connectionState == ConnectionState.DISCONNECTED)
                                MaterialTheme.colorScheme.primary
                            else
                                Color(0xFF333333)
                        )
                    ) {
                        Text(
                            text = when (connectionState) {
                                ConnectionState.DISCONNECTED -> "Connect to Helix IMU"
                                ConnectionState.SCANNING -> "Scanning..."
                                ConnectionState.CONNECTING -> "Connecting..."
                                ConnectionState.CONNECTED -> "Disconnect"
                            },
                            color = if (connectionState == ConnectionState.DISCONNECTED)
                                Color.Black else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.DISCONNECTED -> Color(0xFF555555)
        ConnectionState.SCANNING, ConnectionState.CONNECTING -> Color(0xFFFFAA00)
        ConnectionState.CONNECTED -> Color(0xFF00FF88)
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun OrientationDisplay(orientation: Orientation) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OrientationCard("ROLL", orientation.roll, Color(0xFF00D4FF), Modifier.weight(1f))
        OrientationCard("PITCH", orientation.pitch, Color(0xFF00FF88), Modifier.weight(1f))
        OrientationCard("YAW", orientation.yaw, Color(0xFFFF6B6B), Modifier.weight(1f))
    }
}

@Composable
fun OrientationCard(label: String, value: Float, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "%.1f°".format(value),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun LogView(log: List<Orientation>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(log.size - 1) }
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Log (${log.size})", fontSize = 13.sp, color = Color.Gray)
            TextButton(onClick = onClear) {
                Text("Clear", fontSize = 12.sp, color = Color.Gray)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(log) { o ->
                    Text(
                        text = "R:${"%6.1f".format(o.roll)}° P:${"%6.1f".format(o.pitch)}° Y:${"%6.1f".format(o.yaw)}°",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF888888)
                    )
                }
            }
        }
    }
}
