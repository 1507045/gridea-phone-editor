package org.eu.gjry.gridea_PRO.sync

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.eu.gjry.gridea_PRO.data.FileEntry
import org.eu.gjry.gridea_PRO.data.RemoteConfig
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV 客户端。用 OkHttp 手写 PROPFIND / MKCOL / GET / PUT，
 * 没有引第三方 WebDAV 库——协议就那几条，自己写反而少踩坑，
 * 而且能精确拿到传输字节数来做真实进度条。
 */
class WebDavClient(private val cfg: RemoteConfig) : RemoteClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)   // 上传大文件不设上限
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val baseUrl: HttpUrl = buildBaseUrl(cfg)

    /** baseUrl 的路径部分，用于把 PROPFIND 返回的 href 折算成相对路径。 */
    private val basePrefix: String = baseUrl.encodedPath.let {
        if (it == "/") "" else it.trimEnd('/')
    }

    /** 记一下已经确保存在的远端目录，避免每次上传都重复 MKCOL。 */
    private val ensuredDirs = HashSet<String>()

    private val authHeader: String? =
        if (cfg.username.isNotBlank() || cfg.password.isNotBlank()) {
            val raw = "${cfg.username}:${cfg.password}"
            "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } else {
            null
        }

    // ───────────────────────── 接口实现 ─────────────────────────

    override fun probe() {
        val req = baseRequest(urlFor("", true))
            .method("PROPFIND", BODY_DEPTH0.toRequestBody(XML_TYPE))
            .header("Depth", "0")
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.isSuccessful -> Unit
                resp.code == 401 || resp.code == 403 ->
                    throw RemoteException("认证失败（HTTP ${resp.code}），检查用户名与密码")

                resp.code == 404 || resp.code == 409 ->
                    throw RemoteException("远端目录不存在（HTTP ${resp.code}）：${baseUrl}\n检查服务器地址和根目录设置")

                resp.code == 405 -> Unit // 少数服务端不支持 Depth:0，放过去
                else -> throw RemoteException("连接失败：HTTP ${resp.code}")
            }
        }
    }

    override fun list(root: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        val rootClean = root.trim('/')
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        queue.addLast(rootClean)

        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            if (!visited.add(dir)) continue
            val items = try {
                propfind(dir)
            } catch (e: RemoteException) {
                if (dir == rootClean) throw e
                continue
            }
            for (item in items) {
                val rel = item.relPath.trim('/')
                if (rel.isEmpty()) continue
                if (item.isDirectory) {
                    queue.addLast(rel)
                    continue
                }
                val relToRoot = if (rootClean.isEmpty()) {
                    rel
                } else {
                    if (!rel.startsWith("$rootClean/")) continue
                    rel.removePrefix("$rootClean/")
                }
                if (relToRoot.isBlank()) continue
                out += FileEntry(relToRoot, false, item.size, item.lastModified)
            }
        }
        return out
    }

    override fun download(remotePath: String, sink: OutputStream, onBytes: (Long) -> Unit) {
        val req = baseRequest(urlFor(remotePath, false)).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RemoteException("下载失败 HTTP ${resp.code}：$remotePath")
            }
            val body = resp.body ?: throw RemoteException("下载失败，响应为空：$remotePath")
            body.byteStream().use { input ->
                copyWithProgress(input, sink, onBytes)
            }
        }
    }

    override fun upload(remotePath: String, source: InputStream, size: Long, onBytes: (Long) -> Unit) {
        ensureDir(RemotePaths.parentOf(remotePath))
        val url = urlFor(remotePath, false)
        val body: RequestBody = if (size in 0..MAX_BUFFERED) {
            // 小文件读进内存：OkHttp 重试/重定向时会二次调用 writeTo，流式体会被耗尽
            val bytes = source.use { it.readBytes() }
            BufferedProgressBody(bytes, BINARY_TYPE, onBytes)
        } else {
            StreamProgressBody(source, size, BINARY_TYPE, onBytes)
        }
        val req = baseRequest(url).put(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RemoteException("上传失败 HTTP ${resp.code}：$remotePath")
            }
        }
    }

    override fun ensureDir(remotePath: String) {
        val clean = remotePath.trim('/').trim()
        if (clean.isEmpty()) return
        var acc = ""
        for (seg in clean.split('/')) {
            if (seg.isBlank()) continue
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            if (!ensuredDirs.add(acc)) continue
            val req = baseRequest(urlFor(acc, true)).method("MKCOL", null).build()
            try {
                client.newCall(req).execute().use { resp ->
                    // 201 = 建成；405 = 已存在。其余码不致命，继续往下走。
                }
            } catch (_: Exception) {
                // 建目录失败就让后续 PUT 去暴露问题，不在这里中断
            }
        }
    }

    override fun stat(remotePath: String): FileEntry? {
        val clean = remotePath.trim('/').trim()
        if (clean.isEmpty()) return null
        return try {
            // Depth: 0 表示只要这个资源本身的属性，不列子项
            val req = baseRequest(urlFor(clean, false))
                .method("PROPFIND", BODY_DEPTH1.toRequestBody(XML_TYPE))
                .header("Depth", "0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                val item = parseMultiStatus(xml).firstOrNull() ?: return null
                FileEntry(clean, item.isDirectory, item.size, item.lastModified)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.connectionPool.evictAll() }
    }

    // ───────────────────────── PROPFIND ─────────────────────────

    private class DavItem(
        val relPath: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    private fun propfind(dirRel: String): List<DavItem> {
        val url = urlFor(dirRel, true)
        val req = baseRequest(url)
            .method("PROPFIND", BODY_DEPTH1.toRequestBody(XML_TYPE))
            .header("Depth", "1")
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw RemoteException("认证失败（HTTP ${resp.code}）")

                resp.code == 404 || resp.code == 409 -> return emptyList()
                !resp.isSuccessful -> throw RemoteException("列目录失败 HTTP ${resp.code}：${dirRel.ifBlank { "/" }}")
            }
            val xml = resp.body?.string() ?: return emptyList()
            return parseMultiStatus(xml)
        }
    }

    private fun parseMultiStatus(xml: String): List<DavItem> {
        val doc: Document = try {
            buildFactory().newDocumentBuilder()
                .parse(xml.byteInputStream(Charsets.UTF_8))
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<DavItem>()
        for (respEl in elementsByLocalName(doc, "response")) {
            val hrefEl = findFirst(respEl, "href") ?: continue
            val href = hrefEl.textContent?.trim().orEmpty()
            val rel = relFromHref(href) ?: continue
            if (rel.isEmpty()) continue

            val isDir = findFirst(respEl, "collection") != null
            val sizeText = findFirst(respEl, "getcontentlength")?.textContent?.trim()
            val mtimeText = findFirst(respEl, "getlastmodified")?.textContent?.trim()

            out += DavItem(
                relPath = rel,
                isDirectory = isDir,
                size = sizeText?.toLongOrNull() ?: 0L,
                lastModified = parseHttpDate(mtimeText) ?: 0L,
            )
        }
        return out
    }

    /** href → 相对 baseUrl 的路径。可能是绝对 URL，也可能是绝对路径。 */
    private fun relFromHref(href: String): String? {
        val rawPath = try {
            if (href.startsWith("http://") || href.startsWith("https://")) {
                java.net.URI(href).path ?: return null
            } else {
                java.net.URI(href).path ?: return null
            }
        } catch (_: Exception) {
            return href.substringAfter("://", href).substringAfter('/', "").let { "/$it" }
        }
        val path = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (_: Exception) {
            rawPath
        }
        val prefix = basePrefix
        val rel = when {
            prefix.isEmpty() -> path
            path == prefix -> ""
            path.startsWith("$prefix/") -> path.substring(prefix.length)
            else -> return null
        }
        return rel.trim('/')
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun urlFor(relPath: String, asCollection: Boolean): HttpUrl {
        val b = baseUrl.newBuilder()
        relPath.split('/').forEach { seg ->
            if (seg.isNotBlank() && seg != ".") b.addPathSegment(seg.trim())
        }
        val url = b.build()
        return if (asCollection) url.newBuilder().addPathSegment("").build() else url
    }

    private fun baseRequest(url: HttpUrl): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        authHeader?.let { b.header("Authorization", it) }
        return b
    }

    private fun copyWithProgress(input: InputStream, sink: OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            sink.write(buf, 0, n)
            total += n
            onBytes(total)
        }
        sink.flush()
    }

    private fun buildFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isExpandEntityReferences = false
    }

    /** 服务端前缀五花八门（d: / D: / 默认命名空间），按 localName 找最稳。 */
    private fun elementsByLocalName(root: Document, local: String): List<Element> {
        val out = ArrayList<Element>()
        val byNs = root.getElementsByTagNameNS("*", local)
        if (byNs.length > 0) {
            for (i in 0 until byNs.length) (byNs.item(i) as? Element)?.let { out += it }
            return out
        }
        val all = root.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val e = all.item(i) as? Element ?: continue
            if (localNameOf(e).equals(local, ignoreCase = true)) out += e
        }
        return out
    }

    private fun findFirst(root: Element, local: String): Element? {
        val byNs = root.getElementsByTagNameNS("*", local)
        if (byNs.length > 0) return byNs.item(0) as? Element
        val all = root.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val e = all.item(i) as? Element ?: continue
            if (localNameOf(e).equals(local, ignoreCase = true)) return e
        }
        return null
    }

    private fun localNameOf(e: Element): String = e.localName ?: e.nodeName.substringAfter(':')

    private fun parseHttpDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        runCatching {
            return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }
        runCatching {
            return ZonedDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        }
        runCatching {
            return ZonedDateTime.parse(raw, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant().toEpochMilli()
        }
        return null
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val MAX_BUFFERED = 16L * 1024 * 1024
        const val USER_AGENT = "GrideaPro-Mobile/1.0"

        val XML_TYPE: MediaType? = "application/xml; charset=utf-8".toMediaTypeOrNull()
        val BINARY_TYPE: MediaType? = "application/octet-stream".toMediaTypeOrNull()

        const val BODY_DEPTH0 =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""

        const val BODY_DEPTH1 =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}

/**
 * 从用户输入拼出 baseUrl。
 * 用户可能写成 "https://nas.local:5006/dav/blog/" 也可能写成 "nas.local/dav/blog"，
 * 这里统一剥掉协议头再按需重建，避免出现 https://https:// 这种。
 */
internal fun buildBaseUrl(cfg: RemoteConfig): HttpUrl {
    var host = cfg.host.trim()
    host = host.removePrefix("https://").removePrefix("http://")
    host = host.substringBefore('/').substringBefore('?')
    if (host.isBlank()) throw RemoteException("服务器地址为空")

    // 用户在地址里写死端口时优先用它
    var portFromHost: Int? = null
    if (host.contains(':') && !host.startsWith("[")) {
        val maybe = host.substringAfterLast(':')
        maybe.toIntOrNull()?.let {
            portFromHost = it
            host = host.substringBeforeLast(':')
        }
    }
    if (host.isBlank()) throw RemoteException("服务器地址无效")

    val scheme = if (cfg.useHttps) "https" else "http"
    val port = cfg.port.takeIf { it > 0 }
        ?: portFromHost
        ?: if (cfg.useHttps) 443 else 80

    return try {
        val b = HttpUrl.Builder().scheme(scheme).host(host).port(port)
        cfg.basePath.split('/').forEach { seg ->
            if (seg.isNotBlank()) b.addPathSegment(seg.trim())
        }
        b.build()
    } catch (e: Exception) {
        throw RemoteException("服务器地址无效：${cfg.host}（${e.message ?: ""}）")
    }
}

/** 可重试的内存请求体（小文件）。 */
private class BufferedProgressBody(
    private val bytes: ByteArray,
    private val mediaType: MediaType?,
    private val onBytes: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = bytes.size.toLong()
    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(CHUNK, bytes.size - offset)
            sink.write(bytes, offset, n)
            offset += n
            onBytes(offset.toLong())
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}

/** 流式请求体（大文件），省内存但不可重试。 */
private class StreamProgressBody(
    private val input: InputStream,
    private val length: Long,
    private val mediaType: MediaType?,
    private val onBytes: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = length
    override fun writeTo(sink: BufferedSink) {
        val buf = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            sink.write(buf, 0, n)
            written += n
            onBytes(written)
        }
        input.close()
    }
}
