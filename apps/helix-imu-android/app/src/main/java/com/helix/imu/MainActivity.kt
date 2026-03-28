package com.helix.imu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: HelixBleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = HelixBleManager(this)
        enableEdgeToEdge()
        setContent { HelixIMUApp(bleManager) }
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
    val isCalibrated by bleManager.isCalibrated.collectAsState()

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
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .statusBarsPadding()
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Helix IMU", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    StatusDot(connectionState)
                }
                Text(
                    text = when (connectionState) {
                        ConnectionState.DISCONNECTED -> "Not connected"
                        ConnectionState.SCANNING -> "Scanning..."
                        ConnectionState.CONNECTING -> "Connecting..."
                        ConnectionState.CONNECTED -> deviceAddress ?: "Connected"
                    },
                    fontSize = 13.sp, color = Color.Gray
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (connectionState == ConnectionState.CONNECTED) {
                    // Orientation cards
                    OrientationDisplay(orientation)

                    Spacer(modifier = Modifier.height(12.dp))

                    // Calibrate button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { bleManager.calibrate() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1A3A1A)
                            )
                        ) {
                            Text(
                                text = if (isCalibrated) "✓ Recalibrate" else "Calibrate",
                                color = Color(0xFF00FF88)
                            )
                        }
                        if (isCalibrated) {
                            OutlinedButton(
                                onClick = { bleManager.resetCalibration() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.Gray
                                )
                            ) {
                                Text("Reset", color = Color.Gray)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3D visualiser
                    HeadVisualiser(orientation)

                    Spacer(modifier = Modifier.height(12.dp))

                    // Log
                    LogView(log, onClear = { bleManager.clearLog() })
                }

                Spacer(modifier = Modifier.weight(1f))

                // Connect/Disconnect
                if (!blePermissions.allPermissionsGranted) {
                    Button(
                        onClick = { blePermissions.launchMultiplePermissionRequest() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Grant Bluetooth Permissions") }
                } else {
                    Button(
                        onClick = {
                            if (connectionState == ConnectionState.DISCONNECTED) bleManager.startScan()
                            else bleManager.disconnect()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (connectionState == ConnectionState.DISCONNECTED)
                                MaterialTheme.colorScheme.primary else Color(0xFF333333)
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
fun HeadVisualiser(orientation: Orientation) {
    val rollRad = orientation.roll * PI.toFloat() / 180f
    val pitchRad = orientation.pitch * PI.toFloat() / 180f
    val yawRad = orientation.yaw * PI.toFloat() / 180f

    Card(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = minOf(cx, cy) * 0.55f

                // Draw axes labels
                drawGridLines(cx, cy, r)

                // Forward direction arrow — shows yaw rotation horizontally, pitch vertically
                val fwdX = cx + r * sin(yawRad) * cos(pitchRad)
                val fwdY = cy - r * cos(yawRad) * cos(pitchRad) - r * 0.4f * sin(pitchRad)

                drawArrow(
                    start = Offset(cx, cy),
                    end = Offset(fwdX, fwdY),
                    color = Color(0xFF00D4FF),
                    strokeWidth = 4f
                )

                // Up direction — shows roll
                val upX = cx + r * 0.5f * (-sin(rollRad))
                val upY = cy - r * 0.5f * cos(rollRad)

                drawArrow(
                    start = Offset(cx, cy),
                    end = Offset(upX, upY),
                    color = Color(0xFF00FF88),
                    strokeWidth = 3f
                )

                // Draw centre dot
                drawCircle(Color.White, radius = 5f, center = Offset(cx, cy))
            }

            // Legend
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Color(0xFF00D4FF), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("Forward", fontSize = 9.sp, color = Color.Gray)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Color(0xFF00FF88), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("Up", fontSize = 9.sp, color = Color.Gray)
                }
            }
        }
    }
}

fun DrawScope.drawGridLines(cx: Float, cy: Float, r: Float) {
    val gridColor = Color(0xFF222222)
    // Horizontal
    drawLine(gridColor, Offset(cx - r * 1.2f, cy), Offset(cx + r * 1.2f, cy), strokeWidth = 1f)
    // Vertical
    drawLine(gridColor, Offset(cx, cy - r * 1.2f), Offset(cx, cy + r * 1.2f), strokeWidth = 1f)
    // Circle
    drawCircle(gridColor, radius = r, center = Offset(cx, cy), style = Stroke(1f))
}

fun DrawScope.drawArrow(start: Offset, end: Offset, color: Color, strokeWidth: Float) {
    drawLine(color, start, end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    // Arrowhead
    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1f) return
    val nx = dx / len
    val ny = dy / len
    val arrowLen = 16f
    val arrowAngle = 0.4f
    val p1 = Offset(
        end.x - arrowLen * (nx * cos(arrowAngle) - ny * sin(arrowAngle)),
        end.y - arrowLen * (ny * cos(arrowAngle) + nx * sin(arrowAngle))
    )
    val p2 = Offset(
        end.x - arrowLen * (nx * cos(arrowAngle) + ny * sin(arrowAngle)),
        end.y - arrowLen * (ny * cos(arrowAngle) - nx * sin(arrowAngle))
    )
    val path = Path().apply {
        moveTo(end.x, end.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        close()
    }
    drawPath(path, color)
}

@Composable
fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.DISCONNECTED -> Color(0xFF555555)
        ConnectionState.SCANNING, ConnectionState.CONNECTING -> Color(0xFFFFAA00)
        ConnectionState.CONNECTED -> Color(0xFF00FF88)
    }
    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
fun OrientationDisplay(orientation: Orientation) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrientationCard("ROLL", orientation.roll, Color(0xFF00D4FF), Modifier.weight(1f))
        OrientationCard("PITCH", orientation.pitch, Color(0xFF00FF88), Modifier.weight(1f))
        OrientationCard("YAW", orientation.yaw, Color(0xFFFF6B6B), Modifier.weight(1f))
    }
}

@Composable
fun OrientationCard(label: String, value: Float, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier, shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("%.1f°".format(value), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun LogView(log: List<Orientation>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) scope.launch { listState.animateScrollToItem(log.size - 1) }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Log (${log.size})", fontSize = 13.sp, color = Color.Gray)
            TextButton(onClick = onClear) { Text("Clear", fontSize = 12.sp, color = Color.Gray) }
        }
        Card(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))
        ) {
            LazyColumn(state = listState, modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(log) { o ->
                    Text(
                        text = "R:${"%6.1f".format(o.roll)}° P:${"%6.1f".format(o.pitch)}° Y:${"%6.1f".format(o.yaw)}°",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888888)
                    )
                }
            }
        }
    }
}
