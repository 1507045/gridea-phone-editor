package org.eu.gjry.gridea_PRO.data

import java.time.LocalDateTime

/** 文章。字段与电脑端 Gridea Pro 的 domain.Post / postYaml 一一对应。 */
data class Post(
    val id: String = "",
    val title: String = "",
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val tags: List<String> = emptyList(),
    val tagIds: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val categoryIds: List<String> = emptyList(),
    val published: Boolean = true,
    val hideInList: Boolean = false,
    val isTop: Boolean = false,
    val feature: String = "",
    val content: String = "",
    val fileName: String = "",
) {
    val status: PostStatus
        get() = when {
            !published -> PostStatus.DRAFT
            hideInList -> PostStatus.HIDDEN
            else -> PostStatus.PUBLISHED
        }
}

enum class PostStatus(val label: String) {
    PUBLISHED("正常发布"),
    DRAFT("草稿"),
    HIDDEN("隐藏"),
}

/** 标签，对应 config/tags.json 里的元素。 */
data class Tag(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val used: Boolean = true,
    val color: String? = null,
)

/** 分类，对应 config/categories.json 里的元素。 */
data class Category(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val description: String = "",
)

/** 闪念，对应 config/memos.json 里的元素。 */
data class Memo(
    val id: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)

/** 本地或远端的一个文件条目，同步用。 */
data class FileEntry(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/** 同步方向。 */
enum class SyncMode(val label: String) {
    /** 双向：按快照判断谁改了就传谁。 */
    BIDIRECTIONAL("手动同步"),

    /** 本地 → 远端，全量覆盖。 */
    UPLOAD("上传到服务器"),

    /** 远端 → 本地，全量覆盖（下载覆盖）。 */
    DOWNLOAD("下载覆盖本地"),
}

/** 一次同步的结果。 */
data class SyncOutcome(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val skipped: Int = 0,
    val conflicts: Int = 0,
    val failed: List<String> = emptyList(),
    val seconds: Long = 0,
)

/** 同步/上传进度。 */
data class SyncProgress(
    val running: Boolean = false,
    val phase: String = "",
    val currentFile: String = "",
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null,
    /**
     * 真正出错时的信息。正常完成、或者任务被取消，这里都是 null。
     *
     * 单独立一个字段，是为了跟 [message] 分开：message 里既有成功摘要也有失败说明，
     * 界面没法只凭它判断该不该标红。而且任务被取消（例如界面销毁导致的协程取消）
     * 不是失败，绝不能往这里写东西。
     */
    val error: String? = null,
    val finished: Boolean = false,
    val outcome: SyncOutcome? = null,
) {
    /** 有确定总量时返回 0f..1f，否则 null（表示不确定进度）。 */
    val fraction: Float?
        get() = when {
            totalBytes > 0L -> (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> (doneFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            else -> null
        }
}

/** 远端传输协议。 */
enum class RemoteProtocol(val label: String) {
    WEBDAV("WebDAV"),
    SMB("SMB"),
}

/** 远端连接配置。 */
data class RemoteConfig(
    val protocol: RemoteProtocol = RemoteProtocol.WEBDAV,
    /** WebDAV: 形如 https://example.com/dav ；SMB: 形如 192.168.1.10 或 nas.local */
    val host: String = "",
    val port: Int = 0,
    /** SMB 的共享名，例如 blog ；WebDAV 留空。 */
    val share: String = "",
    /** 远端根目录，相对地址，例如 gridea 或 /gridea 。 */
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    /** WebDAV 是否用 https。 */
    val useHttps: Boolean = true,
    /** SMB 域名，一般留空（工作组或 NAS 通常用 WORKGROUP）。 */
    val domain: String = "",
    /**
     * 是否走「压缩包通道」。
     *
     * 开启后同步不再递归列目录 + 逐文件下载，而是：
     * 请求电脑打包 → 下载一个 zip → 解压到临时目录 → 拿它跟本地比 → 传差异 → 删临时目录。
     *
     * 之所以快：远端只读一次。列目录从 N 次请求变成 1 次，读内容也全在本地磁盘上。
     *
     * 之所以需要 [packUrl]：SMB / WebDAV 都只能读写文件，**没法让手机命令电脑执行压缩**。
     * 所以电脑上得有个常驻的小服务收请求、动手打包。没有它就只能用电脑上现成的旧 zip。
     */
    val useArchive: Boolean = false,
    /** 压缩包在远端的相对路径，例如 site.zip。 */
    val archivePath: String = "site.zip",
    /** 电脑端打包服务的地址，例如 http://192.168.1.100:8765 。留空表示电脑不能自动打包。 */
    val packUrl: String = "",
) {
    val isConfigured: Boolean
        get() = host.isNotBlank()

    /** 用于界面展示的目标描述。 */
    val displayTarget: String
        get() = when (protocol) {
            RemoteProtocol.WEBDAV -> buildString {
                append(if (useHttps) "https://" else "http://")
                append(host.trim())
                if (port > 0) append(":").append(port)
                if (basePath.isNotBlank()) {
                    append("/")
                    append(basePath.trim('/'))
                }
            }

            RemoteProtocol.SMB -> buildString {
                append("smb://")
                append(host.trim())
                append("/")
                append(share.trim().trim('/'))
                if (basePath.isNotBlank()) {
                    append("/")
                    append(basePath.trim('/'))
                }
            }
        }
}

/** 同步范围。 */
data class SyncScope(
    /** true 时只同步 posts / config / post-images / images，跳过大体积的 themes 等。 */
    val contentOnly: Boolean = false,
)
