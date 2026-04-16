package com.helix.mic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object HelixTheme {
    // Backgrounds
    val Background = Color(0xFF0A0A0A)
    val SurfaceSubtle = Color(0xFF111111)

    // Borders & Lines
    val Hairline = Color(0xFF222222)
    val BorderDefault = Color(0xFF333333)

    // Text hierarchy
    val TextPrimary = Color(0xFFE8E8E8)
    val TextSecondary = Color(0xFF888888)
    val TextMuted = Color(0xFF444444)

    // Accent — recording only
    val RecordingRed = Color(0xFFCC3333)

    // Connected indicator — just slightly brighter than borders
    val Connected = Color(0xFF888888)

    // Shapes — sharp, technical
    val SharpShape = RoundedCornerShape(0.dp)
    val SlightShape = RoundedCornerShape(2.dp)
}

@Composable
fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(HelixTheme.Hairline)
    )
}

@Composable
fun SectionHeader(text: String) {
    Column {
        Text(
            text = text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            color = HelixTheme.TextSecondary,
            letterSpacing = 3.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        HairlineDivider()
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "<",
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = HelixTheme.TextSecondary,
            modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp)
        )
        Text(
            title,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            color = HelixTheme.TextSecondary,
            letterSpacing = 3.sp
        )
    }
}
