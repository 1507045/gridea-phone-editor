package org.eu.gjry.gridea_PRO.sync

import org.eu.gjry.gridea_PRO.data.FileEntry
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * 远端存储的统一接口。WebDAV 和 SMB 各实现一份，
 * 上层的同步逻辑只认这个接口，不关心底下是协议。
 *
 * 所有方法都是阻塞的，调用方负责切到 IO 线程。
 */
interface RemoteClient : Closeable {

    /** 连接探测。连不上就直接抛，别让后面的操作一个个超时。 */
    fun probe()

    /**
     * 递归列出远端文件。
     * @param root 相对远端的根目录（一般就是配置里的 basePath，这里传空串表示远端根）
     * @return 相对 [root] 的文件条目（路径用 / 分隔，不含 [root] 前缀）
     */
    fun list(root: String): List<FileEntry>

    fun download(remotePath: String, sink: OutputStream, onBytes: (Long) -> Unit)

    fun upload(remotePath: String, source: InputStream, size: Long, onBytes: (Long) -> Unit)

    /** 确保远端目录存在（含各级父目录）。已存在时静默返回。 */
    fun ensureDir(remotePath: String)

    /**
     * 取单个远端的元数据（大小、修改时间）。取不到返回 null，**不要抛异常**。
     *
     * 只用在一个地方：压缩包通道下载前问一下 zip 多大，好让进度条是确定的而不是转圈。
     * 属于「有更好、没有也能用」，所以实现允许尽力而为。
     */
    fun stat(remotePath: String): FileEntry?
}

class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 判断一个相对路径是否该被同步（排除桌面端缓存等噪音）。 */
object RemotePaths {
    /** 把若干段拼成远端相对路径，过滤空段。 */
    fun join(vararg parts: String): String =
        parts.flatMap { it.split('/') }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")

    fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    fun nameOf(path: String): String = path.substringAfterLast('/')
}
