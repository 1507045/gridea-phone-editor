package org.eu.gjry.gridea_PRO.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地设置。用 SharedPreferences 就够——数据量小、无并发写入压力，
 * 不值得为它引入 DataStore。
 *
 * 注意：远端密码是明文存这里。这是个人自用工具，取值在「可用性」，
 * 不做加密；如果哪天要共享设备，记得清掉。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("gridea_pro_mobile", Context.MODE_PRIVATE)

    // ── 博客文件夹 ──────────────────────────────────────────────

    var treeUri: Uri?
        get() = sp.getString(KEY_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        set(value) {
            sp.edit().apply {
                if (value == null) remove(KEY_TREE) else putString(KEY_TREE, value.toString())
            }.apply()
        }

    var folderLabel: String
        get() = sp.getString(KEY_FOLDER_LABEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_FOLDER_LABEL, value).apply()

    // ── 同步配置 ────────────────────────────────────────────────

    var remote: RemoteConfig
        get() {
            val proto = runCatching {
                RemoteProtocol.valueOf(sp.getString(KEY_PROTO, "WEBDAV") ?: "WEBDAV")
            }.getOrDefault(RemoteProtocol.WEBDAV)
            return RemoteConfig(
                protocol = proto,
                host = sp.getString(KEY_HOST, "") ?: "",
                port = sp.getInt(KEY_PORT, 0),
                share = sp.getString(KEY_SHARE, "") ?: "",
                basePath = sp.getString(KEY_BASE, "") ?: "",
                username = sp.getString(KEY_USER, "") ?: "",
                password = sp.getString(KEY_PASS, "") ?: "",
                useHttps = sp.getBoolean(KEY_HTTPS, true),
                domain = sp.getString(KEY_DOMAIN, "") ?: "",
                useArchive = sp.getBoolean(KEY_USE_ARCHIVE, false),
                archivePath = sp.getString(KEY_ARCHIVE_PATH, "site.zip") ?: "site.zip",
                packUrl = sp.getString(KEY_PACK_URL, "") ?: "",
            )
        }
        set(value) = sp.edit().apply {
            putString(KEY_PROTO, value.protocol.name)
            putString(KEY_HOST, value.host)
            putInt(KEY_PORT, value.port)
            putString(KEY_SHARE, value.share)
            putString(KEY_BASE, value.basePath)
            putString(KEY_USER, value.username)
            putString(KEY_PASS, value.password)
            putBoolean(KEY_HTTPS, value.useHttps)
            putString(KEY_DOMAIN, value.domain)
            putBoolean(KEY_USE_ARCHIVE, value.useArchive)
            putString(KEY_ARCHIVE_PATH, value.archivePath)
            putString(KEY_PACK_URL, value.packUrl)
        }.apply()

    /** 改完文件后是否自动上传。 */
    var autoUpload: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPLOAD, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()

    /** 只同步文章与配置，不动 themes 这类大目录。 */
    var contentOnly: Boolean
        get() = sp.getBoolean(KEY_CONTENT_ONLY, true)
        set(value) = sp.edit().putBoolean(KEY_CONTENT_ONLY, value).apply()

    var lastSyncAt: Long
        get() = sp.getLong(KEY_LAST_SYNC, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC, value).apply()

    // ── 同步快照：path -> [本地mtime, 远端mtime, 大小] ───────────

    fun loadSnapshot(): MutableMap<String, LongArray> {
        val raw = sp.getString(KEY_SNAPSHOT, null) ?: return HashMap()
        return try {
            val obj = JSONObject(raw)
            val out = HashMap<String, LongArray>(obj.length() * 2)
            obj.keys().forEach { key ->
                val arr = obj.optJSONArray(key) ?: return@forEach
                if (arr.length() >= 3) {
                    out[key] = longArrayOf(arr.optLong(0), arr.optLong(1), arr.optLong(2))
                }
            }
            out
        } catch (_: Exception) {
            HashMap()
        }
    }

    fun saveSnapshot(snapshot: Map<String, LongArray>) {
        val obj = JSONObject()
        snapshot.forEach { (k, v) ->
            obj.put(k, JSONArray().put(v.getOrElse(0) { 0L }).put(v.getOrElse(1) { 0L }).put(v.getOrElse(2) { 0L }))
        }
        sp.edit().putString(KEY_SNAPSHOT, obj.toString()).apply()
    }

    // ── 待上传队列 ──────────────────────────────────────────────

    fun loadPending(): MutableSet<String> {
        val raw = sp.getString(KEY_PENDING, null) ?: return mutableSetOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableSetOf<String>()
            for (i in 0 until arr.length()) out += arr.optString(i)
            out
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    fun savePending(pending: Set<String>) {
        val arr = JSONArray()
        pending.forEach { arr.put(it) }
        sp.edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    private companion object {
        const val KEY_TREE = "tree_uri"
        const val KEY_FOLDER_LABEL = "folder_label"
        const val KEY_PROTO = "sync_protocol"
        const val KEY_HOST = "sync_host"
        const val KEY_PORT = "sync_port"
        const val KEY_SHARE = "sync_share"
        const val KEY_BASE = "sync_base"
        const val KEY_USER = "sync_user"
        const val KEY_PASS = "sync_pass"
        const val KEY_HTTPS = "sync_https"
        const val KEY_DOMAIN = "sync_domain"
        const val KEY_USE_ARCHIVE = "sync_use_archive"
        const val KEY_ARCHIVE_PATH = "sync_archive_path"
        const val KEY_PACK_URL = "sync_pack_url"
        const val KEY_AUTO_UPLOAD = "auto_upload"
        const val KEY_CONTENT_ONLY = "content_only"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_SNAPSHOT = "sync_snapshot"
        const val KEY_PENDING = "pending_uploads"
    }
}
