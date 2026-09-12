package org.eu.gjry.gridea_PRO.ui.common

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.eu.gjry.gridea_PRO.ui.icons.GIcons
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrange
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangeLine
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangePress
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangeSoft
import org.eu.gjry.gridea_PRO.ui.theme.DangerRed
import org.eu.gjry.gridea_PRO.ui.theme.Ink
import org.eu.gjry.gridea_PRO.ui.theme.InkFaint
import org.eu.gjry.gridea_PRO.ui.theme.InkSoft
import org.eu.gjry.gridea_PRO.ui.theme.LineSoft
import org.eu.gjry.gridea_PRO.ui.theme.Paper
import org.eu.gjry.gridea_PRO.ui.theme.PaperSoft
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val DATETIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

fun LocalDateTime.minuteText(): String = format(DATETIME_FMT)
fun LocalDateTime.dateText(): String = format(DATE_FMT)

/** 把 Markdown 正文压成一句纯文本，用于列表里的摘要。 */
fun plainSnippet(content: String, max: Int = 90): String {
    var s = content
    s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
    s = s.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
    s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    s = s.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
    s = s.replace(Regex("[`*_>#~]"), "")
    s = s.replace(Regex("\\s+"), " ").trim()
    return if (s.length <= max) s else s.take(max).trimEnd() + "…"
}

// ───────────────────────────── 基础件 ─────────────────────────────

/** 圆角标签。选中是实心橙，未选中是白底细描边。 */
@Composable
fun TagPill(
    text: String,
    selected: Boolean = false,
    accent: Color = BrandOrange,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    val bg = if (selected) accent else Paper
    val fg = if (selected) Color.White else InkSoft
    val border = if (selected) accent else LineSoft
    Surface(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .border(1.dp, border, shape),
        shape = shape,
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 9.dp else 12.dp,
                vertical = if (compact) 4.dp else 6.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 闪念标签带个 # 前缀，跟正文里的写法对上
            if (!selected && compact) {
                Text(
                    text = "#",
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = text,
                color = if (!selected && compact) accent else fg,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Paper,
        border = androidx.compose.foundation.BorderStroke(1.dp, LineSoft),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = Ink,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = InkFaint,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
fun EmptyHint(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = BrandOrangeSoft,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = BrandOrangeLine, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = InkSoft, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = InkFaint, fontSize = 12.sp)
    }
}

// ───────────────────────────── 输入件 ─────────────────────────────

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 12.sp) },
        placeholder = {
            if (placeholder.isNotEmpty()) {
                Text(placeholder, color = InkFaint, fontSize = 14.sp)
            }
        },
        trailingIcon = trailing,
        supportingText = supporting?.let {
            { Text(it, fontSize = 11.sp, color = InkFaint) }
        },
        singleLine = singleLine,
        textStyle = TextStyle(fontSize = 14.sp, color = Ink),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandOrange,
            unfocusedBorderColor = LineSoft,
            focusedLabelColor = BrandOrange,
            unfocusedLabelColor = InkSoft,
            cursorColor = BrandOrange,
            focusedContainerColor = Paper,
            unfocusedContainerColor = PaperSoft,
        ),
    )
}

/** 分段选择器。用一排等宽格子做，比 Slider 之类直白。 */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PaperSoft,
        border = androidx.compose.foundation.BorderStroke(1.dp, LineSoft),
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            options.forEach { (value, label) ->
                val active = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .background(
                            color = if (active) BrandOrange else Color.Transparent,
                            shape = RoundedCornerShape(9.dp),
                        )
                        .clickable { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (active) Color.White else InkSoft,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 开关行。 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 14.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                HintText(subtitle)
            }
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BrandOrange,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = InkFaint,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** 日期 + 时间选择。走系统原生对话框，稳。 */
@Composable
fun DateTimePicker(
    value: LocalDateTime,
    onChange: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
    showValue: Boolean = true,
) {
    val ctx = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showValue) {
            Text(
                text = value.minuteText(),
                color = BrandOrangePress,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        SmallOutlinedButton(
            text = "改日期",
            onClick = {
                DatePickerDialog(
                    ctx,
                    { _, y, m, d ->
                        onChange(value.withYear(y).withMonth(m + 1).withDayOfMonth(d))
                    },
                    value.year,
                    value.monthValue - 1,
                    value.dayOfMonth,
                ).show()
            },
        )
        Spacer(Modifier.width(8.dp))
        SmallOutlinedButton(
            text = "改时间",
            onClick = {
                TimePickerDialog(
                    ctx,
                    { _, h, mi ->
                        onChange(value.withHour(h).withMinute(mi).withSecond(0).withNano(0))
                    },
                    value.hour,
                    value.minute,
                    true,
                ).show()
            },
        )
    }
}

@Composable
fun SmallOutlinedButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(32.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(9.dp),
        color = Paper,
        border = androidx.compose.foundation.BorderStroke(1.dp, BrandOrangeLine),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = BrandOrange, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(text, color = BrandOrangePress, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier
            .height(46.dp)
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) BrandOrange else LineSoft,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    null,
                    tint = if (enabled) Color.White else InkFaint,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text,
                color = if (enabled) Color.White else InkFaint,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val color = when {
        !enabled -> InkFaint
        danger -> DangerRed
        else -> InkSoft
    }
    Surface(
        modifier = modifier
            .height(46.dp)
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = PaperSoft,
        border = androidx.compose.foundation.BorderStroke(1.dp, LineSoft),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 多选 + 可新增 的收缩菜单。
 * 标签和分类共用这套逻辑——电脑端也是同一套，没必要写两遍。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiSelectSection(
    title: String,
    icon: ImageVector,
    options: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = BrandOrange,
    emptyHint: String = "还没有可选项目",
    createPlaceholder: String = "输入名称后新增",
) {
    var expanded by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        // 收缩头：点一下展开/收起
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (selected.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(percent = 50), color = BrandOrangeSoft) {
                    Text(
                        text = "${selected.size}",
                        color = BrandOrangePress,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = if (expanded) "收起" else "展开",
                tint = InkFaint,
                modifier = Modifier.size(18.dp),
            )
        }

        // 已选的始终显示，收起时也知道选了啥
        val chosen = if (selected.isEmpty()) {
            listOf("未选择")
        } else {
            selected
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (selected.isEmpty()) {
                Text("未选择", color = InkFaint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
            } else {
                chosen.forEach { name ->
                    TagPill(text = name, selected = true, accent = accent, compact = true, onClick = { onToggle(name) })
                }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            if (options.isEmpty()) {
                HintText(emptyHint)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    options.forEach { name ->
                        TagPill(
                            text = name,
                            selected = name in selected,
                            accent = accent,
                            compact = true,
                            onClick = { onToggle(name) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    LabeledField(
                        label = "新增$title",
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = createPlaceholder,
                    )
                }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(
                    text = "添加",
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            if (name !in selected) onCreate(name)
                            newName = ""
                        }
                    },
                )
            }
        }
    }
}

// ───────────────────────────── 自绘底部面板 ─────────────────────────────

/**
 * 自己画的底部面板，没用 Material 的 ModalBottomSheet。
 * 理由很实际：官方那个还在实验期，签名换过几轮；这里的交互简单到不值得赌 API 稳定性。
 */
@Composable
fun BottomPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.88f,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    BackHandler(enabled = true) { onDismiss() }

    Box(modifier = Modifier.fillMaxSize().zIndex(20f)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(heightFraction),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = Paper,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .background(LineSoft, RoundedCornerShape(2.dp)),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    content = content,
                )
            }
        }
    }
}

/** 面板顶部的标题条：左标题右关闭。 */
@Composable
fun PanelHeader(title: String, onClose: () -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                HintText(subtitle)
            }
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(PaperSoft, CircleShape)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(GIcons.Close, contentDescription = "关闭", tint = InkSoft, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink) },
        text = { Text(text, fontSize = 13.sp, color = InkSoft) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = DangerRed, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = InkSoft) }
        },
        containerColor = Paper,
        shape = RoundedCornerShape(16.dp),
    )
}

/** 空数据时给个能点的按钮，别让人对着空白页发愣。 */
@Composable
fun InlineNotice(text: String, modifier: Modifier = Modifier, accent: Color = BrandOrangePress) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = BrandOrangeSoft,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            color = accent,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

/** 顶部细进度条：同步进行中时挂在界面顶端，不挡操作。 */
@Composable
fun TopProgressStrip(fraction: Float?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(LineSoft),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction?.coerceIn(0.02f, 1f) ?: 0.25f)
                .background(BrandOrange),
        )
    }
}

/** 高度受限的文本，避免摘要撑爆卡片。 */
@Composable
fun ClampedText(text: String, maxLines: Int, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.heightIn(min = 0.dp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style,
    )
}
