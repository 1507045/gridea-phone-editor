package org.eu.gjry.gridea_PRO.sync

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eu.gjry.gridea_PRO.data.FileEntry
import org.eu.gjry.gridea_PRO.data.Prefs
import org.eu.gjry.gridea_PRO.data.RemoteConfig
import org.eu.gjry.gridea_PRO.data.RemoteProtocol
import org.eu.gjry.gridea_PRO.data.SyncMode
import org.eu.gjry.gridea_PRO.data.SyncOutcome
import org.eu.gjry.gridea_PRO.data.SyncProgress
import org.eu.gjry.gridea_PRO.data.SyncScope
import org.eu.gjry.gridea_PRO.data.Vault
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import kotlin.math.abs

/**
 * 同步引擎。三种模式：
 *
 * - **手动同步**：拿上次同步快照做三方比对，本地改了就传、远端改了就拉，
 *   两边都改过就按修改时间取新的。这是日常最常用的那个。
 * - **上传覆盖**：本地全量推上去，服务器上的对应文件无条件覆盖。
 * - **下载覆盖**：远端全量拉下来，本地文件无条件覆盖。
 *
 * 另有 `flushPending()`：文章/闪念一保存就把文件塞进待传队列，
 * 有空就把队列里的文件传上去，实现「改完自动上传」。
 *
 * ## 两条通道
 *
 * - **逐文件**（默认）：递归列远端目录，然后一个文件一个文件地传。
 * - **压缩包**（[RemoteConfig.useArchive]）：让电脑把站点打成一个 zip，
 *   下载这一个文件、解压到临时目录，之后所有「读远端」都变成读本地磁盘。
 *
 * 压缩包通道快在哪：列目录从「每个子目录一次请求」变成「一次下载」，
 * 读内容也不再走网络。代价是电脑上得有东西负责打包——见 [PackService] 的注释。
 */
class SyncEngine(
    private val vault: Vault,
    private val prefs: Prefs,
    private val appScope: CoroutineScope,
    /** 临时目录的父目录（传 app 的 cacheDir）。压缩包要在这儿解压。 */
    private val workRoot: File,
) {

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private val mutex = Mutex()
    private var pendingJob: Job? = null
    private var syncJob: Job? = null

    // ───────────────────────── 对外入口 ─────────────────────────

    /**
     * 手动同步的入口。**必须在 app 级 scope 上跑，不要从界面的 rememberCoroutineScope 里调。**
     *
     * rememberCoroutineScope 的 scope 绑在组合上：页面一离开组合，Compose 就用
     * ForgottenCoroutineScopeException 把这个 scope 的 Job 取消掉。
     * 同步是几十秒起步的长任务，用户切个页签就会被掐断，而且取消异常会被下面的
     * catch 当成「失败」显示出来，文案就是 "rememberCoroutineScope left the composition"。
     *
     * 放到 appScope 后，切页签、锁屏，同步照跑；界面只是旁观 [progress]。
     */
    fun start(mode: SyncMode, syncScope: SyncScope) {
        if (syncJob?.isActive == true) return
        // 先置 running，让按钮立刻变灰，不用等 run() 内部第一次 publish
        publish(SyncProgress(running = true, phase = "准备中…"))
        syncJob = appScope.launch {
            try {
                run(mode, syncScope)
            } catch (e: CancellationException) {
                // 取消不是失败（进程退出、或者 URL 变了主动作废），静默复位
                publish(SyncProgress())
                throw e
            } catch (e: Throwable) {
                // run() 已经 publish 过失败信息了，这里只兜底它没覆盖到的异常
                if (!_progress.value.finished) {
                    publish(
                        SyncProgress(
                            running = false,
                            finished = true,
                            phase = "同步失败",
                            message = e.message ?: e.javaClass.simpleName,
                            error = e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    /** 手动触发一次待传队列。同样跑在 appScope 上。 */
    fun startFlush() {
        scheduleFlush()
    }

    /** 把改动过的文件记进待传队列；autoUpload 开着就顺手起一个上传任务。 */
    fun markDirty(paths: List<String>) {
        if (paths.isEmpty()) return
        val pending = prefs.loadPending()
        pending.addAll(paths)
        prefs.savePending(pending)
        if (prefs.autoUpload && prefs.remote.isConfigured && prefs.treeUri != null) {
            scheduleFlush()
        }
    }

    fun pendingPaths(): List<String> = prefs.loadPending().sorted()

    fun pendingCount(): Int = prefs.loadPending().size

    /** 异步把待传队列跑掉。同一时刻只会有一个任务在跑。 */
    fun scheduleFlush() {
        if (pendingJob?.isActive == true) return
        pendingJob = appScope.launch {
            runCatching { flushPending() }
        }
    }

    /**
     * 把待传队列里的文件传上去。远端没配好或没选文件夹时静默返回 null，
     * 队列保留着，等条件具备了再传。
     *
     * 只上传、不读远端，所以不需要压缩包通道——就算开着也走散文件。
     */
    suspend fun flushPending(): SyncOutcome? = mutex.withLock {
        val tree = prefs.treeUri ?: return@withLock null
        val cfg = prefs.remote
        if (!cfg.isConfigured) return@withLock null

        val paths = prefs.loadPending().toList()
        if (paths.isEmpty()) return@withLock null

        val started = System.currentTimeMillis()
        var client: RemoteClient? = null
        try {
            // 客户端构造也得在 IO 线程上：SMB 那边初始化就要解析本机地址
            client = withContext(Dispatchers.IO) { newClient(cfg) }
            publish(
                SyncProgress(
                    running = true,
                    phase = "自动上传",
                    totalFiles = paths.size,
                ),
            )
            withContext(Dispatchers.IO) { client.probe() }

            val sizes = HashMap<String, Long>()
            for (p in paths) {
                sizes[p] = withContext(Dispatchers.IO) {
                    runCatching { vault.readFileBytes(tree, p).size.toLong() }.getOrDefault(0L)
                }
            }
            var doneBytes = 0L
            val totalBytes = sizes.values.sum()
            publish(_progress.value.copy(totalBytes = totalBytes))

            var uploaded = 0
            val failed = mutableListOf<String>()
            val succeeded = mutableListOf<String>()

            for ((index, path) in paths.withIndex()) {
                publish(
                    _progress.value.copy(
                        currentFile = path,
                        doneFiles = index,
                        doneBytes = doneBytes,
                        phase = "自动上传",
                    ),
                )
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { vault.readFileBytes(tree, path) }.getOrNull()
                }
                if (bytes == null) {
                    failed += path
                    doneBytes += sizes[path] ?: 0L
                    continue
                }
                val base = doneBytes
                try {
                    withContext(Dispatchers.IO) {
                        client.upload(path, ByteArrayInputStream(bytes), bytes.size.toLong()) { n ->
                            publish(_progress.value.copy(doneBytes = base + n))
                        }
                    }
                    uploaded++
                    succeeded += path
                } catch (_: Exception) {
                    failed += path
                }
                doneBytes += bytes.size.toLong()
            }

            // 成功的从队列里摘掉，失败的留着下次再试
            val stillPending = prefs.loadPending().apply { removeAll(succeeded.toSet()) }
            prefs.savePending(stillPending)

            val outcome = SyncOutcome(
                uploaded = uploaded,
                failed = failed,
                seconds = (System.currentTimeMillis() - started) / 1000,
            )
            publish(
                SyncProgress(
                    running = false,
                    finished = true,
                    phase = if (failed.isEmpty()) "自动上传完成" else "部分文件上传失败",
                    doneFiles = paths.size,
                    totalFiles = paths.size,
                    doneBytes = totalBytes,
                    totalBytes = totalBytes,
                    outcome = outcome,
                    message = if (failed.isEmpty()) {
                        "已自动上传 $uploaded 个文件"
                    } else {
                        "已上传 $uploaded 个，${failed.size} 个失败：${failed.take(3).joinToString("、")}"
                    },
                    error = if (failed.isEmpty()) {
                        null
                    } else {
                        "${failed.size} 个文件上传失败：${failed.take(3).joinToString("、")}"
                    },
                ),
            )
            return@withLock outcome
        } catch (e: CancellationException) {
            // 队列原样留着，下次再传；取消不当失败记账
            publish(SyncProgress())
            throw e
        } catch (e: Exception) {
            // 失败不弹错，静默留着队列；下次手动同步或编辑时会再试
            val detail = e.message ?: e.javaClass.simpleName
            publish(
                SyncProgress(
                    running = false,
                    finished = true,
                    phase = "自动上传失败",
                    message = detail,
                    error = detail,
                ),
            )
            return@withLock null
        } finally {
            // NonCancellable：协程被取消时 finally 里的 withContext 会立刻抛出，
            // 连接就漏了。清理必须不受取消影响。
            runCatching { withContext(NonCancellable + Dispatchers.IO) { client?.close() } }
        }
    }

    /** 手动同步。 */
    suspend fun run(mode: SyncMode, scope: SyncScope): SyncOutcome = mutex.withLock {
        val tree = vault.requireTree()
        val cfg = prefs.remote
        if (!cfg.isConfigured) throw RemoteException("还没有配置远端服务器")

        val started = System.currentTimeMillis()
        var client: RemoteClient? = null
        var source: RemoteSource? = null
        var work: ArchiveWork? = null
        try {
            val c = withContext(Dispatchers.IO) { newClient(cfg) }
            client = c

            publish(SyncProgress(running = true, phase = "正在扫描本地文件…"))
            val zipPath = archivePathOf(cfg)
            val localFiles = withContext(Dispatchers.IO) { vault.listFiles(tree, scope.contentOnly) }
                .filterNot { it.path == zipPath }
            val localMap = localFiles.associateBy { it.path }

            // ── 远端来源：打包解压出来的临时目录，或者直连 ──
            if (cfg.useArchive) {
                work = prepareArchive(cfg, c, scope.contentOnly)
                source = work.source
            } else {
                publish(_progress.value.copy(phase = "正在连接 ${cfg.protocol.label}…"))
                withContext(Dispatchers.IO) { c.probe() }
                publish(_progress.value.copy(phase = "正在读取远端目录…"))
                source = NetworkSource(c)
            }
            val remote = source

            // 远端也要按同一套规则过滤，否则「只同步文章与配置」会被远端独有的大目录撑破。
            // 另外把压缩包本身摘掉：它是中转产物，不是站点内容，
            // 万一走了散文件通道把几 MB 的 zip 拉进博客目录就说不清了。
            val remoteFiles = withContext(Dispatchers.IO) { remote.list() }
                .filter { vault.isSyncable(it.path, scope.contentOnly) && it.path != zipPath }
            val remoteMap = remoteFiles.associateBy { it.path }

            val snapshot = prefs.loadSnapshot()
            val ops = plan(mode, localMap, remoteMap, snapshot)

            if (ops.isEmpty()) {
                val secs = (System.currentTimeMillis() - started) / 1000
                publish(
                    SyncProgress(
                        running = false,
                        finished = true,
                        phase = "已是最新",
                        outcome = SyncOutcome(seconds = secs),
                        message = "本地与服务器一致，无需传输",
                    ),
                )
                return@withLock SyncOutcome(seconds = secs)
            }

            val totalBytes = ops.sumOf { it.size }
            publish(
                SyncProgress(
                    running = true,
                    phase = "准备传输",
                    totalFiles = ops.size,
                    totalBytes = totalBytes,
                    doneFiles = 0,
                    doneBytes = 0,
                ),
            )

            var uploaded = 0
            var downloaded = 0
            var doneBytes = 0L
            var changed = false
            val failed = mutableListOf<String>()
            val succeeded = mutableListOf<String>()

            for ((index, op) in ops.withIndex()) {
                publish(
                    _progress.value.copy(
                        phase = if (op.upload) "上传中" else "下载中",
                        currentFile = op.path,
                        doneFiles = index,
                        doneBytes = doneBytes,
                    ),
                )
                val base = doneBytes
                try {
                    withContext(Dispatchers.IO) {
                        if (op.upload) {
                            // 上传永远走散文件：电脑端 Gridea Pro 认的就是散文件，
                            // 传个 zip 过去它不会自己解压
                            val bytes = vault.readFileBytes(tree, op.path)
                            c.upload(op.path, ByteArrayInputStream(bytes), bytes.size.toLong()) { n ->
                                publish(_progress.value.copy(doneBytes = base + n))
                            }
                        } else {
                            // 下载：逐文件模式走网络，压缩包模式直接从临时目录拷
                            vault.fs.openOutput(tree, op.path).use { out ->
                                remote.copyTo(op.path, out) { n ->
                                    publish(_progress.value.copy(doneBytes = base + n))
                                }
                            }
                        }
                    }
                    if (op.upload) {
                        uploaded++
                        changed = true
                    } else {
                        downloaded++
                    }
                    succeeded += op.path
                } catch (_: Exception) {
                    failed += op.path
                }
                doneBytes += op.size
            }

            publish(_progress.value.copy(phase = "正在记录同步状态…", doneBytes = totalBytes))
            saveSnapshot(tree, scope, remote)

            // 压缩包模式收尾：详见 finishArchive 的注释
            if (changed) {
                finishArchive(cfg, c, work, scope.contentOnly)
            }

            val seconds = (System.currentTimeMillis() - started) / 1000
            val outcome = SyncOutcome(
                uploaded = uploaded,
                downloaded = downloaded,
                skipped = (localMap.size + remoteMap.size) - ops.size,
                conflicts = 0,
                failed = failed,
                seconds = seconds,
            )
            prefs.lastSyncAt = System.currentTimeMillis()

            val summary = buildString {
                append("上传 $uploaded")
                append("，下载 $downloaded")
                if (failed.isNotEmpty()) append("，失败 ${failed.size}")
                append("，用时 ${seconds}s")
            }
            publish(
                SyncProgress(
                    running = false,
                    finished = true,
                    phase = if (failed.isEmpty()) "同步完成" else "同步完成（有失败项）",
                    doneFiles = ops.size,
                    totalFiles = ops.size,
                    doneBytes = totalBytes,
                    totalBytes = totalBytes,
                    outcome = outcome,
                    message = if (failed.isEmpty()) summary else "$summary\n失败：${failed.take(3).joinToString("、")}",
                    error = if (failed.isEmpty()) {
                        null
                    } else {
                        "${failed.size} 个文件没传成功：${failed.take(3).joinToString("、")}"
                    },
                ),
            )
            return@withLock outcome
        } catch (e: CancellationException) {
            // 取消不是失败，别 publish 成错误——否则界面会显示
            // "rememberCoroutineScope left the composition" 这种让用户一头雾水的文案
            publish(SyncProgress())
            throw e
        } catch (e: Exception) {
            val detail = e.message ?: e.javaClass.simpleName
            publish(
                SyncProgress(
                    running = false,
                    finished = true,
                    phase = "同步失败",
                    message = detail,
                    error = detail,
                ),
            )
            throw e
        } finally {
            // NonCancellable：协程被取消时 finally 里的 withContext 会立刻抛出，
            // 连接就漏了。清理必须不受取消影响。
            runCatching { withContext(NonCancellable + Dispatchers.IO) { source?.close() } }
            runCatching { withContext(NonCancellable + Dispatchers.IO) { work?.close() } }
            runCatching { withContext(NonCancellable + Dispatchers.IO) { client?.close() } }
        }
    }

    // ───────────────────────── 压缩包通道 ─────────────────────────

    /**
     * 准备远端来源：让电脑打包 → 下载 zip → 解压到临时目录。
     *
     * 临时目录用的是 app 私有 cache 下的普通文件，不走 SAF——
     * 解压后要频繁读这些文件做比对，走 ContentProvider 会慢一个量级。
     */
    private suspend fun prepareArchive(
        cfg: RemoteConfig,
        client: RemoteClient,
        contentOnly: Boolean,
    ): ArchiveWork {
        val root = File(workRoot, WORK_DIR)
        withContext(Dispatchers.IO) {
            // 清掉上次可能残留的（比如进程被杀导致的半截结果）
            root.deleteRecursively()
            root.mkdirs()
        }
        val zipFile = File(root, LOCAL_ZIP)
        val remoteDir = File(root, "remote")

        // 1) 请电脑打包。没配地址就跳过，用远端现成的那个包。
        var packedByServer = false
        if (cfg.packUrl.isNotBlank()) {
            publish(_progress.value.copy(phase = "正在让电脑打包…", doneBytes = 0, totalBytes = 0))
            val svc = PackService(cfg.packUrl)
            try {
                // 把「只同步文章与配置」透传给电脑：themes 那两千多个文件
                // 这边反正会过滤掉，让电脑别白打、手机别白下
                val r = withContext(Dispatchers.IO) { svc.requestPack(contentOnly) }
                packedByServer = true
                publish(
                    _progress.value.copy(
                        phase = "电脑已打包 ${r.files} 个文件（${r.seconds}s）",
                    ),
                )
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { svc.close() }
            }
        }

        // 2) 下载 zip。先问一下大小，进度条才不是「转圈」
        val archivePath = archivePathOf(cfg)
        val zipSize = withContext(Dispatchers.IO) {
            runCatching { client.stat(archivePath) }.getOrNull()?.size ?: 0L
        }
        publish(
            _progress.value.copy(
                phase = "正在下载压缩包…",
                totalBytes = zipSize,
                doneBytes = 0,
                totalFiles = 0,
                doneFiles = 0,
            ),
        )
        try {
            withContext(Dispatchers.IO) {
                zipFile.outputStream().buffered().use { out ->
                    client.download(archivePath, out) { n ->
                        publish(_progress.value.copy(doneBytes = n))
                    }
                }
            }
        } catch (e: Exception) {
            throw RemoteException(
                "下载压缩包失败：远端 ${archivePath} 取不到。\n" +
                    "确认电脑上已经打过包、压缩包就放在同步目录的根下（当前配置：$archivePath）。\n" +
                    "原始错误：${e.message ?: e.javaClass.simpleName}",
            )
        }

        // 3) 解压
        publish(_progress.value.copy(phase = "正在解压…", doneBytes = 0, totalBytes = 0))
        val extracted = withContext(Dispatchers.IO) {
            val r = ArchivePack.extract(zipFile, remoteDir)
            r to ArchivePack.listFiles(remoteDir)
        }
        publish(
            _progress.value.copy(
                phase = "压缩包解出 ${extracted.first.files} 个文件",
                totalFiles = extracted.first.files,
                doneFiles = extracted.first.files,
            ),
        )

        return ArchiveWork(
            root = root,
            zipFile = zipFile,
            remoteDir = remoteDir,
            source = ArchiveSource(extracted.second),
            packedByServer = packedByServer,
        )
    }

    /**
     * 压缩包模式的收尾。
     *
     * **电脑能打包**（配了 packUrl）时什么都不用做：下次同步它重新打一个包，
     * 里面自然包含这次传上去的改动。
     *
     * **电脑不能打包**时，这个 zip 就永远停在「上次打包」那一刻，手机这边的改动
     * 它永远看不见——下次同步还会拿旧包来比。所以由手机把本地内容重新打一个包传上去。
     * 代价是每次有改动就要多传一整个包，这也是更推荐把电脑端服务跑起来的原因。
     */
    private suspend fun finishArchive(
        cfg: RemoteConfig,
        client: RemoteClient,
        work: ArchiveWork?,
        contentOnly: Boolean,
    ) {
        if (work == null) return
        if (work.packedByServer) return

        val tree = vault.requireTree()
        val archivePath = archivePathOf(cfg)
        val localFiles = withContext(Dispatchers.IO) {
            vault.listFiles(tree, contentOnly)
        }.filterNot { it.path == archivePath }
        if (localFiles.isEmpty()) return

        val timeMap = localFiles.associate { it.path to it.lastModified }

        publish(_progress.value.copy(phase = "正在重新打包…", doneBytes = 0, totalBytes = 0))
        val outZip = File(work.root, UPLOAD_ZIP)
        val packed = withContext(Dispatchers.IO) {
            ArchivePack.pack(
                destZip = outZip,
                entries = localFiles.map { it.path },
                readFrom = { p -> vault.openInput(tree, p) },
                timeOf = { p -> timeMap[p] ?: 0L },
            )
        }

        val size = withContext(Dispatchers.IO) { outZip.length() }
        publish(
            _progress.value.copy(
                phase = "正在上传压缩包",
                totalFiles = packed.files,
                doneFiles = 0,
                totalBytes = size,
                doneBytes = 0,
            ),
        )
        withContext(Dispatchers.IO) {
            outZip.inputStream().buffered().use { input ->
                client.upload(archivePath, input, size) { n ->
                    publish(_progress.value.copy(doneBytes = n))
                }
            }
        }
    }

    // ───────────────────────── 内部 ─────────────────────────

    private class Op(val path: String, val upload: Boolean, val size: Long)

    private fun plan(
        mode: SyncMode,
        local: Map<String, FileEntry>,
        remote: Map<String, FileEntry>,
        snapshot: Map<String, LongArray>,
    ): List<Op> {
        val ops = ArrayList<Op>()
        val all = sortedSetOf<String>().apply {
            addAll(local.keys)
            addAll(remote.keys)
        }

        for (path in all) {
            val l = local[path]
            val r = remote[path]
            when (mode) {
                // 全量推：本地有的都传，无条件覆盖远端
                SyncMode.UPLOAD -> if (l != null) ops += Op(path, true, l.size)

                // 全量拉：远端有的都下，无条件覆盖本地
                SyncMode.DOWNLOAD -> if (r != null) ops += Op(path, false, r.size)

                SyncMode.BIDIRECTIONAL -> {
                    val snap = snapshot[path]
                    when {
                        l != null && r != null -> {
                            val localChanged =
                                snap == null || snap[0] != l.lastModified || snap[2] != l.size
                            val remoteChanged =
                                snap == null || snap[1] != r.lastModified || snap[2] != r.size
                            when {
                                !localChanged && !remoteChanged -> Unit // 都没动
                                localChanged && !remoteChanged -> ops += Op(path, true, l.size)
                                !localChanged && remoteChanged -> ops += Op(path, false, r.size)
                                else -> {
                                    // 首次同步或两边都动过：大小一致且时间接近就当同一份
                                    if (l.size == r.size && abs(l.lastModified - r.lastModified) < 3000) {
                                        Unit
                                    } else if (l.lastModified >= r.lastModified) {
                                        ops += Op(path, true, l.size)
                                    } else {
                                        ops += Op(path, false, r.size)
                                    }
                                }
                            }
                        }

                        l != null -> ops += Op(path, true, l.size)   // 远端没有 → 传上去
                        r != null -> ops += Op(path, false, r.size)  // 本地没有 → 拉下来
                    }
                }
            }
        }
        return ops
    }

    /** 传完之后重新采一遍两边的元数据，作为下次比对的基准。 */
    private suspend fun saveSnapshot(tree: Uri, scope: SyncScope, remote: RemoteSource) {
        val snapshot = HashMap<String, LongArray>()
        try {
            val local = withContext(Dispatchers.IO) { vault.listFiles(tree, scope.contentOnly) }
            val remoteFiles = withContext(Dispatchers.IO) { remote.list() }
                .filter { vault.isSyncable(it.path, scope.contentOnly) }
            val remoteMap = remoteFiles.associateBy { it.path }
            for (l in local) {
                val r = remoteMap[l.path] ?: continue
                snapshot[l.path] = longArrayOf(l.lastModified, r.lastModified, l.size)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 采不到就算了，下次同步会退化成「按修改时间取新的」
        }
        prefs.saveSnapshot(snapshot)
    }

    private fun newClient(cfg: RemoteConfig): RemoteClient = when (cfg.protocol) {
        RemoteProtocol.WEBDAV -> WebDavClient(cfg)
        RemoteProtocol.SMB -> SmbClient(cfg)
    }

    /**
     * 压缩包在远端的相对路径，归一化过。
     *
     * 它跟普通站点文件走的是同一条通道（SMB / WebDAV 只能读写文件，没别的路），
     * 所以必须从「站点内容」里摘出去，否则它会被当成一个普通文件参与比对。
     */
    private fun archivePathOf(cfg: RemoteConfig): String =
        cfg.archivePath.trim().trim('/').ifBlank { DEFAULT_ZIP }

    private fun publish(p: SyncProgress) {
        _progress.value = p
    }

    /** 清掉「完成」提示，免得回到界面时又弹一次。 */
    fun clearFinished() {
        if (_progress.value.finished && !_progress.value.running) {
            _progress.value = SyncProgress()
        }
    }

    private companion object {
        const val WORK_DIR = "sync_tmp"
        const val LOCAL_ZIP = "remote.zip"
        const val UPLOAD_ZIP = "upload.zip"
        const val DEFAULT_ZIP = "site.zip"
    }
}

// ───────────────────────── 远端来源 ─────────────────────────

/**
 * 「远端的内容」这件事的抽象。
 *
 * 有了它，主流程不必关心远端到底是网络那头的服务器，还是刚解压出来的临时目录——
 * 比对和传输逻辑共用一套代码。
 */
private interface RemoteSource : Closeable {
    /** 远端文件清单（不含目录）。 */
    fun list(): List<FileEntry>

    /** 把远端某个文件的内容写进 sink，边写边回调累计字节数。 */
    fun copyTo(path: String, sink: OutputStream, onBytes: (Long) -> Unit)

    override fun close() = Unit
}

/** 直连远端的来源：列目录和下载都走网络。 */
private class NetworkSource(private val client: RemoteClient) : RemoteSource {
    override fun list(): List<FileEntry> = client.list("")
    override fun copyTo(path: String, sink: OutputStream, onBytes: (Long) -> Unit) =
        client.download(path, sink, onBytes)
}

/** 压缩包解压出来的来源：所有读取都是本地磁盘操作。 */
private class ArchiveSource(private val files: Map<String, File>) : RemoteSource {

    override fun list(): List<FileEntry> = files.map { (path, f) ->
        FileEntry(
            path = path,
            isDirectory = false,
            size = f.length(),
            lastModified = f.lastModified(),
        )
    }

    override fun copyTo(path: String, sink: OutputStream, onBytes: (Long) -> Unit) {
        val f = files[path] ?: throw RemoteException("压缩包里没有这个文件：$path")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
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
    }
}

/**
 * 一次压缩包同步的临时产物。用完必须删——几百个文件的解压结果不该留在磁盘上。
 */
private class ArchiveWork(
    val root: File,
    val zipFile: File,
    val remoteDir: File,
    val source: ArchiveSource,
    /** 这个包是不是电脑刚打的。是的话收尾就不用手机重打包了。 */
    val packedByServer: Boolean,
) : Closeable {
    override fun close() {
        runCatching { root.deleteRecursively() }
    }
}
