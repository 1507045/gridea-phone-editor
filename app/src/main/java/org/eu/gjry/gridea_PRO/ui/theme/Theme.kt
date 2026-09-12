package org.eu.gjry.gridea_PRO.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 刻意只做浅色。这是个写作工具，不是阅读器——橙 + 白是这个 app 的脸，
 * 跟着系统切成深色反而把脸丢了。也没开动态取色，否则安卓 12+ 会把橙色换掉。
 */
private val LightColors = lightColorScheme(
    primary = BrandOrange,
    onPrimary = Color.White,
    primaryContainer = BrandOrangeSoft,
    onPrimaryContainer = BrandOrangePress,

    secondary = BrandOrangeDeep,
    onSecondary = Color.White,
    secondaryContainer = BrandOrangeSoft,
    onSecondaryContainer = BrandOrangePress,

    tertiary = BrandOrange,
    onTertiary = Color.White,

    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperSunken,
    onSurfaceVariant = InkSoft,

    outline = LineSoft,
    outlineVariant = BrandOrangeLine,

    error = DangerRed,
    onError = Color.White,
)

@Composable
fun GrideaPROTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content,
    )
}
