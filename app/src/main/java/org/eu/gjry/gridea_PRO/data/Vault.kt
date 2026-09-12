package org.eu.gjry.gridea_PRO.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 博客站点仓库：直接读写用户选中的那个文件夹。
 *
 * 目录结构与电脑端 Gridea Pro 完全一致：
 * ```
 * <博客文件夹>/
 *   posts/<文件名>.md          ← YAML front matter + 正文
 *   config/tags.json           ← {"tags":[{id,name,slug,used,color}]}
 *   config/categories.json     ← {"categories":[{id,name,slug,description}]}
 *   config/memos.json          ← {"memos":[{id,content,tags,images,createdAt,updatedAt}]}
 * ```
 * 手机只碰这几处，其余（主题、菜单、友链…）原样不动。
 */
class Vault(
    context: Context,
    private val prefs: Prefs,
) {

    private val appContext = context.applicationContext
    val fs = DocFs(appContext)

    val tree: Uri?
        get() = prefs.treeUri

    fun requireTree(): Uri = tree ?: throw DocFs.NoAccessException("还没有选择博客文件夹。")

    // ───────────────────────── 文章 ─────────────────────────

    fun listPosts(tree: Uri): List<Post> {
        val entries = fs.listPath(tree, POSTS_DIR)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
        val posts = ArrayList<Post>(entries.size)
        for (entry in entries) {
            val fileName = entry.name.removeSuffix(".md").removeSuffix(".MD")
            try {
                val text = fs.readText(entry)
                posts += FrontMatter.parse(text, fileName)
            } catch (_: Exception) {
                // 单个文件读不出来不该拖垮整个列表
            }
        }
        return posts.sortedWith(
            compareByDescending<Post> { it.isTop }.thenByDescending { it.createdAt },
        )
    }

    /**
     * 保存文章。fileName 为空时按标题生成拼音文件名。
     * 返回落盘后的文章（fileName / id 已补齐）。
     */
    fun savePost(tree: Uri, draft: Post, originalFileName: String?): Post {
        fs.ensureDir(tree, POSTS_DIR)
        val now = LocalDateTime.now()

        var fileName = draft.fileName.trim().removeSuffix(".md").trim('/')
        if (fileName.isEmpty()) {
            fileName = Slug.fromTitle(draft.title)
        }
        fileName = sanitizeFileName(fileName)
        if (fileName.isEmpty()) fileName = "post-" + FrontMatter.newId(6).lowercase()

        // 文件名唯一：撞了就加序号，不让两篇文章互相覆盖
        fileName = uniqueFileName(tree, fileName, originalFileName)

        val tags = ensureTags(tree, draft.tags)
        val categories = ensureCategories(tree, draft.categories)

        val isNew = originalFileName.isNullOrBlank()
        val result = draft.copy(
            id = draft.id.ifBlank { FrontMatter.newId() },
            fileName = fileName,
            createdAt = draft.createdAt,
            updatedAt = now,
            tags = tags.map { it.name },
            tagIds = tags.map { it.id },
            categories = categories.map { it.name },
            categoryIds = categories.map { it.slug },
        )

        val entry = fs.putText(tree, POSTS_DIR, "$fileName.md", FrontMatter.serialize(result))
            ?: throw java.io.IOException("写入文章失败：$fileName.md")

        // 改过文件名就把旧文件删掉，别在 posts 里留孤儿
        if (!isNew && originalFileName != fileName) {
            fs.resolve(tree, "$POSTS_DIR/$originalFileName.md")?.let { fs.delete(it) }
        }
        return result
    }

    fun deletePost(tree: Uri, fileName: String) {
        fs.resolve(tree, "$POSTS_DIR/$fileName.md")?.let { fs.delete(it) }
    }

    private fun uniqueFileName(tree: Uri, base: String, originalFileName: String?): String {
        val existing = fs.listPath(tree, POSTS_DIR)
            .filter { !it.isDirectory && it.name.endsWith(".md", true) }
            .map { it.name.removeSuffix(".md") }
            .filter { it != originalFileName }
            .toHashSet()
        if (base !in existing) return base
        var i = 2
        while ("$base-$i" in existing) i++
        return "$base-$i"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-")
            .trim('-', '.')
            .take(80)

    // ───────────────────────── 标签 ─────────────────────────

    fun loadTags(tree: Uri): List<Tag> {
        val text = fs.resolve(tree, "$CONFIG_DIR/tags.json")?.let {
            runCatching { fs.readText(it) }.getOrNull()
        } ?: return emptyList()
        return try {
            val arr = JSONObject(text).optJSONArray("tags") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Tag(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    slug = o.optString("slug"),
                    used = o.optBoolean("used", true),
                    color = o.optString("color").takeIf { it.isNotBlank() },
                )
            }.filter { it.name.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveTags(tree: Uri, tags: List<Tag>) {
        fs.ensureDir(tree, CONFIG_DIR)
        val arr = JSONArray()
        for (t in tags) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("name", t.name)
            o.put("slug", t.slug)
            o.put("used", t.used)
            if (!t.color.isNullOrBlank()) o.put("color", t.color)
            arr.put(o)
        }
        val root = JSONObject().put("tags", arr)
        fs.putText(tree, CONFIG_DIR, "tags.json", root.toString(2))
    }

    /** 保证这些标签名都存在于 tags.json，返回带 id 的完整标签。 */
    fun ensureTags(tree: Uri, names: List<String>): List<Tag> {
        val wanted = names.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyList()

        val existing = loadTags(tree).toMutableList()
        var changed = false
        val result = ArrayList<Tag>(wanted.size)

        for (name in wanted) {
            val hit = existing.firstOrNull { it.name == name }
            if (hit != null) {
                if (hit.id.isBlank() || hit.slug.isBlank()) {
                    val fixed = hit.copy(
                        id = hit.id.ifBlank { FrontMatter.newId() },
                        slug = hit.slug.ifBlank { Slug.forName(hit.name).ifBlank { FrontMatter.newId(8).lowercase() } },
                    )
                    val idx = existing.indexOf(hit)
                    existing[idx] = fixed
                    changed = true
                    result += fixed
                } else {
                    result += hit
                }
                continue
            }
            val created = Tag(
                id = FrontMatter.newId(),
                name = name,
                slug = Slug.forName(name).ifBlank { FrontMatter.newId(8).lowercase() },
                used = true,
                color = colorFor(name),
            )
            existing += created
            changed = true
            result += created
        }

        if (changed) {
            saveTags(tree, existing)
            // 标签集变了，分类侧不用动；但同步队列要把它带上
        }
        return result
    }

    /** 标签颜色按名字散列固定挑一个，同一标签每次拿到同一个颜色。 */
    fun colorFor(name: String): String {
        val palette = listOf(
            "#f97316", "#eab308", "#22c55e", "#3b82f6",
            "#a855f7", "#06b6d4", "#ef4444", "#ec4899",
        )
        val h = name.fold(7) { acc, c -> acc * 31 + c.code }
        return palette[Math.floorMod(h, palette.size)]
    }

    // ───────────────────────── 分类 ─────────────────────────

    fun loadCategories(tree: Uri): List<Category> {
        val text = fs.resolve(tree, "$CONFIG_DIR/categories.json")?.let {
            runCatching { fs.readText(it) }.getOrNull()
        } ?: return emptyList()
        return try {
            val arr = JSONObject(text).optJSONArray("categories") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Category(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    slug = o.optString("slug"),
                    description = o.optString("description"),
                )
            }.filter { it.name.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCategories(tree: Uri, categories: List<Category>) {
        fs.ensureDir(tree, CONFIG_DIR)
        val arr = JSONArray()
        for (c in categories) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("slug", c.slug)
            o.put("description", c.description)
            arr.put(o)
        }
        val root = JSONObject().put("categories", arr)
        fs.putText(tree, CONFIG_DIR, "categories.json", root.toString(2))
    }

    /** 分类与标签同一套逻辑：名称 → slug 主键。 */
    fun ensureCategories(tree: Uri, names: List<String>): List<Category> {
        val wanted = names.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyList()

        val existing = loadCategories(tree).toMutableList()
        var changed = false
        val result = ArrayList<Category>(wanted.size)

        for (name in wanted) {
            val hit = existing.firstOrNull { it.name == name }
            if (hit != null) {
                result += hit
                continue
            }
            val created = Category(
                id = FrontMatter.newId(),
                name = name,
                slug = Slug.forName(name).ifBlank { FrontMatter.newId(8).lowercase() },
                description = name,
            )
            existing += created
            changed = true
            result += created
        }
        if (changed) saveCategories(tree, existing)
        return result
    }

    // ───────────────────────── 闪念 ─────────────────────────

    fun loadMemos(tree: Uri): List<Memo> {
        val text = fs.resolve(tree, "$CONFIG_DIR/memos.json")?.let {
            runCatching { fs.readText(it) }.getOrNull()
        } ?: return emptyList()
        return try {
            val arr = JSONObject(text).optJSONArray("memos") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val content = o.optString("content")
                if (content.isBlank()) return@mapNotNull null
                Memo(
                    id = o.optString("id"),
                    content = content,
                    tags = optStringList(o.optJSONArray("tags")),
                    images = optStringList(o.optJSONArray("images")),
                    createdAt = parseFlexibleTime(o.opt("createdAt")),
                    updatedAt = parseFlexibleTime(o.opt("updatedAt")),
                )
            }.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMemos(tree: Uri, memos: List<Memo>) {
        fs.ensureDir(tree, CONFIG_DIR)
        val arr = JSONArray()
        for (m in memos) {
            val o = JSONObject()
            o.put("id", m.id)
            o.put("content", m.content)
            o.put("tags", JSONArray().apply { m.tags.forEach { put(it) } })
            o.put("images", JSONArray().apply { m.images.forEach { put(it) } })
            o.put("createdAt", rfc3339(m.createdAt))
            o.put("updatedAt", rfc3339(m.updatedAt))
            arr.put(o)
        }
        val root = JSONObject().put("memos", arr)
        fs.putText(tree, CONFIG_DIR, "memos.json", root.toString(2))
    }

    /** 新建闪念。createdAt 由调用方指定（默认就是「现在」）。 */
    fun createMemo(tree: Uri, content: String, createdAt: LocalDateTime): Memo {
        val memo = Memo(
            id = FrontMatter.newId(),
            content = content.trim(),
            tags = extractMemoTags(content),
            images = emptyList(),
            createdAt = createdAt,
            updatedAt = LocalDateTime.now(),
        )
        val all = ArrayList(loadMemos(tree))
        all.add(0, memo)
        saveMemos(tree, all)
        return memo
    }

    fun updateMemo(tree: Uri, id: String, content: String, createdAt: LocalDateTime): List<Memo> {
        val all = loadMemos(tree).map { m ->
            if (m.id == id) {
                m.copy(
                    content = content.trim(),
                    tags = extractMemoTags(content),
                    createdAt = createdAt,
                    updatedAt = LocalDateTime.now(),
                )
            } else {
                m
            }
        }
        saveMemos(tree, all)
        return all.sortedByDescending { it.createdAt }
    }

    fun deleteMemo(tree: Uri, id: String): List<Memo> {
        val all = loadMemos(tree).filterNot { it.id == id }
        saveMemos(tree, all)
        return all
    }

    /** 闪念的标签来自正文里的 #xxx，与文章标签体系完全隔离。 */
    fun extractMemoTags(content: String): List<String> =
        MEMO_TAG.findAll(content)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    // ───────────────────────── 同步用 ─────────────────────────

    /** 站点文件清单（只列文件）。 */
    fun listFiles(tree: Uri, contentOnly: Boolean): List<FileEntry> {
        val entries = if (contentOnly) {
            CONTENT_DIRS.flatMap { fs.walk(tree, it) }
        } else {
            fs.walk(tree, "")
        }
        return entries
            .filterNot { isExcluded(it.path) }
            .map { FileEntry(it.path, it.isDirectory, it.size, it.lastModified) }
    }

    fun readFileBytes(tree: Uri, path: String): ByteArray {
        val entry = fs.resolve(tree, path) ?: throw java.io.IOException("文件不存在：$path")
        return fs.readBytes(entry)
    }

    /**
     * 打开某个文件的输入流，供「边读边打包」用。
     * 文件不在或打不开都返回 null，由调用方跳过——打包时个别文件读不到不该让整包失败。
     */
    fun openInput(tree: Uri, path: String): java.io.InputStream? {
        val entry = fs.resolve(tree, path) ?: return null
        return fs.openInput(entry)
    }

    fun writeFileBytes(tree: Uri, path: String, bytes: ByteArray) {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        fs.putFile(tree, dir, name, bytes) ?: throw java.io.IOException("写入失败：$path")
    }

    /** 桌面端的帖缓存和版本库不该被同步来回搬。 */
    fun isExcluded(path: String): Boolean {
        if (path.isBlank()) return true
        if (path.endsWith("/.DS_Store") || path == ".DS_Store") return true
        if (path.startsWith(".git/") || path.contains("/.git/")) return true
        if (path == "config/posts.json") return true
        if (path.startsWith("output/")) return true
        // 站点根目录下的压缩包是同步中转产物——电脑端打的 site.zip、或者手工备份的
        // 大包。它们不是站点内容，不排除的话一个几百 MB 的 zip 会被反复搬。
        // 只针对根目录：子目录里的 zip 可能是主题自带的资源，不能动。
        if (!path.contains('/') && path.endsWith(".zip", ignoreCase = true)) return true
        return false
    }

    /** 路径是否落在「只同步文章与配置」的范围内。 */
    fun isContentPath(path: String): Boolean =
        CONTENT_DIRS.any { path == it || path.startsWith("$it/") }

    /**
     * 一个路径该不该参与同步。
     *
     * 本地与远端必须用同一套判断，否则 contentOnly 会失效：
     * 本地只列 posts/config，远端列出全站，plan() 就会把 themes 之类的
     * 「远端独有」文件判成需要下载，把「只同步文章与配置」变成一句空话。
     */
    fun isSyncable(path: String, contentOnly: Boolean): Boolean {
        if (isExcluded(path)) return false
        if (contentOnly && !isContentPath(path)) return false
        return true
    }

    fun hasPostsDir(tree: Uri): Boolean = fs.resolve(tree, POSTS_DIR)?.isDirectory == true

    fun folderLabel(tree: Uri): String = runCatching {
        val id = fs.rootDocId(tree)
        // primary:Download/blog -> Download/blog
        id.substringAfter(':', id).replace(':', '/')
    }.getOrDefault("")

    // ───────────────────────── 内部工具 ─────────────────────────

    private fun optStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun parseFlexibleTime(v: Any?): LocalDateTime = when (v) {
        is Number -> java.time.Instant.ofEpochMilli(v.toLong())
            .atZone(ZoneId.systemDefault()).toLocalDateTime()

        is String -> FrontMatter.parseTime(v) ?: LocalDateTime.now()
        else -> LocalDateTime.now()
    }

    /** 与电脑端 go 的 time.RFC3339 输出对齐（不带毫秒）。 */
    private fun rfc3339(t: LocalDateTime): String = t
        .truncatedTo(ChronoUnit.SECONDS)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        const val POSTS_DIR = "posts"
        const val CONFIG_DIR = "config"
        val CONTENT_DIRS = listOf("posts", "config", "post-images", "images")

        private val MEMO_TAG = Regex("#([\\p{L}\\p{N}_]+)")
    }
}
