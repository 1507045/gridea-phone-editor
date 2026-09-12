package org.eu.gjry.gridea_PRO.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap

/** SAF 里的一个文档条目。 */
data class DocEntry(
    val docId: String,
    val name: String,
    /** 相对博客根目录的路径，例如 posts/hello.md */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val uri: Uri,
)

/**
 * 直接用 SAF 的 DocumentsContract 操作文件，不走 DocumentFile。
 *
 * 原因：DocumentFile 每调一次 isDirectory / length 都是一次 ContentProvider 查询，
 * 几百篇文章扫一遍要好几秒。这里一次 query 拿全一层的元数据，实测快一个量级。
 */
class DocFs(private val context: Context) {

    private val resolver get() = context.contentResolver

    class NoAccessException(message: String) : Exception(message)

    fun rootDocId(tree: Uri): String = try {
        DocumentsContract.getTreeDocumentId(tree)
    } catch (e: Exception) {
        throw NoAccessException("无法读取文件夹授权，请重新选择文件夹。${e.message ?: ""}")
    }

    fun checkAccess(tree: Uri) {
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootDocId(tree))
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { }
                ?: throw NoAccessException("文件夹授权已失效，请重新选择文件夹。")
        } catch (e: NoAccessException) {
            throw e
        } catch (e: Exception) {
            throw NoAccessException("文件夹不可访问，请重新选择文件夹。${e.message ?: ""}")
        }
    }

    // ───────────────────────── 读 ─────────────────────────

    fun listChildren(tree: Uri, parentDocId: String, parentPath: String): List<DocEntry> {
        val out = ArrayList<DocEntry>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = if (c.isNull(2)) "" else c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val mtime = if (c.isNull(4)) 0L else c.getLong(4)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                out += DocEntry(
                    docId = docId,
                    name = name,
                    path = if (parentPath.isEmpty()) name else "$parentPath/$name",
                    isDirectory = isDir,
                    size = size,
                    lastModified = mtime,
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                )
            }
        }
        return out
    }

    /** 按相对路径找条目，逐级下钻。找不到返回 null。 */
    fun resolve(tree: Uri, path: String): DocEntry? {
        val clean = path.trim('/').trim()
        if (clean.isEmpty()) return null
        var currentDocId = rootDocId(tree)
        var currentPath = ""
        val segments = clean.split('/').filter { it.isNotEmpty() }
        var found: DocEntry? = null
        for (seg in segments) {
            val child = listChildren(tree, currentDocId, currentPath).firstOrNull { it.name == seg }
                ?: return null
            found = child
            currentDocId = child.docId
            currentPath = child.path
        }
        return found
    }

    /** 列出一层子项。 */
    fun listPath(tree: Uri, dirPath: String): List<DocEntry> {
        val clean = dirPath.trim('/')
        if (clean.isEmpty()) {
            return listChildren(tree, rootDocId(tree), "")
        }
        val dir = resolve(tree, clean) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return listChildren(tree, dir.docId, dir.path)
    }

    /** 递归收集所有文件（不含目录），可按相对路径前缀过滤。 */
    fun walk(tree: Uri, startPath: String = ""): List<DocEntry> {
        val out = ArrayList<DocEntry>()
        val rootDocId = if (startPath.isBlank()) {
            rootDocId(tree)
        } else {
            resolve(tree, startPath.trim('/'))?.docId ?: return emptyList()
        }
        val startRel = startPath.trim('/')
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootDocId to startRel)
        while (stack.isNotEmpty()) {
            val (docId, rel) = stack.removeLast()
            val children = try {
                listChildren(tree, docId, rel)
            } catch (_: Exception) {
                continue
            }
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child.docId to child.path)
                } else {
                    out += child
                }
            }
        }
        return out
    }

    fun readBytes(entry: DocEntry): ByteArray =
        resolver.openInputStream(entry.uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("无法读取 ${entry.path}")

    /**
     * 打开输入流。给「边读边打包」用——打 zip 时不必先把整个文件读进内存。
     * 拿不到就返回 null，交给调用方跳过。
     */
    fun openInput(entry: DocEntry): java.io.InputStream? =
        runCatching { resolver.openInputStream(entry.uri) }.getOrNull()

    fun readText(entry: DocEntry): String = String(readBytes(entry), Charsets.UTF_8)

    // ───────────────────────── 写 ─────────────────────────

    /** 覆盖写文本。先试 "wt"（截断）模式，失败退回 "w"。 */
    fun writeText(entry: DocEntry, text: String) = writeBytes(entry, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(entry: DocEntry, bytes: ByteArray) {
        val stream = try {
            resolver.openOutputStream(entry.uri, "wt")
        } catch (_: Exception) {
            null
        } ?: resolver.openOutputStream(entry.uri, "w")
        ?: throw java.io.IOException("无法写入 ${entry.path}")
        stream.use {
            it.write(bytes)
            it.flush()
        }
    }

    /** 顺序创建缺失的目录，返回最后一级。 */
    fun ensureDir(tree: Uri, path: String): DocEntry? {
        val clean = path.trim('/')
        if (clean.isEmpty()) return null
        var currentDocId = rootDocId(tree)
        var currentPath = ""
        var entry: DocEntry? = null
        for (seg in clean.split('/').filter { it.isNotEmpty() }) {
            val children = listChildren(tree, currentDocId, currentPath)
            val existing = children.firstOrNull { it.name == seg }
            if (existing != null && existing.isDirectory) {
                entry = existing
                currentDocId = existing.docId
                currentPath = existing.path
                continue
            }
            if (existing != null && !existing.isDirectory) {
                // 同名文件挡住了目录，删掉它再建
                runCatching { DocumentsContract.deleteDocument(resolver, existing.uri) }
            }
            val created = createDocument(tree, currentDocId, currentPath, seg, MIME_DIR) ?: return null
            entry = created
            currentDocId = created.docId
            currentPath = created.path
        }
        return entry
    }

    /** 在指定目录下建/覆盖文件。已存在则直接覆盖内容。 */
    fun putFile(tree: Uri, dirPath: String, name: String, bytes: ByteArray): DocEntry? {
        val parentDocId = if (dirPath.isBlank()) rootDocId(tree) else ensureDir(tree, dirPath)?.docId ?: return null
        val existing = listChildren(tree, parentDocId, dirPath.trim('/')).firstOrNull { it.name == name }
        if (existing != null && !existing.isDirectory) {
            writeBytes(existing, bytes)
            return existing
        }
        if (existing != null) return null
        val created = createDocument(tree, parentDocId, dirPath.trim('/'), name, mimeFor(name)) ?: return null
        writeBytes(created, bytes)
        return created
    }

    fun putText(tree: Uri, dirPath: String, name: String, text: String): DocEntry? =
        putFile(tree, dirPath, name, text.toByteArray(Charsets.UTF_8))

    /**
     * 打开一个文件的输出流（不存在则创建，存在则截断）。
     * 下载时用——先拿到流直接往里灌，不用把整包内容先读进内存。
     */
    fun openOutput(tree: Uri, path: String): java.io.OutputStream {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val parentDocId = if (dir.isBlank()) {
            rootDocId(tree)
        } else {
            ensureDir(tree, dir)?.docId ?: throw java.io.IOException("无法创建目录：$dir")
        }
        val existing = listChildren(tree, parentDocId, dir).firstOrNull { it.name == name }
        val entry = if (existing != null && !existing.isDirectory) {
            existing
        } else {
            createDocument(tree, parentDocId, dir, name, mimeFor(name))
                ?: throw java.io.IOException("无法创建文件：$path")
        }
        val stream = try {
            resolver.openOutputStream(entry.uri, "wt")
        } catch (_: Exception) {
            null
        } ?: resolver.openOutputStream(entry.uri, "w")
        ?: throw java.io.IOException("无法写入：$path")
        return stream
    }

    fun delete(entry: DocEntry): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, entry.uri) }.getOrDefault(false)

    fun rename(tree: Uri, entry: DocEntry, newName: String): DocEntry? {
        val uri = runCatching { DocumentsContract.renameDocument(resolver, entry.uri, newName) }.getOrNull()
            ?: return null
        val newDocId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return entry.copy(
            docId = newDocId,
            name = newName,
            path = entry.path.substringBeforeLast('/', "") + if (entry.path.contains('/')) "/$newName" else newName,
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, newDocId),
        )
    }

    private fun createDocument(
        tree: Uri,
        parentDocId: String,
        parentPath: String,
        name: String,
        mime: String,
    ): DocEntry? {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
        val created = runCatching { DocumentsContract.createDocument(resolver, parentUri, mime, name) }
            .getOrNull() ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(created) }.getOrNull() ?: return null
        return DocEntry(
            docId = docId,
            name = name,
            path = if (parentPath.isEmpty()) name else "$parentPath/$name",
            isDirectory = mime == MIME_DIR,
            size = 0L,
            lastModified = System.currentTimeMillis(),
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
        )
    }

    /** 用文件名后缀猜 MIME。SAF 的 provider 会据此决定要不要补扩展名，所以尽量给准。 */
    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "xml" -> "application/xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "yaml", "yml" -> "text/yaml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    companion object {
        const val MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR
    }
}
