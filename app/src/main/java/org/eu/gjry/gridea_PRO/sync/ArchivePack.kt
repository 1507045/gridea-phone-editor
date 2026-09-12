package org.eu.gjry.gridea_PRO.sync

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 压缩包工具。用 JDK 自带的 java.util.zip，不引第三方。
 *
 * 有三个必须自己处理的坑：
 *
 * 1. **中文文件名编码。** zip 格式诞生时没约定文件名编码。Windows 的
 *    「发送到 → 压缩文件夹」用系统 ANSI（简体中文 Windows 上是 GBK），
 *    而 PowerShell 的 Compress-Archive 和多数现代工具用 UTF-8。
 *    用错编码，解出来的每个中文路径都是乱码，同步会认成一批陌生文件。
 *    这里先按 UTF-8 读一遍条目名，出现替换字符（U+FFFD，即解码失败的标志）就改用 GBK。
 *
 * 2. **解压后必须还原修改时间。** 不还原的话，解压出来的文件 mtime 就是解压那一刻，
 *    每次同步都不一样，对比逻辑会认为「远端所有文件都变过」→ 每次全量重传。
 *    所以每个文件都 setLastModified(entry.time)。
 *
 * 3. **zip slip。** 压缩包里的路径是外部输入，`../../xxx` 这种名字拼出来能写到目标目录之外。
 *    解压前统一校验规范化路径仍在目标目录内，不合格的直接跳过。
 */
object ArchivePack {

    /** 解压结果。 */
    data class Extracted(val files: Int, val bytes: Long)

    /** 打包结果。 */
    data class Packed(val files: Int, val bytes: Long)

    // ───────────────────────── 解压 ─────────────────────────

    /**
     * 解压到 destDir，同路径覆盖。
     *
     * 返回解出多少个文件、共多少字节，用于在进度里报数。
     */
    fun extract(zip: File, destDir: File): Extracted {
        if (!zip.isFile) throw RemoteException("压缩包不存在：${zip.name}")
        destDir.mkdirs()
        val root = destDir.canonicalFile
        val charset = detectCharset(zip)

        var count = 0
        var bytes = 0L
        try {
            ZipFile(zip, charset).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val target = resolveInside(root, entry.name) ?: continue
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { out ->
                            bytes += input.copyTo(out)
                        }
                    }
                    count++
                    // 见类注释第 2 条：不还原时间，对比逻辑就废了
                    if (entry.time > 0) {
                        runCatching { target.setLastModified(entry.time) }
                    }
                }
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("解压失败：${e.message ?: e.javaClass.simpleName}")
        }
        return Extracted(count, bytes)
    }

    /** 列出目录下所有文件，键为相对路径（用 / 分隔）。 */
    fun listFiles(root: File): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        if (!root.isDirectory) return out
        val base = root.canonicalFile
        val stack = ArrayDeque<File>()
        stack.addLast(base)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    val rel = runCatching { child.canonicalFile.relativeTo(base).invariantSeparatorsPath }
                        .getOrNull() ?: continue
                    if (rel.isNotBlank() && !rel.startsWith("..")) out[rel] = child
                }
            }
        }
        return out
    }

    /**
     * 打包。内容怎么取由调用方决定——手机上的博客目录是 SAF 的 content:// ，
     * 不能直接当 File 读，所以这里只收「路径列表 + 打开方式」。
     *
     * @param entries 要打包的相对路径，用 / 分隔
     * @param readFrom 打开某个路径的输入流，返回 null 表示跳过
     * @param timeOf 取某个路径的修改时间，用于写进 zip（下次解压能还原）
     */
    fun pack(
        destZip: File,
        entries: List<String>,
        readFrom: (String) -> InputStream?,
        timeOf: (String) -> Long,
    ): Packed {
        destZip.parentFile?.mkdirs()
        var count = 0
        var bytes = 0L
        try {
            ZipOutputStream(destZip.outputStream().buffered()).use { zos ->
                for (path in entries) {
                    val input = readFrom(path) ?: continue
                    try {
                        val entry = ZipEntry(path)
                        val t = timeOf(path)
                        if (t > 0) entry.time = t
                        zos.putNextEntry(entry)
                        bytes += input.copyTo(zos)
                        zos.closeEntry()
                        count++
                    } catch (_: Exception) {
                        // 单个文件写失败不该让整包作废
                        runCatching { zos.closeEntry() }
                    } finally {
                        runCatching { input.close() }
                    }
                }
            }
        } catch (e: Exception) {
            throw RemoteException("打包失败：${e.message ?: e.javaClass.simpleName}")
        }
        return Packed(count, bytes)
    }

    // ───────────────────────── 内部 ─────────────────────────

    /** UTF-8 解码非法字节序列时会产出这个字符，用它判断编码猜错了。 */
    private const val REPLACEMENT = '\uFFFD'

    private val CHARSET_GBK: Charset by lazy { Charset.forName("GBK") }

    /**
     * 猜条目名的编码。只读条目名、不解压内容，代价很小。
     *
     * 判据：UTF-8 是自校验编码，非法字节序列必然解出 U+FFFD。
     * GBK 编码的中文路径按 UTF-8 解，绝大多数都会踩到非法序列，
     * 所以「出现替换字符 → 换 GBK」足够可靠。
     */
    private fun detectCharset(zip: File): Charset {
        val names = runCatching {
            ZipFile(zip, Charsets.UTF_8).use { zf ->
                val out = ArrayList<String>()
                val en = zf.entries()
                // 看前若干个就够，不必扫全表
                while (en.hasMoreElements() && out.size < 200) {
                    out += en.nextElement().name
                }
                out
            }
        }.getOrNull() ?: return Charsets.UTF_8

        if (names.any { it.contains(REPLACEMENT) }) return CHARSET_GBK
        return Charsets.UTF_8
    }

    /** 把条目名解析成目标目录内的文件；越界或非法则返回 null。 */
    private fun resolveInside(root: File, name: String): File? {
        val clean = name.replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) return null
        if (clean.split('/').any { it == ".." }) return null
        val candidate = File(root, clean)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path
        return if (canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)) {
            canonical
        } else {
            null
        }
    }
}
