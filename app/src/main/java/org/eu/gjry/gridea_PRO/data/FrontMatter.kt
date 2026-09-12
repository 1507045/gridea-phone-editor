package org.eu.gjry.gridea_PRO.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * 电脑端 Gridea Pro 的 Markdown 头部格式：YAML front matter + 正文。
 *
 * 写入格式刻意对齐 `backend/internal/repository/post_repo.go` 里
 * `yaml.Marshal(postYaml)` 的输出（同样的 key 名、同样的 block 数组风格），
 * 这样手机写完电脑打开不会有任何差异感；读取则比电脑端更宽容
 * （兼容旧版 `date` 字段、`tags: [a, b]` 流式数组、单引号/双引号/裸值）。
 */
object FrontMatter {

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val ID_ALPHABET =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val PLAIN_SAFE = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} _./()+（）\\-]*$")

    fun formatTime(t: LocalDateTime): String = t.format(TIME_FMT)

    /** 生成 6 位 ID，与电脑端 nanoid 生成规则保持一致（同字符表、同长度）。 */
    fun newId(length: Int = 6): String = buildString(length) {
        repeat(length) { append(ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)]) }
    }

    // ───────────────────────────── 序列化 ─────────────────────────────

    fun serialize(post: Post): String {
        val sb = StringBuilder(512)
        sb.append("---\n")
        if (post.id.isNotBlank()) sb.append("id: ").append(scalar(post.id)).append('\n')
        sb.append("title: ").append(scalar(post.title)).append('\n')
        sb.append("createdAt: \"").append(formatTime(post.createdAt)).append("\"\n")
        sb.append("updated: \"").append(formatTime(post.updatedAt)).append("\"\n")
        appendList(sb, "tags", post.tags)
        appendList(sb, "tag_ids", post.tagIds)
        appendList(sb, "categories", post.categories)
        appendList(sb, "category_ids", post.categoryIds)
        sb.append("published: ").append(post.published).append('\n')
        sb.append("hideInList: ").append(post.hideInList).append('\n')
        sb.append("feature: ").append(scalar(post.feature)).append('\n')
        sb.append("isTop: ").append(post.isTop).append('\n')
        sb.append("---\n\n")
        sb.append(post.content)
        return sb.toString()
    }

    private fun appendList(sb: StringBuilder, key: String, values: List<String>) {
        val clean = values.filter { it.isNotBlank() }
        if (clean.isEmpty()) {
            sb.append(key).append(": []\n")
            return
        }
        sb.append(key).append(":\n")
        for (v in clean) {
            sb.append("  - ").append(scalar(v)).append('\n')
        }
    }

    /**
     * YAML 标量：能裸写就裸写，否则加单引号。
     * 规则保守一点没坏处——写坏一个引号电脑端整篇解析不出来。
     */
    private fun scalar(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "\"\""
        if (s.length > 200) return quote(s)
        if (!PLAIN_SAFE.matches(s)) return quote(s)
        if (s.contains(": ") || s.endsWith(":") || s.contains(" #")) return quote(s)
        if (s.startsWith("-") || s.startsWith("?") || s.startsWith("!")) return quote(s)
        if (s.equals("true", true) || s.equals("false", true) || s.equals("null", true)) return quote(s)
        return s
    }

    private fun quote(s: String): String = "'" + s.replace("'", "''") + "'"

    // ───────────────────────────── 解析 ─────────────────────────────

    /**
     * 解析一篇 Markdown。缺少 front matter 时不报错，退化成「只有正文」的文章，
     * 免得一个坏文件拖垮整个列表。
     */
    fun parse(raw: String, fileName: String): Post {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')

        var first = 0
        while (first < lines.size && lines[first].isBlank()) first++
        if (first >= lines.size || lines[first].trim() != "---") {
            return Post(
                title = fileName,
                fileName = fileName,
                content = text.trim(),
            )
        }

        var closing = -1
        var i = first + 1
        while (i < lines.size) {
            if (lines[i].trim() == "---") {
                closing = i
                break
            }
            i++
        }
        if (closing < 0) {
            return Post(title = fileName, fileName = fileName, content = text.trim())
        }

        val yamlLines = lines.subList(first + 1, closing)
        val body = lines.subList(closing + 1, lines.size).joinToString("\n").trim()
        val map = parseYaml(yamlLines)

        val createdRaw = map.str("createdAt").ifBlank { map.str("date") }
        val created = parseTime(createdRaw) ?: LocalDateTime.now()
        val updated = parseTime(map.str("updated")) ?: created

        val tags = map.list("tags")
        var tagIds = map.list("tag_ids")
        if (tagIds.isEmpty() && tags.isNotEmpty()) tagIds = tags.map { it }
        val categories = map.list("categories")
        var categoryIds = map.list("category_ids")
        if (categoryIds.isEmpty() && categories.isNotEmpty()) categoryIds = categories.map { it }

        return Post(
            id = map.str("id"),
            title = map.str("title").ifBlank { fileName },
            createdAt = created,
            updatedAt = updated,
            tags = tags,
            tagIds = tagIds,
            categories = categories,
            categoryIds = categoryIds,
            published = map.bool("published", true),
            hideInList = map.bool("hideInList", false),
            isTop = map.bool("isTop", false),
            feature = map.str("feature"),
            content = body,
            fileName = fileName,
        )
    }

    /** 极简 YAML：只吃 Gridea 会写出来的形态（标量 / 流式数组 / block 数组）。 */
    private fun parseYaml(lines: List<String>): YamlMap {
        val map = YamlMap()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }
            val colon = trimmed.indexOf(':')
            if (colon <= 0) {
                i++
                continue
            }
            val key = trimmed.substring(0, colon).trim()
            val valueRaw = trimmed.substring(colon + 1).trim()

            if (valueRaw.isEmpty()) {
                // 可能是 block 数组，也可能是空标量
                val items = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j]
                    if (next.isBlank()) {
                        j++
                        continue
                    }
                    val nt = next.trimStart()
                    if (!nt.startsWith("- ")) break
                    if (next.length - nt.length <= 0) break
                    items += unquote(nt.removePrefix("- ").trim())
                    j++
                }
                if (items.isNotEmpty()) {
                    map.put(key, items)
                    i = j
                    continue
                }
                map.put(key, "")
                i++
                continue
            }

            if (valueRaw.startsWith("[")) {
                map.put(key, parseFlowList(valueRaw))
            } else {
                map.put(key, unquote(valueRaw))
            }
            i++
        }
        return map
    }

    private fun parseFlowList(raw: String): List<String> {
        val end = raw.lastIndexOf(']')
        val inner = if (end > 0) raw.substring(1, end) else raw.substring(1)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var k = 0
        while (k < inner.length) {
            val c = inner[k]
            when {
                quote != null -> {
                    if (c == quote) {
                        if (quote == '\'' && k + 1 < inner.length && inner[k + 1] == '\'') {
                            cur.append('\'')
                            k++
                        } else {
                            quote = null
                        }
                    } else {
                        cur.append(c)
                    }
                }

                c == '\'' || c == '"' -> quote = c
                c == ',' -> {
                    out += cur.toString().trim()
                    cur.setLength(0)
                }

                else -> cur.append(c)
            }
            k++
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    private fun unquote(raw: String): String {
        val s = raw.trim()
        if (s.length >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length - 1).replace("''", "'")
        }
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
        }
        if (s == "~") return ""
        return s
    }

    fun parseTime(raw: String): LocalDateTime? {
        val s = raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (s.isEmpty()) return null
        return try {
            when {
                // 电脑端标准格式
                s.length >= 19 && s[4] == '-' && s[10] == ' ' ->
                    LocalDateTime.parse(s.substring(0, 19), TIME_FMT)

                // ISO / RFC3339
                s.contains('T') -> {
                    val cleaned = s.substringBefore('+').substringBeforeLast('.')
                    val base = if (cleaned.length >= 19) cleaned.substring(0, 19) else cleaned
                    try {
                        LocalDateTime.parse(base)
                    } catch (_: Exception) {
                        LocalDateTime.parse(base, TIME_FMT)
                    }
                }

                s.length >= 10 && s[4] == '-' -> LocalDateTime.parse(
                    "$s 00:00:00".take(19),
                    TIME_FMT,
                )

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 小工具：内部把解析结果装着，省得反复判空。 */
    private class YamlMap {
        private val data = HashMap<String, Any>()

        fun put(key: String, value: Any) {
            data[key] = value
        }

        fun str(key: String): String = when (val v = data[key]) {
            is String -> v
            null -> ""
            else -> v.toString()
        }

        @Suppress("UNCHECKED_CAST")
        fun list(key: String): List<String> = when (val v = data[key]) {
            is List<*> -> (v as List<Any?>).mapNotNull { it?.toString() }.filter { it.isNotBlank() }
            is String -> if (v.isBlank()) emptyList() else listOf(v)
            else -> emptyList()
        }

        fun bool(key: String, def: Boolean): Boolean = when (val v = data[key]) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> def
            }

            else -> def
        }
    }
}
