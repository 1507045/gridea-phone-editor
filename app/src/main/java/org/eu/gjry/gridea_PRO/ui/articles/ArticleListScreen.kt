package org.eu.gjry.gridea_PRO.ui.articles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.gjry.gridea_PRO.data.Post
import org.eu.gjry.gridea_PRO.data.PostStatus
import org.eu.gjry.gridea_PRO.state.AppState
import org.eu.gjry.gridea_PRO.ui.common.EmptyHint
import org.eu.gjry.gridea_PRO.ui.common.TagPill
import org.eu.gjry.gridea_PRO.ui.common.minuteText
import org.eu.gjry.gridea_PRO.ui.common.plainSnippet
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

/**
 * 首页：搜索 → 标签栏 → 文章列表 → 右下角新增。
 * 右上角那颗按钮用来换博客文件夹（需求指定的位置）。
 */
@Composable
fun ArticleListScreen(
    state: AppState,
    revision: Int,
    onNewArticle: () -> Unit,
    onEditArticle: (Post) -> Unit,
    onPickFolder: () -> Unit,
) {
    val posts by state.posts.collectAsState()
    val loading by state.loading.collectAsState()
    val folderLabel by state.folderLabel.collectAsState()
    val tags by state.tags.collectAsState()
    val hasFolder = folderLabel.isNotEmpty() || state.tree.value != null

    var query by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }

    // 标签栏：既包含 tags.json 里声明的，也包含文章里实际用到的
    val tagNames = remember(posts, tags, revision) {
        val seen = LinkedHashMap<String, Int>()
        for (t in tags) if (t.name.isNotBlank()) seen[t.name] = 0
        for (p in posts) for (t in p.tags) seen[t] = (seen[t] ?: 0) + 1
        seen.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    val filtered = remember(posts, query, selectedTag, revision) {
        posts.filter { p ->
            val okTag = selectedTag == null || p.tags.contains(selectedTag)
            if (!okTag) return@filter false
            if (query.isBlank()) return@filter true
            val q = query.trim()
            p.title.contains(q, ignoreCase = true) || p.content.contains(q, ignoreCase = true)
        }
    }

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── 顶栏 ──────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("文章", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (hasFolder) "${posts.size} 篇 · ${folderLabel.ifBlank { "博客文件夹" }}" else "还没有选择博客文件夹",
                        color = InkFaint,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(PaperSoft, CircleShape)
                        .clickable { state.refresh() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Refresh, "刷新", tint = InkSoft, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                // 右上角：更改博客文件夹
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BrandOrangeSoft,
                    modifier = Modifier.clickable { onPickFolder() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(GIcons.Folder, "更改文件夹", tint = BrandOrange, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "文件夹",
                            color = BrandOrange,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // ── 搜索 ──────────────────────────────────
            SearchBar(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )

            // ── 标签栏 ────────────────────────────────
            if (tagNames.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    item {
                        TagPill(
                            text = "全部",
                            selected = selectedTag == null,
                            onClick = { selectedTag = null },
                        )
                    }
                    items(tagNames, key = { it }) { name ->
                        TagPill(
                            text = name,
                            selected = selectedTag == name,
                            onClick = { selectedTag = if (selectedTag == name) null else name },
                        )
                    }
                }
            }

            // ── 列表 ──────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                when {
                    !hasFolder -> EmptyHint(
                        icon = GIcons.Folder,
                        title = "先选一个博客文件夹",
                        subtitle = "点右上角「文件夹」，选中电脑上那个 Gridea 站点目录",
                    )

                    loading && posts.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.5.dp)
                    }

                    filtered.isEmpty() -> EmptyHint(
                        icon = GIcons.Article,
                        title = if (posts.isEmpty()) "还没有文章" else "没有匹配的文章",
                        subtitle = if (posts.isEmpty()) "点右下角的按钮写第一篇" else "换个关键词或标签试试",
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(filtered, key = { it.fileName }) { post ->
                            PostCard(post = post, onClick = { onEditArticle(post) })
                        }
                    }
                }
            }
        }

        // ── 右下角新增 ────────────────────────────────
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 22.dp)
                .size(56.dp)
                .clickable { onNewArticle() },
            shape = CircleShape,
            color = BrandOrange,
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(GIcons.Pencil, "写文章", tint = Color.White, modifier = Modifier.size(23.dp))
            }
        }
    }
}

@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = PaperSunken,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = InkFaint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(9.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text("搜索已写过的文章…", color = InkFaint, fontSize = 13.5.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.5.sp, color = Ink),
                    cursorBrush = SolidColor(BrandOrange),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Clear, "清空", tint = InkFaint, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

@Composable
private fun PostCard(post: Post, onClick: () -> Unit) {
    val snippet = remember(post.content, post.fileName) { plainSnippet(post.content) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = Paper,
        border = androidx.compose.foundation.BorderStroke(1.dp, LineSoft),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.title,
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 22.sp,
                )
                if (post.isTop) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(BrandOrangeSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(GIcons.Pin, "置顶", tint = BrandOrange, modifier = Modifier.size(13.dp))
                    }
                }
            }

            if (snippet.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = snippet,
                    color = InkSoft,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.createdAt.minuteText(),
                    color = InkFaint,
                    fontSize = 11.sp,
                )

                if (post.status != PostStatus.PUBLISHED) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(post.status)
                }

                if (post.tags.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        post.tags.take(3).forEach { tag ->
                            TagPill(text = tag, selected = false, compact = true)
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: PostStatus) {
    val (bg, fg) = when (status) {
        PostStatus.DRAFT -> PaperSunken to InkSoft
        PostStatus.HIDDEN -> Color(0xFFFEF3C7) to Color(0xFF92400E)
        PostStatus.PUBLISHED -> BrandOrangeSoft to BrandOrange
    }
    Surface(shape = RoundedCornerShape(percent = 50), color = bg) {
        Text(
            text = status.label,
            color = fg,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
