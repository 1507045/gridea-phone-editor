package org.eu.gjry.gridea_PRO.ui.articles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.gjry.gridea_PRO.data.Post
import org.eu.gjry.gridea_PRO.data.PostStatus
import org.eu.gjry.gridea_PRO.data.Slug
import org.eu.gjry.gridea_PRO.state.AppState
import org.eu.gjry.gridea_PRO.ui.common.BottomPanel
import org.eu.gjry.gridea_PRO.ui.common.ConfirmDialog
import org.eu.gjry.gridea_PRO.ui.common.DateTimePicker
import org.eu.gjry.gridea_PRO.ui.common.GhostButton
import org.eu.gjry.gridea_PRO.ui.common.HintText
import org.eu.gjry.gridea_PRO.ui.common.LabeledField
import org.eu.gjry.gridea_PRO.ui.common.MarkdownPreview
import org.eu.gjry.gridea_PRO.ui.common.MultiSelectSection
import org.eu.gjry.gridea_PRO.ui.common.PanelHeader
import org.eu.gjry.gridea_PRO.ui.common.PrimaryButton
import org.eu.gjry.gridea_PRO.ui.common.SegmentedControl
import org.eu.gjry.gridea_PRO.ui.common.SwitchRow
import org.eu.gjry.gridea_PRO.ui.common.TagPill
import org.eu.gjry.gridea_PRO.ui.icons.GIcons
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrange
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangeSoft
import org.eu.gjry.gridea_PRO.ui.theme.Ink
import org.eu.gjry.gridea_PRO.ui.theme.InkFaint
import org.eu.gjry.gridea_PRO.ui.theme.InkSoft
import org.eu.gjry.gridea_PRO.ui.theme.LineSoft
import org.eu.gjry.gridea_PRO.ui.theme.Paper
import org.eu.gjry.gridea_PRO.ui.theme.PaperSoft
import org.eu.gjry.gridea_PRO.ui.theme.PaperSunken
import java.time.LocalDateTime

/**
 * 文章编辑页。
 *
 * 编辑/预览双模式、发布设置收在底部面板里。
 * 根布局挂 imePadding，键盘弹出时整页上缩而不是把输入框顶出屏幕。
 */
@Composable
fun ArticleEditorScreen(
    state: AppState,
    editing: Post?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val isNew = editing == null

    var title by remember { mutableStateOf(editing?.title.orEmpty()) }
    var content by remember { mutableStateOf(editing?.content.orEmpty()) }
    var fileName by remember { mutableStateOf(editing?.fileName.orEmpty()) }
    var createdAt by remember { mutableStateOf(editing?.createdAt ?: LocalDateTime.now()) }
    var status by remember { mutableStateOf(editing?.status ?: PostStatus.PUBLISHED) }
    var isTop by remember { mutableStateOf(editing?.isTop ?: false) }
    var selectedTags by remember { mutableStateOf(editing?.tags.orEmpty()) }
    var selectedCategories by remember { mutableStateOf(editing?.categories.orEmpty()) }

    // 用户手动改过文件名之后，就不再跟着标题自动变了
    var fileNameTouched by remember { mutableStateOf(editing?.fileName?.isNotBlank() == true) }
    var preview by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val initial = remember(editing?.fileName) {
        listOf(
            editing?.title.orEmpty(),
            editing?.content.orEmpty(),
            editing?.tags?.joinToString(",").orEmpty(),
            editing?.categories?.joinToString(",").orEmpty(),
            editing?.status?.name.orEmpty(),
            (editing?.isTop ?: false).toString(),
        )
    }
    val current = listOf(
        title,
        content,
        selectedTags.joinToString(","),
        selectedCategories.joinToString(","),
        status.name,
        isTop.toString(),
    )
    val dirty = isNew || current != initial

    val allTags = remember(state.posts.value, state.tags.value) { state.tagOptions() }
    val allCategories = remember(state.posts.value, state.categories.value) { state.categoryOptions() }

    val effectiveFileName: String =
        fileName.trim().ifBlank { Slug.fromTitle(title) }

    fun doPublish() {
        if (saving) return
        saving = true
        val draft = Post(
            id = editing?.id.orEmpty(),
            title = title.trim().ifBlank { "无标题" },
            createdAt = createdAt,
            tags = selectedTags,
            categories = selectedCategories,
            published = status != PostStatus.DRAFT,
            hideInList = status == PostStatus.HIDDEN,
            isTop = isTop,
            feature = editing?.feature.orEmpty(),
            content = content,
            fileName = effectiveFileName,
        )
        state.savePost(draft, editing?.fileName) { ok ->
            saving = false
            if (ok) onSaved()
        }
    }

    fun tryLeave() {
        if (dirty) askDiscard = true else onBack()
    }

    BackHandler(enabled = true) { tryLeave() }

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {

            // ── 顶栏 ──────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PaperSoft, CircleShape)
                        .clickable { tryLeave() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GIcons.Back, "返回", tint = InkSoft, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isNew) "写文章" else "编辑文章",
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "$effectiveFileName.md",
                        color = InkFaint,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 预览 / 编辑 切换
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = if (preview) BrandOrange else PaperSoft,
                    modifier = Modifier.clickable { preview = !preview },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (preview) GIcons.Pencil else GIcons.Eye,
                            contentDescription = if (preview) "回到编辑" else "预览",
                            tint = if (preview) Color.White else InkSoft,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (preview) "编辑" else "预览",
                            color = if (preview) Color.White else InkSoft,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // ── 正文区 ────────────────────────────────
            if (preview) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                ) {
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            color = Ink,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 31.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = createdAt.toString().replace('T', ' ').take(16),
                            color = InkFaint,
                            fontSize = 11.5.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    MarkdownPreview(markdown = content)
                    Spacer(Modifier.height(60.dp))
                }
            } else {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // 标题
                    Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                        if (title.isEmpty()) {
                            Text("标题", color = InkFaint, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        }
                        BasicTextField(
                            value = title,
                            onValueChange = {
                                title = it
                                if (!fileNameTouched) fileName = Slug.fromTitle(it)
                            },
                            textStyle = TextStyle(
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink,
                                lineHeight = 29.sp,
                            ),
                            cursorBrush = SolidColor(BrandOrange),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(LineSoft),
                    )

                    // 正文
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        if (content.isEmpty()) {
                            Text(
                                "开始写吧。支持 Markdown：## 标题、**加粗**、`代码`、> 引用…",
                                color = InkFaint,
                                fontSize = 14.sp,
                                lineHeight = 24.sp,
                            )
                        }
                        BasicTextField(
                            value = content,
                            onValueChange = { content = it },
                            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 25.sp, color = Ink),
                            cursorBrush = SolidColor(BrandOrange),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // ── 底栏 ──────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LineSoft),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = status.label + if (isTop) " · 置顶" else "",
                            color = BrandOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (selectedTags.isEmpty() && selectedCategories.isEmpty()) {
                                HintText("未设标签与分类 · ${content.length} 字")
                            } else {
                                selectedTags.take(2).forEach {
                                    TagPill(text = it, selected = false, compact = true)
                                }
                                selectedCategories.take(1).forEach {
                                    TagPill(text = it, selected = false, compact = true)
                                }
                            }
                        }
                    }
                    GhostButton(text = "发布设置", icon = GIcons.Tag, onClick = { showSettings = true })
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton(
                        text = if (saving) "发布中…" else "发布",
                        icon = GIcons.Upload,
                        enabled = !saving,
                        onClick = { doPublish() },
                    )
                }
            }
        }

        // ── 发布设置面板 ──────────────────────────────
        BottomPanel(visible = showSettings, onDismiss = { showSettings = false }) {
            PanelHeader(
                title = "发布设置",
                subtitle = "改完点右上角「发布」才会写进文件",
                onClose = { showSettings = false },
            )
            Spacer(Modifier.height(10.dp))

            // 发布时间
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(GIcons.Clock, null, tint = BrandOrange, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("发布时间", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                DateTimePicker(value = createdAt, onChange = { createdAt = it })
            }

            Spacer(Modifier.height(14.dp))

            // 状态
            Text("发布状态", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                options = listOf(
                    PostStatus.PUBLISHED to "正常发布",
                    PostStatus.DRAFT to "草稿",
                    PostStatus.HIDDEN to "隐藏",
                ),
                selected = status,
                onSelect = { status = it },
            )
            Spacer(Modifier.height(8.dp))
            HintText(
                when (status) {
                    PostStatus.PUBLISHED -> "published: true，出现在文章列表里"
                    PostStatus.DRAFT -> "published: false，电脑端会当成草稿"
                    PostStatus.HIDDEN -> "published: true 且 hideInList: true，列表里不显示"
                },
            )

            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "置顶",
                subtitle = "isTop: true，排在列表最前面",
                checked = isTop,
                onCheckedChange = { isTop = it },
            )

            Spacer(Modifier.height(10.dp))
            LabeledField(
                label = "文件名（URL）",
                value = fileName,
                onValueChange = {
                    fileNameTouched = true
                    fileName = it
                },
                placeholder = "留空则按标题自动生成拼音",
                supporting = "默认取标题的拼音，用连字符连接；写成 $effectiveFileName.md",
            )

            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
            Spacer(Modifier.height(6.dp))

            // 标签：收缩菜单 + 多选 + 可新增
            MultiSelectSection(
                title = "标签",
                icon = GIcons.Tag,
                options = allTags.map { it.name },
                selected = selectedTags,
                onToggle = { name ->
                    selectedTags = if (name in selectedTags) selectedTags - name else selectedTags + name
                },
                onCreate = { name -> selectedTags = selectedTags + name },
                emptyHint = "还没有标签，在下面直接新建一个",
            )

            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
            Spacer(Modifier.height(6.dp))

            // 分类：与标签同一套逻辑
            MultiSelectSection(
                title = "分类",
                icon = GIcons.Category,
                options = allCategories.map { it.name },
                selected = selectedCategories,
                onToggle = { name ->
                    selectedCategories =
                        if (name in selectedCategories) selectedCategories - name else selectedCategories + name
                },
                onCreate = { name -> selectedCategories = selectedCategories + name },
                emptyHint = "还没有分类，在下面直接新建一个",
                createPlaceholder = "输入分类名后新增",
            )

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = "收起",
                    modifier = Modifier.weight(1f),
                    onClick = { showSettings = false },
                )
                PrimaryButton(
                    text = if (saving) "发布中…" else "发布",
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                    onClick = {
                        showSettings = false
                        doPublish()
                    },
                )
            }
        }

        if (askDiscard) {
            ConfirmDialog(
                title = "放弃这次修改？",
                text = "返回后这次编辑的内容不会保存。",
                confirmText = "放弃",
                onConfirm = {
                    askDiscard = false
                    onBack()
                },
                onDismiss = { askDiscard = false },
            )
        }
    }
}
