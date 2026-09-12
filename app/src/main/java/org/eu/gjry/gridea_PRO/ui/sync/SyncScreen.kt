package org.eu.gjry.gridea_PRO.ui.sync

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eu.gjry.gridea_PRO.data.RemoteConfig
import org.eu.gjry.gridea_PRO.data.RemoteProtocol
import org.eu.gjry.gridea_PRO.data.SyncMode
import org.eu.gjry.gridea_PRO.data.SyncProgress
import org.eu.gjry.gridea_PRO.data.SyncScope
import org.eu.gjry.gridea_PRO.state.AppState
import org.eu.gjry.gridea_PRO.sync.PackService
import org.eu.gjry.gridea_PRO.sync.SyncEngine
import org.eu.gjry.gridea_PRO.sync.WebDavClient
import org.eu.gjry.gridea_PRO.sync.SmbClient
import org.eu.gjry.gridea_PRO.ui.common.GhostButton
import org.eu.gjry.gridea_PRO.ui.common.HintText
import org.eu.gjry.gridea_PRO.ui.common.InlineNotice
import org.eu.gjry.gridea_PRO.ui.common.LabeledField
import org.eu.gjry.gridea_PRO.ui.common.PrimaryButton
import org.eu.gjry.gridea_PRO.ui.common.SectionCard
import org.eu.gjry.gridea_PRO.ui.common.SectionTitle
import org.eu.gjry.gridea_PRO.ui.common.SegmentedControl
import org.eu.gjry.gridea_PRO.ui.common.SmallOutlinedButton
import org.eu.gjry.gridea_PRO.ui.common.SwitchRow
import org.eu.gjry.gridea_PRO.ui.icons.GIcons
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrange
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangeLine
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangePress
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangeSoft
import org.eu.gjry.gridea_PRO.ui.theme.DangerRed
import org.eu.gjry.gridea_PRO.ui.theme.Ink
import org.eu.gjry.gridea_PRO.ui.theme.InkFaint
import org.eu.gjry.gridea_PRO.ui.theme.InkSoft
import org.eu.gjry.gridea_PRO.ui.theme.LineSoft
import org.eu.gjry.gridea_PRO.ui.theme.Paper
import org.eu.gjry.gridea_PRO.ui.theme.PaperSoft
import org.eu.gjry.gridea_PRO.ui.theme.PaperSunken
import org.eu.gjry.gridea_PRO.ui.theme.SuccessGreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 同步页：配服务器 → 传文件 → 看进度。
 *
 * 三种传法各有各的用处：
 * 手动同步是日常；上传覆盖是在手机上改完想一把推平服务器；
 * 下载覆盖是电脑上改了、想把手机上的旧版本整体冲掉。
 */
@Composable
fun SyncScreen(
    state: AppState,
    sync: SyncEngine,
    onPickFolder: () -> Unit,
) {
    val progress by sync.progress.collectAsState()
    val folderLabel by state.folderLabel.collectAsState()
    val autoUpload by state.autoUpload.collectAsState()
    val contentOnly by state.contentOnly.collectAsState()
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf(state.remoteConfig()) }
    val pendingCount = remember(progress) { sync.pendingCount() }
    // progress 一变就重读一次，同步跑完这里会自动换成新时间
    val lastSyncText = remember(progress) { formatSyncTime(state.lastSyncMillis()) }

    /** 保存配置 / 测试连接的提示。第二个字段标记是不是坏消息，决定标红还是标绿。 */
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    /** 压缩包通道那块的提示，跟服务器那块分开，免得上下一改就串了。 */
    var packNotice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val running = progress.running
    // 失败信息从 app 级的进度流里取。同步不再跑在本页的 scope 上，
    // 所以结果必须放在这里，页面被销毁重建也还在。
    val syncError = progress.error

    fun persist() = state.saveRemote(cfg)

    fun startSync(mode: SyncMode) {
        persist()
        notice = null
        // 交给 SyncEngine 在 app 级 scope 上跑：本页的 rememberCoroutineScope
        // 一旦离开组合就会被取消（ForgottenCoroutineScopeException），
        // 同步会被中途掐断，还会把取消异常显示成失败原因。
        sync.start(mode, SyncScope(contentOnly = state.contentOnly.value))
    }

    fun testConnection() {
        persist()
        notice = null
        scope.launch {
            notice = try {
                // 整段都丢到 IO：客户端的构造本身就可能解析地址，属于网络操作
                withContext(Dispatchers.IO) {
                    val client = when (cfg.protocol) {
                        RemoteProtocol.WEBDAV -> WebDavClient(cfg)
                        RemoteProtocol.SMB -> SmbClient(cfg)
                    }
                    client.use {
                        it.probe()
                    }
                }
                "连接成功：${cfg.displayTarget}" to false
            } catch (e: CancellationException) {
                // 离开页面导致的取消不是「连接失败」，原样抛回去
                throw e
            } catch (e: Exception) {
                "连接失败：${e.message ?: e.javaClass.simpleName}" to true
            }
        }
    }

    /** 探一下电脑端打包服务在不在。只发 /ping，不会真的触发打包。 */
    fun testPackService() {
        persist()
        packNotice = null
        scope.launch {
            packNotice = try {
                val ok = withContext(Dispatchers.IO) {
                    val svc = PackService(cfg.packUrl)
                    try {
                        svc.ping()
                    } finally {
                        svc.close()
                    }
                }
                if (ok) {
                    "打包服务在线" to false
                } else {
                    "没连上打包服务。确认电脑上那个窗口开着、手机和电脑在同一个 Wi-Fi。" to true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "没连上打包服务：${e.message ?: e.javaClass.simpleName}" to true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState()),
    ) {
        // ── 顶栏 ──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("同步", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (cfg.isConfigured) cfg.displayTarget else "还没有配置服务器",
                    color = InkFaint,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(GIcons.Sync, null, tint = BrandOrange, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.height(6.dp))

        // ── 博客文件夹 ────────────────────────────────
        SectionCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(GIcons.Folder, null, tint = BrandOrange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SectionTitle("博客文件夹")
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = folderLabel.ifBlank { "尚未选择" },
                        color = InkSoft,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SmallOutlinedButton(text = "更改", onClick = onPickFolder)
            }
            Spacer(Modifier.height(8.dp))
            HintText("同步就是拿这个文件夹里的内容跟服务器对。手机上写文章也是写进这个文件夹。")
        }

        Spacer(Modifier.height(12.dp))

        // ── 服务器配置 ────────────────────────────────
        SectionCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionTitle("服务器")
            Spacer(Modifier.height(10.dp))
            SegmentedControl(
                options = listOf(
                    RemoteProtocol.WEBDAV to "WebDAV",
                    RemoteProtocol.SMB to "SMB",
                ),
                selected = cfg.protocol,
                onSelect = { cfg = cfg.copy(protocol = it) },
            )

            Spacer(Modifier.height(12.dp))

            if (cfg.protocol == RemoteProtocol.WEBDAV) {
                LabeledField(
                    label = "服务器地址",
                    value = cfg.host,
                    onValueChange = { cfg = cfg.copy(host = it) },
                    placeholder = "nas.local 或 example.com",
                    supporting = "不要带 http:// 前缀，用下面的开关控制协议",
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        LabeledField(
                            label = "端口",
                            value = if (cfg.port > 0) cfg.port.toString() else "",
                            onValueChange = { v ->
                                cfg = cfg.copy(port = v.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0)
                            },
                            placeholder = if (cfg.useHttps) "443" else "80",
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    Box(modifier = Modifier.weight(1.6f)) {
                        LabeledField(
                            label = "根目录",
                            value = cfg.basePath,
                            onValueChange = { cfg = cfg.copy(basePath = it) },
                            placeholder = "dav/blog",
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = "使用 HTTPS",
                    subtitle = "局域网自建 WebDAV 一般是 http，那就关掉",
                    checked = cfg.useHttps,
                    onCheckedChange = { cfg = cfg.copy(useHttps = it) },
                )
            } else {
                HintText(
                    "把这台 Windows 电脑当文件服务器。先在电脑上把站点文件夹右键→属性→共享，" +
                        "记下弹窗里的「共享名」。\n" +
                        "例：电脑 IP 192.168.1.100、共享名 blog、站点就在共享根目录 → " +
                        "主机地址填 192.168.1.100，端口 445，共享名填 blog，子目录留空。\n" +
                        "用户名/密码用你登录 Windows 的那组（微软账户就填邮箱）。手机和电脑必须连同一个 Wi-Fi。",
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "主机地址",
                    value = cfg.host,
                    onValueChange = { cfg = cfg.copy(host = it) },
                    placeholder = "192.168.1.10 或 nas.local",
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        LabeledField(
                            label = "端口",
                            value = if (cfg.port > 0) cfg.port.toString() else "",
                            onValueChange = { v ->
                                cfg = cfg.copy(port = v.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0)
                            },
                            placeholder = "445",
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    Box(modifier = Modifier.weight(1.6f)) {
                        LabeledField(
                            label = "共享名",
                            value = cfg.share,
                            onValueChange = { cfg = cfg.copy(share = it) },
                            placeholder = "blog",
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "共享内的子目录",
                    value = cfg.basePath,
                    onValueChange = { cfg = cfg.copy(basePath = it) },
                    placeholder = "留空表示共享根目录",
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "域",
                    value = cfg.domain,
                    onValueChange = { cfg = cfg.copy(domain = it) },
                    placeholder = "一般不填，NAS 常是 WORKGROUP",
                )
            }

            Spacer(Modifier.height(10.dp))
            LabeledField(
                label = "用户名",
                value = cfg.username,
                onValueChange = { cfg = cfg.copy(username = it) },
                placeholder = "登录共享用的账号",
            )
            Spacer(Modifier.height(10.dp))
            LabeledField(
                label = "密码",
                value = cfg.password,
                onValueChange = { cfg = cfg.copy(password = it) },
                placeholder = "密码",
                visualTransformation = PasswordVisualTransformation(),
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = "保存配置",
                    icon = GIcons.Check,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        persist()
                        notice = "配置已保存" to false
                    },
                )
                GhostButton(
                    text = "测试连接",
                    icon = GIcons.Cloud,
                    modifier = Modifier.weight(1f),
                    onClick = { testConnection() },
                )
            }

            notice?.let { (text, isError) ->
                Spacer(Modifier.height(10.dp))
                InlineNotice(
                    text = text,
                    accent = if (isError) DangerRed else SuccessGreen,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 压缩包通道 ────────────────────────────────
        //
        // 这一块是给「远端文件多、逐文件比对慢」准备的。开着它，同步变成：
        // 让电脑打一个 zip → 下载 zip → 在手机上解压到临时目录 → 拿解压结果跟本地比 → 传差异 → 删临时目录。
        // 远端只读一次，剩下的都在本地磁盘上跑，比逐个文件发 PROPFIND/SMB 请求快得多。
        SectionCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionTitle("压缩包通道")
            SwitchRow(
                title = "通过压缩包同步",
                subtitle = "远端打成 zip 一次拉下来，本地解压后比对",
                checked = cfg.useArchive,
                onCheckedChange = { cfg = cfg.copy(useArchive = it) },
            )

            if (cfg.useArchive) {
                Spacer(Modifier.height(8.dp))
                HintText(
                    "为什么需要电脑端服务：SMB / WebDAV 只能读写文件，手机没法命令电脑压缩。" +
                        "所以电脑上要开一个小服务，手机点同步时请求它现场打包。\n" +
                        "在电脑上双击「安装开机自启.bat」一次即可，之后开机自动在后台跑，" +
                        "不用再管；只想临时用就双击「启动打包服务.bat」。\n" +
                        "没开服务也能用：只要远端那个 zip 是现成的，就按它比。",
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "压缩包在远端的路径",
                    value = cfg.archivePath,
                    onValueChange = { cfg = cfg.copy(archivePath = it) },
                    placeholder = "site.zip",
                    supporting = "相对根目录，例如 site.zip 或 backup/site.zip",
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "电脑端打包服务地址",
                    value = cfg.packUrl,
                    onValueChange = { cfg = cfg.copy(packUrl = it) },
                    placeholder = "192.168.1.100:8765",
                    supporting = "打包服务窗口启动时会显示这一行，照抄即可；留空就不用自动打包",
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    text = "测试打包服务",
                    icon = GIcons.Cloud,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { testPackService() },
                )
                packNotice?.let { (text, isError) ->
                    Spacer(Modifier.height(8.dp))
                    InlineNotice(text = text, accent = if (isError) DangerRed else SuccessGreen)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 传输 ──────────────────────────────────────
        SectionCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionTitle("传输")
            Spacer(Modifier.height(4.dp))
            HintText("先把上面的配置保存好，再点下面任何一个。")
            Spacer(Modifier.height(12.dp))

            PrimaryButton(
                text = if (running) "正在传输…" else "手动同步",
                icon = GIcons.Sync,
                enabled = !running && cfg.isConfigured,
                modifier = Modifier.fillMaxWidth(),
                onClick = { startSync(SyncMode.BIDIRECTIONAL) },
            )
            Spacer(Modifier.height(8.dp))
            HintText("比对两边，本地改的传上去、服务器改的拉下来；同一份两边都动过就按修改时间取新的。")

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = "上传覆盖",
                    icon = GIcons.Upload,
                    modifier = Modifier.weight(1f),
                    enabled = !running && cfg.isConfigured,
                    onClick = { startSync(SyncMode.UPLOAD) },
                )
                GhostButton(
                    text = "下载覆盖",
                    icon = GIcons.Download,
                    modifier = Modifier.weight(1f),
                    enabled = !running && cfg.isConfigured,
                    onClick = { startSync(SyncMode.DOWNLOAD) },
                )
            }
            Spacer(Modifier.height(6.dp))
            HintText("「上传覆盖」把手机上的文件全推上去；「下载覆盖」把服务器上的全拉下来盖掉本地。这两个不比对，直接盖。")

            // 进度
            if (running || progress.finished) {
                Spacer(Modifier.height(14.dp))
                ProgressBlock(progress)
            }

            syncError?.let {
                Spacer(Modifier.height(10.dp))
                InlineNotice(text = it, accent = DangerRed)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 选项 ──────────────────────────────────────
        SectionCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionTitle("选项")
            SwitchRow(
                title = "改完自动上传",
                subtitle = "写完文章、发完闪念，立刻把改动推上去",
                checked = autoUpload,
                onCheckedChange = { state.setAutoUpload(it) },
            )
            SwitchRow(
                title = "只同步文章与配置",
                subtitle = "只传 posts / config / post-images / images，跳过主题这些大块头",
                checked = contentOnly,
                onCheckedChange = { state.setContentOnly(it) },
            )

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "待上传 $pendingCount 个文件",
                        color = if (pendingCount > 0) BrandOrangePress else InkSoft,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (autoUpload) "开启自动上传后会自己传掉" else "自动上传关着，点右边手动传",
                        color = InkFaint,
                        fontSize = 11.sp,
                    )
                }
                SmallOutlinedButton(
                    text = "立即上传",
                    onClick = {
                        persist()
                        // 同上：走 app 级任务，别挂在离开组合就会被取消的页面 scope 上
                        sync.startFlush()
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
            Spacer(Modifier.height(8.dp))
            Text(
                text = "上次同步：$lastSyncText",
                color = InkFaint,
                fontSize = 11.5.sp,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ProgressBlock(progress: SyncProgress) {
    val fraction = progress.fraction
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PaperSunken, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (progress.running) BrandOrange else SuccessGreen,
                        shape = CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = progress.phase.ifBlank { if (progress.running) "传输中" else "完成" },
                color = Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (fraction != null) {
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    color = BrandOrangePress,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(9.dp))
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = BrandOrange,
                trackColor = LineSoft,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = BrandOrange,
                trackColor = LineSoft,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        if (progress.running || progress.totalFiles > 0) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = buildString {
                    if (progress.totalFiles > 0) {
                        append("${progress.doneFiles}/${progress.totalFiles} 个文件")
                    }
                    if (progress.totalBytes > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${humanSize(progress.doneBytes)} / ${humanSize(progress.totalBytes)}")
                    }
                },
                color = InkSoft,
                fontSize = 11.5.sp,
            )
        }

        if (progress.running && progress.currentFile.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = progress.currentFile,
                color = InkFaint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        progress.message?.let { msg ->
            Spacer(Modifier.height(8.dp))
            InlineNotice(
                text = msg,
                accent = if (progress.outcome?.failed.isNullOrEmpty()) SuccessGreen else DangerRed,
            )
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

/** 时间戳转成电脑端同款的 yyyy-MM-dd HH:mm。0 表示还没同步过。 */
private fun formatSyncTime(millis: Long): String {
    if (millis <= 0L) return "还没同步过"
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
