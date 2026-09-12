package org.eu.gjry.gridea_PRO.sync

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.eu.gjry.gridea_PRO.data.FileEntry
import org.eu.gjry.gridea_PRO.data.RemoteConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.Properties

/**
 * SMB/CIFS 客户端，基于 jcifs-ng。
 *
 * 目标场景是「手机连家里 NAS / 局域网共享，把博客文件夹同步过去」，
 * 所以固定走 SMB2/3（SMB1 在现在的 Windows 和 NAS 上默认都关了）。
 */
class SmbClient(private val cfg: RemoteConfig) : RemoteClient {

    private val ensuredDirs = HashSet<String>()

    /**
     * 注意：lazy，不是直接初始化。
     *
     * jcifs 的 PropertyConfiguration 构造时会调 Config.getLocalHost() 解析本机地址
     * （读 jcifs.smb.client.laddr，没配就走系统默认解析），那是实打实的网络调用。
     * 写成属性初始化，就等于「谁 new 出 SmbClient，谁所在线程就得是 IO 线程」——
     * 调用方一旦在主线程上 new，抛的就是 NetworkOnMainThreadException，
     * 而且会被下面这个 catch 伪装成「SMB 上下文初始化失败」，非常难查。
     *
     * 改成 lazy 后构造函数是纯的，初始化推迟到第一次真正使用，
     * 而所有使用点都在 IO 线程上（SyncEngine 与测试连接都已 withContext(Dispatchers.IO)）。
     */
    private val ctx: CIFSContext by lazy {
        try {
            val props = Properties().apply {
                // SMB1 已过时，直接要 SMB2 起步；上限给到 3.1.1
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.connTimeout", "20000")
                setProperty("jcifs.smb.client.responseTimeout", "60000")
                setProperty("jcifs.smb.client.soTimeout", "120000")
                // 只走 DNS，不开 NetBIOS 广播（省电，也避开很多路由器的组播封锁）
                setProperty("jcifs.resolveOrder", "DNS")
            }
            BaseContext(PropertyConfiguration(props)).withCredentials(
                NtlmPasswordAuthenticator(
                    cfg.domain.trim().ifBlank { null },
                    cfg.username.trim().ifBlank { null },
                    cfg.password,
                ),
            )
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("SMB 上下文初始化失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val baseUrl: String = buildString {
        append("smb://")
        append(cfg.host.trim().removePrefix("smb://").substringBefore('/').trimEnd('/'))
        if (cfg.port > 0) append(":").append(cfg.port)
        append("/")
        val share = cfg.share.trim().trim('/')
        if (share.isNotEmpty()) {
            share.split('/').forEach { append(enc(it)).append("/") }
        }
        val base = cfg.basePath.trim().trim('/')
        if (base.isNotEmpty()) {
            base.split('/').forEach { append(enc(it)).append("/") }
        }
    }

    // ───────────────────────── 接口实现 ─────────────────────────

    override fun probe() {
        val f = try {
            SmbFile(baseUrl, ctx)
        } catch (e: Exception) {
            throw RemoteException("SMB 地址无效：$baseUrl\n${e.message ?: ""}")
        }
        val exists = try {
            f.exists()
        } catch (e: Exception) {
            throw RemoteException(
                "连接 SMB 失败：${e.message ?: e.javaClass.simpleName}\n" +
                    "检查地址、端口、共享名，以及用户名密码是否正确。",
            )
        }
        if (!exists) {
            throw RemoteException("远端目录不存在：$baseUrl")
        }
    }

    override fun list(root: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        val start = try {
            SmbFile(urlFor(root, true), ctx)
        } catch (e: Exception) {
            throw RemoteException("SMB 路径无效：$root")
        }
        if (!start.exists()) throw RemoteException("远端目录不存在：$baseUrl$root")

        val stack = ArrayDeque<Pair<SmbFile, String>>()
        stack.addLast(start to "")

        while (stack.isNotEmpty()) {
            val (dir, rel) = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                continue
            }
            for (child in children) {
                val name = child.name.trimEnd('/')
                if (name.isBlank()) continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                if (child.isDirectory) {
                    stack.addLast(child to childRel)
                } else {
                    out += FileEntry(
                        path = childRel,
                        isDirectory = false,
                        size = runCatching { child.length() }.getOrDefault(0L),
                        lastModified = runCatching { child.lastModified() }.getOrDefault(0L),
                    )
                }
            }
        }
        return out
    }

    override fun download(remotePath: String, sink: OutputStream, onBytes: (Long) -> Unit) {
        val f = SmbFile(urlFor(remotePath, false), ctx)
        try {
            f.getInputStream().use { input ->
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
        } catch (e: Exception) {
            throw RemoteException("下载失败：$remotePath\n${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun upload(remotePath: String, source: InputStream, size: Long, onBytes: (Long) -> Unit) {
        ensureDir(RemotePaths.parentOf(remotePath))
        val f = try {
            SmbFile(urlFor(remotePath, false), ctx)
        } catch (e: Exception) {
            throw RemoteException("SMB 路径无效：$remotePath")
        }
        try {
            // getOutputStream() 默认带 O_TRUNC，会覆盖旧内容
            f.getOutputStream().use { out ->
                val buf = ByteArray(BUFFER)
                var written = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    written += n
                    onBytes(written)
                }
                out.flush()
            }
        } catch (e: Exception) {
            throw RemoteException("上传失败：$remotePath\n${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { source.close() }
        }
    }

    override fun ensureDir(remotePath: String) {
        val clean = remotePath.trim('/').trim()
        if (clean.isEmpty()) return
        if (!ensuredDirs.add(clean)) return
        try {
            val f = SmbFile(urlFor(clean, true), ctx)
            if (!f.exists()) f.mkdirs()
        } catch (_: Exception) {
            // 建不了就让后面的写操作报错，这里不拦
        }
    }

    override fun stat(remotePath: String): FileEntry? {
        val clean = remotePath.trim('/').trim()
        if (clean.isEmpty()) return null
        return try {
            val f = SmbFile(urlFor(clean, false), ctx)
            if (!f.exists()) return null
            FileEntry(clean, f.isDirectory, f.length(), f.lastModified())
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        runCatching { ctx.close() }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun urlFor(relPath: String, asCollection: Boolean): String {
        val clean = relPath.trim('/').trim()
        val sb = StringBuilder(baseUrl)
        if (clean.isNotEmpty()) {
            clean.split('/').forEach { seg ->
                if (seg.isNotBlank()) sb.append(enc(seg)).append("/")
            }
        }
        if (!asCollection && clean.isNotEmpty() && sb.endsWith('/')) {
            // 文件路径去掉尾斜杠
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    private fun enc(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
