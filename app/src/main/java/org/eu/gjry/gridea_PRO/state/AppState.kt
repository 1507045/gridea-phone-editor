package org.eu.gjry.gridea_PRO.state

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eu.gjry.gridea_PRO.data.Category
import org.eu.gjry.gridea_PRO.data.Memo
import org.eu.gjry.gridea_PRO.data.Post
import org.eu.gjry.gridea_PRO.data.Prefs
import org.eu.gjry.gridea_PRO.data.Tag
import org.eu.gjry.gridea_PRO.data.Vault
import org.eu.gjry.gridea_PRO.sync.SyncEngine
import java.time.LocalDateTime

/**
 * 全app唯一的状态源。三个页面都从这里取数据，谁改了都走这里落盘，
 * 落完盘统一 refresh，界面不会出现两个页面各显示一份不同数据的情况。
 */
class AppState(
    private val vault: Vault,
    private val prefs: Prefs,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
) {

    private val _tree = MutableStateFlow(prefs.treeUri)
    val tree: StateFlow<Uri?> = _tree.asStateFlow()

    private val _folderLabel = MutableStateFlow(prefs.folderLabel)
    val folderLabel: StateFlow<String> = _folderLabel.asStateFlow()

    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts.asStateFlow()

    private val _memos = MutableStateFlow<List<Memo>>(emptyList())
    val memos: StateFlow<List<Memo>> = _memos.asStateFlow()

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 每次刷新自增。界面用它当 key，确保列表真的重建一次。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _autoUpload = MutableStateFlow(prefs.autoUpload)
    val autoUpload: StateFlow<Boolean> = _autoUpload.asStateFlow()

    private val _contentOnly = MutableStateFlow(prefs.contentOnly)
    val contentOnly: StateFlow<Boolean> = _contentOnly.asStateFlow()

    /** 上一次见到的「同步已完成」状态，用来只在跳变时刷新，避免进度每变一次就重读一遍盘。 */
    private var syncWasFinished = false

    init {
        // 同步跑完（成功或失败）就重读一遍数据。
        // 以前这一步由同步页在协程里回调，但同步现在是 app 级任务，
        // 触发它的页面很可能已经不在组合里了，回调不会再执行。
        // 挂在 progress 上就与界面生命周期无关了。
        scope.launch {
            sync.progress.collect { p ->
                if (p.running) {
                    syncWasFinished = false
                } else if (p.finished && !syncWasFinished) {
                    syncWasFinished = true
                    refresh()
                }
            }
        }
    }

    // ───────────────────────── 文件夹 ─────────────────────────

    fun setFolder(uri: Uri) {
        prefs.treeUri = uri
        _tree.value = uri
        val label = runCatching { vault.folderLabel(uri) }.getOrDefault("")
        prefs.folderLabel = label
        _folderLabel.value = label
        // 换了文件夹，同步快照就作废了，否则会拿旧基准去比新目录
        prefs.saveSnapshot(emptyMap())
        prefs.savePending(emptySet())
        refresh()
    }

    // ───────────────────────── 读取 ─────────────────────────

    fun refresh() {
        val uri = _tree.value ?: run {
            _posts.value = emptyList()
            _memos.value = emptyList()
            _tags.value = emptyList()
            _categories.value = emptyList()
            return
        }
        scope.launch {
            _loading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    Triple(
                        vault.listPosts(uri),
                        vault.loadMemos(uri),
                        Pair(vault.loadTags(uri), vault.loadCategories(uri)),
                    )
                }
                _posts.value = result.first
                _memos.value = result.second
                _tags.value = result.third.first
                _categories.value = result.third.second
                _revision.value = _revision.value + 1
            } catch (e: Exception) {
                _message.value = "读取博客文件夹失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _loading.value = false
            }
        }
    }

    /** 供界面直接用的标签集合：tags.json 里的全部标签，按被引用次数排序。 */
    fun tagOptions(): List<Tag> {
        val counts = HashMap<String, Int>()
        for (p in _posts.value) {
            for (t in p.tags) counts[t] = (counts[t] ?: 0) + 1
        }
        val merged = LinkedHashMap<String, Tag>()
        for (t in _tags.value) merged[t.name] = t
        for ((name, _) in counts) {
            if (name !in merged) {
                merged[name] = Tag(id = "", name = name, slug = org.eu.gjry.gridea_PRO.data.Slug.forName(name))
            }
        }
        return merged.values.sortedWith(
            compareByDescending<Tag> { counts[it.name] ?: 0 }.thenBy { it.name },
        )
    }

    fun categoryOptions(): List<Category> {
        val counts = HashMap<String, Int>()
        for (p in _posts.value) {
            for (c in p.categories) counts[c] = (counts[c] ?: 0) + 1
        }
        val merged = LinkedHashMap<String, Category>()
        for (c in _categories.value) merged[c.name] = c
        for ((name, _) in counts) {
            if (name !in merged) {
                merged[name] = Category(id = "", name = name, slug = org.eu.gjry.gridea_PRO.data.Slug.forName(name))
            }
        }
        return merged.values.sortedWith(
            compareByDescending<Category> { counts[it.name] ?: 0 }.thenBy { it.name },
        )
    }

    /** 闪念专属标签，来自正文里的 #xxx，跟文章标签体系互不干扰。 */
    fun memoTagOptions(): List<String> {
        val counts = HashMap<String, Int>()
        for (m in _memos.value) {
            for (t in m.tags) counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    // ───────────────────────── 写入 ─────────────────────────

    /**
     * 保存文章。返回 true 表示成功。
     * 成功后会 refresh 一次，列表立刻能看到刚写的东西。
     */
    fun savePost(draft: Post, originalFileName: String?, onDone: (Boolean) -> Unit = {}) {
        val uri = _tree.value
        if (uri == null) {
            _message.value = "请先选择博客文件夹"
            onDone(false)
            return
        }
        scope.launch {
            _loading.value = true
            try {
                val saved = withContext(Dispatchers.IO) { vault.savePost(uri, draft, originalFileName) }
                refreshNow(uri)
                enqueueUpload(listOf("posts/${saved.fileName}.md"))
                _message.value = "已保存《${saved.title}》"
                onDone(true)
            } catch (e: Exception) {
                _message.value = "保存失败：${e.message ?: e.javaClass.simpleName}"
                onDone(false)
            } finally {
                _loading.value = false
            }
        }
    }

    fun deletePost(fileName: String, onDone: (Boolean) -> Unit = {}) {
        val uri = _tree.value ?: return onDone(false)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { vault.deletePost(uri, fileName) }
                refreshNow(uri)
                _message.value = "已删除"
                onDone(true)
            } catch (e: Exception) {
                _message.value = "删除失败：${e.message ?: e.javaClass.simpleName}"
                onDone(false)
            }
        }
    }

    fun saveMemo(content: String, createdAt: LocalDateTime, memoId: String?, onDone: (Boolean) -> Unit = {}) {
        val uri = _tree.value
        if (uri == null) {
            _message.value = "请先选择博客文件夹"
            onDone(false)
            return
        }
        if (content.isBlank()) {
            _message.value = "闪念内容不能为空"
            onDone(false)
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (memoId == null) vault.createMemo(uri, content, createdAt)
                    else vault.updateMemo(uri, memoId, content, createdAt)
                }
                refreshNow(uri)
                enqueueUpload(listOf("config/memos.json"))
                _message.value = if (memoId == null) "已发布闪念" else "已更新闪念"
                onDone(true)
            } catch (e: Exception) {
                _message.value = "发布失败：${e.message ?: e.javaClass.simpleName}"
                onDone(false)
            }
        }
    }

    fun deleteMemo(id: String) {
        val uri = _tree.value ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { vault.deleteMemo(uri, id) }
                refreshNow(uri)
                enqueueUpload(listOf("config/memos.json"))
                _message.value = "已删除闪念"
            } catch (e: Exception) {
                _message.value = "删除失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ───────────────────────── 同步相关 ─────────────────────────

    fun setAutoUpload(enabled: Boolean) {
        prefs.autoUpload = enabled
        _autoUpload.value = enabled
        if (enabled) sync.scheduleFlush()
    }

    fun setContentOnly(only: Boolean) {
        prefs.contentOnly = only
        _contentOnly.value = only
    }

    fun saveRemote(config: org.eu.gjry.gridea_PRO.data.RemoteConfig) {
        prefs.remote = config
    }

    fun remoteConfig(): org.eu.gjry.gridea_PRO.data.RemoteConfig = prefs.remote

    /** 上次同步完成的时间戳，0 表示从未同步过。 */
    fun lastSyncMillis(): Long = prefs.lastSyncAt

    /** 改完文件后把对应文件排进上传队列，实现「自动上传」。 */
    private fun enqueueUpload(paths: List<String>) {
        sync.markDirty(paths)
    }

    /** 同步刷新：立即重新读一遍盘（不走协程切来切去，供保存成功后调用）。 */
    private suspend fun refreshNow(uri: Uri) {
        val result = withContext(Dispatchers.IO) {
            Triple(
                vault.listPosts(uri),
                vault.loadMemos(uri),
                Pair(vault.loadTags(uri), vault.loadCategories(uri)),
            )
        }
        _posts.value = result.first
        _memos.value = result.second
        _tags.value = result.third.first
        _categories.value = result.third.second
        _revision.value = _revision.value + 1
    }
}
