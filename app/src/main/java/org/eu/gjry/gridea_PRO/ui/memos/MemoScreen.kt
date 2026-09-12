package org.eu.gjry.gridea_PRO.ui.memos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.gjry.gridea_PRO.data.Memo
import org.eu.gjry.gridea_PRO.state.AppState
import org.eu.gjry.gridea_PRO.ui.common.ConfirmDialog
import org.eu.gjry.gridea_PRO.ui.common.DateTimePicker
import org.eu.gjry.gridea_PRO.ui.common.EmptyHint
import org.eu.gjry.gridea_PRO.ui.common.HintText
import org.eu.gjry.gridea_PRO.ui.common.PrimaryButton
import org.eu.gjry.gridea_PRO.ui.common.TagPill
import org.eu.gjry.gridea_PRO.ui.common.highlightMemoTags
import org.eu.gjry.gridea_PRO.ui.common.minuteText
import org.eu.gjry.gridea_PRO.ui.icons.GIcons
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrange
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangeLine
import org.eu.gjry.gridea_PRO.ui.theme.Ink
import org.eu.gjry.gridea_PRO.ui.theme.InkFaint
import org.eu.gjry.gridea_PRO.ui.theme.InkSoft
import org.eu.gjry.gridea_PRO.ui.theme.LineSoft
import org.eu.gjry.gridea_PRO.ui.theme.Paper
import org.eu.gjry.gridea_PRO.ui.theme.PaperSoft
import org.eu.gjry.gridea_PRO.ui.theme.PaperSunken
import java.time.LocalDateTime

/**
 * 闪念页。
 *
 * 两件事跟文章页刻意不一样：
 * 1. 标签来自正文里的 #xxx，是闪念自己的体系，不碰 tags.json
 * 2. 标签在正文里用橙色标出来——一眼能看见用过的标签
 */
@Composable
fun MemoScreen(state: AppState, revision: Int) {
    val memos by state.memos.collectAsState()
    val hasFolder = state.tree.value != null

    var draft by remember { mutableStateOf("") }
    var createdAt by remember { mutableStateOf(LocalDateTime.now()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Memo?>(null) }

    val allTags = remember(memos, revision) { state.memoTagOptions() }

    val visible = remember(memos, selectedTag, revision) {
        if (selectedTag == null) memos else memos.filter { it.tags.contains(selectedTag) }
    }

    fun resetEditor() {
        draft = ""
        editingId = null
        createdAt = LocalDateTime.now()
    }

    fun submit() {
        state.saveMemo(draft, createdAt, editingId) { ok ->
            if (ok) resetEditor()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {

            // ── 顶栏 ──────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("闪念", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${memos.size} 条想法",
                        color = InkFaint,
                        fontSize = 11.5.sp,
                    )
                }
                if (editingId != null) {
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = PaperSoft,
                        modifier = Modifier.clickable { resetEditor() },
                    ) {
                        Text(
                            text = "取消编辑",
                            color = InkSoft,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // ── 书写小框 ──────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = PaperSoft,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (editingId != null) BrandOrange else BrandOrangeLine,
                ),
            ) {
                Column(modifier = Modifier.padding(13.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
                        if (draft.isEmpty()) {
                            Text(
                                text = "记点什么…用 #标签 归类，比如 #日常",
                                color = InkFaint,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = TextStyle(fontSize = 14.5.sp, lineHeight = 23.sp, color = Ink),
                            cursorBrush = SolidColor(BrandOrange),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 时间可改：默认就是「现在」，跟电脑端的年月日时分对齐
                        Box(modifier = Modifier.weight(1f)) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(GIcons.Clock, null, tint = InkFaint, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = createdAt.minuteText(),
                                        color = InkSoft,
                                        fontSize = 11.5.sp,
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                SmallDateRow(value = createdAt, onChange = { createdAt = it })
                            }
                        }
                        PrimaryButton(
                            text = if (editingId == null) "发布" else "更新",
                            icon = if (editingId == null) GIcons.Upload else GIcons.Check,
                            enabled = draft.isNotBlank(),
                            onClick = { submit() },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 闪念标签栏（独立于文章标签） ────────────
            if (allTags.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    item {
                        TagPill(
                            text = "全部",
                            selected = selectedTag == null,
                            onClick = { selectedTag = null },
                        )
                    }
                    items(allTags, key = { it }) { name ->
                        TagPill(
                            text = name,
                            selected = selectedTag == name,
                            compact = true,
                            onClick = { selectedTag = if (selectedTag == name) null else name },
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
            }

            // ── 闪念列表 ──────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                when {
                    !hasFolder -> EmptyHint(
                        icon = GIcons.Folder,
                        title = "还没有选择博客文件夹",
                        subtitle = "去「文章」页点右上角选一个",
                    )

                    visible.isEmpty() -> EmptyHint(
                        icon = GIcons.Memo,
                        title = if (memos.isEmpty()) "还没有闪念" else "这个标签下没有闪念",
                        subtitle = if (memos.isEmpty()) "在上面那个框里写第一句" else "换个标签看看",
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 28.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(visible, key = { it.id.ifBlank { it.content.hashCode().toString() } }) { memo ->
                            MemoCard(
                                memo = memo,
                                onEdit = {
                                    draft = memo.content
                                    createdAt = memo.createdAt
                                    editingId = memo.id
                                },
                                onDelete = { pendingDelete = memo },
                            )
                        }
                    }
                }
            }
        }

        pendingDelete?.let { memo ->
            ConfirmDialog(
                title = "删除这条闪念？",
                text = "删掉就没了，电脑端下次同步也会同步这个删除。",
                onConfirm = {
                    state.deleteMemo(memo.id)
                    if (editingId == memo.id) resetEditor()
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        }
    }
}

@Composable
private fun SmallDateRow(value: LocalDateTime, onChange: (LocalDateTime) -> Unit) {
    // 面板里时间已经单独显示过了，这里只要两个按钮
    DateTimePicker(value = value, onChange = onChange, showValue = false)
}

@Composable
private fun MemoCard(memo: Memo, onEdit: () -> Unit, onDelete: () -> Unit) {
    val highlighted = remember(memo.content) {
        highlightMemoTags(memo.content, BrandOrange, Ink)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Paper,
        border = androidx.compose.foundation.BorderStroke(1.dp, LineSoft),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = highlighted,
                fontSize = 14.5.sp,
                lineHeight = 23.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = memo.createdAt.minuteText(),
                    color = InkFaint,
                    fontSize = 11.sp,
                )
                if (memo.tags.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        memo.tags.take(3).forEach { tag ->
                            TagPill(text = tag, selected = false, compact = true)
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(PaperSunken, CircleShape)
                        .clickable { onEdit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Edit, "编辑", tint = InkSoft, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(PaperSunken, CircleShape)
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Delete, "删除", tint = InkSoft, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
