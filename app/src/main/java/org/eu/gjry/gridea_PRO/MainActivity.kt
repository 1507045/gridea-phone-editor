package org.eu.gjry.gridea_PRO

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.gjry.gridea_PRO.data.Post
import org.eu.gjry.gridea_PRO.state.AppContainer
import org.eu.gjry.gridea_PRO.ui.articles.ArticleEditorScreen
import org.eu.gjry.gridea_PRO.ui.articles.ArticleListScreen
import org.eu.gjry.gridea_PRO.ui.icons.GIcons
import org.eu.gjry.gridea_PRO.ui.memos.MemoScreen
import org.eu.gjry.gridea_PRO.ui.sync.SyncScreen
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrange
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangeSoft
import org.eu.gjry.gridea_PRO.ui.theme.GrideaPROTheme
import org.eu.gjry.gridea_PRO.ui.theme.InkFaint
import org.eu.gjry.gridea_PRO.ui.theme.Paper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GrideaApp).container
        setContent {
            GrideaPROTheme {
                AppRoot(container)
            }
        }
    }
}

/**
 * 整个 app 就这一棵界面树：底下三个 Tab，编辑文章时把 Tab 栏收起来整页给编辑器。
 *
 * 用同一个 Scaffold 而不是给编辑器另开一套，是因为要共用那个 SnackbarHost——
 * 保存成功、同步失败这类提示，在哪个页面都得能弹出来。
 */
@Composable
private fun AppRoot(container: AppContainer) {
    val state = container.appState
    val sync = container.sync
    val context = LocalContext.current

    val revision by state.revision.collectAsState()
    val message by state.message.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    var inEditor by remember { mutableStateOf(false) }
    // null = 新文章；非 null = 改这篇
    var editing by remember { mutableStateOf<Post?>(null) }

    val snackbar = remember { SnackbarHostState() }

    // SAF 选文件夹。拿到的是 content:// 树，得先要持久权限，
    // 否则 app 一重启这个 uri 就读不动了。
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        state.setFolder(uri)
    }

    // 冷启动如果上次已经选过文件夹，直接把内容读出来
    LaunchedEffect(Unit) { state.refresh() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        state.consumeMessage()
    }

    // 编辑页开着的时候，返回键先退编辑器而不是退出 app
    BackHandler(enabled = inEditor) {
        inEditor = false
        editing = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // 编辑器要占满整屏，Tab 栏这时候收掉
            if (!inEditor) {
                BottomBar(selected = tab, onSelect = { tab = it })
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Paper)
                .padding(inner),
        ) {
            if (inEditor) {
                ArticleEditorScreen(
                    state = state,
                    editing = editing,
                    onBack = {
                        inEditor = false
                        editing = null
                    },
                    onSaved = {
                        // 需求：发布完自动退回首页，并且刷新一次
                        inEditor = false
                        editing = null
                        tab = 0
                        state.refresh()
                    },
                )
            } else {
                when (tab) {
                    0 -> ArticleListScreen(
                        state = state,
                        revision = revision,
                        onNewArticle = {
                            editing = null
                            inEditor = true
                        },
                        onEditArticle = { post ->
                            editing = post
                            inEditor = true
                        },
                        onPickFolder = { pickFolder.launch(state.tree.value) },
                    )

                    1 -> MemoScreen(state = state, revision = revision)

                    else -> SyncScreen(
                        state = state,
                        sync = sync,
                        onPickFolder = { pickFolder.launch(state.tree.value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
        BottomTab(0, selected, onSelect, GIcons.Article, "文章")
        BottomTab(1, selected, onSelect, GIcons.Memo, "闪念")
        BottomTab(2, selected, onSelect, GIcons.Sync, "同步")
    }
}

@Composable
private fun RowScope.BottomTab(
    index: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    icon: ImageVector,
    label: String,
) {
    val active = index == selected
    NavigationBarItem(
        selected = active,
        onClick = { onSelect(index) },
        icon = {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        },
        label = {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = BrandOrange,
            selectedTextColor = BrandOrange,
            indicatorColor = BrandOrangeSoft,
            unselectedIconColor = InkFaint,
            unselectedTextColor = InkFaint,
        ),
    )
}
