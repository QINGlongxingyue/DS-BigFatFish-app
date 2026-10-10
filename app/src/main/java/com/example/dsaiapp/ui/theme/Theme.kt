package com.example.dsaiapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DafeiyuColorScheme = darkColorScheme(
    primary = WhaleBlue,
    onPrimary = DeepSea,
    secondary = WhaleCyan,
    onSecondary = DeepSea,
    tertiary = WhaleBlueDark,
    background = DeepSea,
    onBackground = TextPrimary,
    surface = DeepSeaCard,
    onSurface = TextPrimary,
    surfaceVariant = DeepSeaSoft,
    onSurfaceVariant = TextSecondary,
)

/**
 * 固定用深色主题。
 *
 * <p>刻意不接 dynamicColor：模型的贴图颜色跟壁纸取色会打架，
 * 而且 Live2D 的 GL 背景色是我们写死的深蓝，跟着系统变色会出现色差接缝。
 */
@Composable
fun DSAIAPPTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DafeiyuColorScheme,
        typography = Typography,
        content = content,
    )
}
