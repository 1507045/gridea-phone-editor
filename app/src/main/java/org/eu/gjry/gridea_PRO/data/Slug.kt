package org.eu.gjry.gridea_PRO.data

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import java.text.Normalizer

/**
 * 标题 → 文件名/URL slug。
 *
 * 复刻电脑端 `backend/internal/utils/slug.go` 的 `SlugifyName`：
 *   1. 汉字逐字换成「空格 + 首读音拼音 + 空格」，非汉字原样保留
 *   2. 归一：去音标、转小写、非字母数字一律折成单个连字符、掐掉首尾连字符
 *
 * 所以「你好，世界」→ `ni-hao-shi-jie`，「hello 世界」→ `hello-shi-jie`，
 * 跟电脑端生成的文件名完全一致——同一个标题不会在两个端各生成一份文件。
 */
object Slug {

    private val FORMAT = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        // ü 输出成 v，与 go-pinyin 的 Normal 风格一致（女 → nv）
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val COMBINING = Regex("\\p{Mn}+")

    /** 由标题生成文件名（不带 .md）。结果为空时会兜底成一个随机短名。 */
    fun fromTitle(title: String): String {
        val s = slugify(title)
        return s.ifBlank { "post-" + FrontMatter.newId(6).lowercase() }
    }

    /** 原始 slugify，不做兜底。 */
    fun slugify(input: String): String {
        if (input.isBlank()) return ""
        val buf = StringBuilder(input.length * 3)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (isHan(cp)) {
                val py = pinyinOf(cp)
                if (py != null) {
                    buf.append(' ').append(py).append(' ')
                    i += charCount
                    continue
                }
            }
            buf.appendCodePoint(cp)
            i += charCount
        }
        val ascii = stripDiacritics(buf.toString()).lowercase()
        return ascii.replace(NON_ALNUM, "-").trim('-')
    }

    /** 给标签/分类生成 slug，规则与文件名一致（电脑端也是同一个函数）。 */
    fun forName(name: String): String = slugify(name)

    private fun isHan(cp: Int): Boolean = when (cp) {
        in 0x3400..0x4DBF -> true      // 扩展 A
        in 0x4E00..0x9FFF -> true      // 基本区
        in 0xF900..0xFAFF -> true      // 兼容区
        in 0x20000..0x2FA1F -> true    // 扩展 B~F
        else -> false
    }

    private fun pinyinOf(cp: Int): String? = try {
        val arr = PinyinHelper.toHanyuPinyinStringArray(cp.toChar(), FORMAT)
        arr?.firstOrNull()?.takeIf { it.isNotBlank() }?.lowercase()
    } catch (_: Exception) {
        null
    }

    private fun stripDiacritics(s: String): String = try {
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING, "")
    } catch (_: Exception) {
        s
    }
}
