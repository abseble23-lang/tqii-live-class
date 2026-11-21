package com.tqii.liveclass

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- TQII brand palette (from the logo: black, gold, ivory) ----
val Night = Color(0xFF0E0E10)
val Night2 = Color(0xFF1A1A1D)
val Night3 = Color(0xFF26262B)
val Gold = Color(0xFFD4A24C)
val GoldLight = Color(0xFFF0C66B)
val GoldDeep = Color(0xFFA9792C)
val GoldSoft = Color(0xFFF6ECD6)
val Ivory = Color(0xFFFAF7F0)
val Line = Color(0xFFECE5D6)
val Ink = Color(0xFF16161A)
val Muted = Color(0xFF6E6A63)
val LiveRed = Color(0xFFE0393E)
val Success = Color(0xFF178A4C)

// old names used across the code
val Brand = Gold
val BrandSoft = GoldSoft

val GoldGradient = Brush.linearGradient(listOf(GoldLight, Gold, GoldDeep))
val NightGradient = Brush.verticalGradient(listOf(Color(0xFF000000), Night2))

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Night, onPrimary = Ivory,
            primaryContainer = GoldSoft, onPrimaryContainer = Ink,
            secondary = Gold, onSecondary = Night,
            background = Ivory, onBackground = Ink,
            surface = Color.White, onSurface = Ink, surfaceVariant = Color(0xFFF3EEE3),
            outline = Color(0xFFD9D1C1), error = LiveRed
        ),
        typography = Typography(
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        ),
        content = content
    )
}

/** The TQII mark. light = white/gold version for dark backgrounds. */
@Composable
fun Logo(size: Dp, light: Boolean = true, modifier: Modifier = Modifier) {
    Image(
        painterResource(if (light) R.drawable.logo_light else R.drawable.logo_dark),
        contentDescription = "TQII",
        modifier = modifier.size(size)
    )
}

/** Black header with logo + title, used at the top of main screens. */
@Composable
fun BrandHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().background(NightGradient).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ivory, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                subtitle?.let { Text(it, color = GoldLight, fontSize = 12.sp, maxLines = 1) }
            }
            actions()
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(GoldGradient))
    }
}

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) GoldGradient else Brush.linearGradient(listOf(Color(0xFFD8D2C6), Color(0xFFD8D2C6))))
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Night, strokeWidth = 2.dp)
        else Text(text, color = Night, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Night else Color.White)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = if (selected) GoldLight else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun InitialAvatar(name: String, size: Dp = 40.dp, dark: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (dark) Night else GoldSoft),
        contentAlignment = Alignment.Center
    ) {
        Text(name.trim().take(1).uppercase(), color = if (dark) GoldLight else GoldDeep, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) { Column(Modifier.padding(16.dp), content = content) }
}
