package org.eu.gjry.gridea_PRO.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 电脑端「打包服务」的客户端。
 *
 * 为什么非要这个东西：SMB 和 WebDAV 都只是文件读写协议——列目录、下载、上传、删除，
 * 仅此而已。**没有任何一条指令能让手机命令电脑执行压缩动作**，这是协议的能力边界，
 * 不是实现偷懒。所以想让「手机点一下，电脑就打包」成立，电脑上必须有个常驻的小服务
 * 收这个请求、动手压缩。
 *
 * 协议很简单，配套的 PowerShell 服务端在同目录的 gridea-pack-server.ps1：
 *
 * ```
 * GET /ping  → {"ok":true,"version":1}
 * GET /pack  → 触发打包，等打完再返回
 *              {"ok":true,"files":123,"bytes":456789,"seconds":2}
 *              失败时 {"ok":false,"error":"..."}
 * ```
 *
 * 打包是同步响应，所以 readTimeout 给到 3 分钟——站点大、机械硬盘慢的时候
 * 压缩确实可能要一会儿，超时太短会把正常情况误判成故障。
 */
class PackService(rawUrl: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val base: String = normalize(rawUrl)

    /** 打包结果。 */
    data class Packed(val files: Int, val bytes: Long, val seconds: Long)

    /** 探活。连不上返回 false，不抛异常——调用方常拿它做「要不要提示用户去启动服务」的判断。 */
    fun ping(): Boolean = try {
        val body = get("/ping") ?: return false
        JSONObject(body).optBoolean("ok", false)
    } catch (_: Exception) {
        false
    }

    /**
     * 请求打包。等服务端真的打完再返回。
     *
     * [contentOnly] 会作为 `?scope=content` 传过去，让电脑只打 posts / config /
     * post-images / images。站点里 themes 通常有两千多个文件、上百 MB，
     * 而「只同步文章与配置」开着时手机根本不要它们——不告诉电脑的话，
     * 每次同步都会白下载一个数量级的数据。
     *
     * 失败一律抛 [RemoteException]，文案尽量指出该怎么办——
     * 这个失败最常见的原因就是「电脑上那个服务没在跑」。
     */
    fun requestPack(contentOnly: Boolean = false): Packed {
        val path = if (contentOnly) "/pack?scope=content" else "/pack"
        val body = try {
            get(path)
        } catch (e: RemoteException) {
            throw RemoteException(
                "连不上电脑的打包服务（$base）。\n" +
                    "先在电脑上双击「安装开机自启.bat」（只需一次），之后开机就会自动跑；\n" +
                    "也可以双击「启动打包服务.bat」临时开一个窗口。\n" +
                    "手机和电脑必须在同一个 Wi-Fi 下。\n" +
                    "原始错误：${e.message ?: ""}",
            )
        } ?: throw RemoteException("打包服务没有返回内容（$base）")

        val obj = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw RemoteException("打包服务返回了无法解析的内容：${body.take(120)}")
        }

        if (!obj.optBoolean("ok", false)) {
            val detail = obj.optString("error").ifBlank { "服务端未说明原因" }
            throw RemoteException("电脑打包失败：$detail")
        }

        return Packed(
            files = obj.optInt("files", 0),
            bytes = obj.optLong("bytes", 0L),
            seconds = obj.optLong("seconds", 0L),
        )
    }

    fun close() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.connectionPool.evictAll() }
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun get(path: String): String? {
        val req = Request.Builder().url(base + path).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw RemoteException("打包服务返回 HTTP ${resp.code}")
                }
                resp.body?.string()
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 允许用户写成 `192.168.1.100:8765` 或 `http://192.168.1.100:8765`。 */
    private fun normalize(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) throw RemoteException("还没有填打包服务地址")
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u.trimEnd('/')
    }
}
