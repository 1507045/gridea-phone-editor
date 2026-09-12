package org.eu.gjry.gridea_PRO.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrange
import org.eu.gjry.gridea_PRO.ui.theme.BrandOrangeLine
import org.eu.gjry.gridea_PRO.ui.theme.Ink
import org.eu.gjry.gridea_PRO.ui.theme.InkFaint
import org.eu.gjry.gridea_PRO.ui.theme.InkSoft
import org.eu.gjry.gridea_PRO.ui.theme.LineSoft
import org.eu.gjry.gridea_PRO.ui.theme.PaperSunken

/**
 * 自己写的 Markdown 预览器。
 *
 * 覆盖博客写作里真正会用到的部分：标题、段落、列表、引用、代码块、分割线、
 * 粗体/斜体/行内代码/链接/图片/删除线，以及 Gridea 用的 `<!-- more -->`。
 * 表格和 HTML 走降级处理——预览的目的是「看清楚结构」，不是像素级还原主题渲染。
 */
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val depth: Int, val text: String) : MdBlock
    data class Ordered(val marker: String, val depth: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data object Divider : MdBlock
}

private val HEADING_RE = Regex("^#{1,6}\\s+.*")
private val DIVIDER_RE = Regex("^([-*_])\\1{2,}$")
private val BULLET_RE = Regex("^[-*+]\\s+.*")
private val ORDERED_RE = Regex("^\\d+[.)]\\s+.*")

private fun parseBlocks(md: String): List<MdBlock> {
    val lines = md.replace("\r\n", "\n").split('\n')
    val blocks = ArrayList<MdBlock>()
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) blocks += MdBlock.Paragraph(para.toString().trim())
        para.setLength(0)
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trim()
        when {
            // 代码块：原样保留，不做任何行内解析
            line.startsWith("```") -> {
                flushPara()
                val lang = line.removePrefix("```").trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                blocks += MdBlock.Code(lang, body.toString().trimEnd('\n'))
            }

            line.isEmpty() -> flushPara()

            HEADING_RE.matches(line) -> {
                flushPara()
                val level = line.takeWhile { it == '#' }.length
                blocks += MdBlock.Heading(level, line.drop(level).trim())
            }

            DIVIDER_RE.matches(line) -> {
                flushPara()
                blocks += MdBlock.Divider
            }

            line.startsWith(">") -> {
                flushPara()
                blocks += MdBlock.Quote(line.removePrefix(">").trim())
            }

            BULLET_RE.matches(line) -> {
                flushPara()
                blocks += MdBlock.Bullet(indentOf(raw), line.drop(2).trim())
            }

            ORDERED_RE.matches(line) -> {
                flushPara()
                val marker = line.takeWhile { it.isDigit() || it == '.' || it == ')' }
                blocks += MdBlock.Ordered(marker, indentOf(raw), line.drop(marker.length).trim())
            }

            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flushPara()
    return blocks
}

private fun indentOf(raw: String): Int {
    val spaces = raw.length - raw.trimStart().length
    return (spaces / 2).coerceIn(0, 4)
}

// ───────────────────────────── 行内解析 ─────────────────────────────

private fun appendInline(
    b: AnnotatedString.Builder,
    s: String,
    style: SpanStyle,
    linkColor: Color,
    codeBg: Color,
) {
    b.pushStyle(style)
    var i = 0
    while (i < s.length) {
        // HTML 注释直接吞掉（Gridea 的 <!-- more --> 就走这条路）
        if (s.startsWith("<!--", i)) {
            val end = s.indexOf("-->", i)
            i = if (end < 0) s.length else end + 3
            continue
        }

        // 图片：预览里不加载网络图，标出位置就够了
        if (s.startsWith("![", i)) {
            val close = s.indexOf(']', i + 2)
            if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                val paren = s.indexOf(')', close + 2)
                if (paren > 0) {
                    val alt = s.substring(i + 2, close)
                    b.pushStyle(SpanStyle(color = InkFaint, fontStyle = FontStyle.Italic))
                    b.append(if (alt.isBlank()) "〔图片〕" else "〔图片：$alt〕")
                    b.pop()
                    i = paren + 1
                    continue
                }
            }
        }

        // 链接
        if (s[i] == '[') {
            val close = s.indexOf(']', i + 1)
            if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                val paren = s.indexOf(')', close + 2)
                if (paren > 0) {
                    b.pushStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    )
                    b.append(s.substring(i + 1, close))
                    b.pop()
                    i = paren + 1
                    continue
                }
            }
        }

        // 行内代码
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > 0) {
                b.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        color = Ink,
                        fontSize = 13.5.sp,
                    ),
                )
                b.append(s.substring(i + 1, end))
                b.pop()
                i = end + 1
                continue
            }
        }

        // 删除线
        if (s.startsWith("~~", i)) {
            val end = s.indexOf("~~", i + 2)
            if (end > 0) {
                appendInline(
                    b,
                    s.substring(i + 2, end),
                    style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough, color = InkFaint)),
                    linkColor,
                    codeBg,
                )
                i = end + 2
                continue
            }
        }

        // 粗体
        val boldDelim = when {
            s.startsWith("**", i) -> "**"
            s.startsWith("__", i) -> "__"
            else -> null
        }
        if (boldDelim != null) {
            val end = s.indexOf(boldDelim, i + 2)
            if (end > 0) {
                appendInline(
                    b,
                    s.substring(i + 2, end),
                    style.merge(SpanStyle(fontWeight = FontWeight.Bold)),
                    linkColor,
                    codeBg,
                )
                i = end + 2
                continue
            }
        }

        // 斜体
        if (s[i] == '*' || s[i] == '_') {
            val end = s.indexOf(s[i], i + 1)
            if (end > 0) {
                appendInline(
                    b,
                    s.substring(i + 1, end),
                    style.merge(SpanStyle(fontStyle = FontStyle.Italic)),
                    linkColor,
                    codeBg,
                )
                i = end + 1
                continue
            }
        }

        b.append(s[i])
        i++
    }
    b.pop()
}

@Composable
private fun renderInline(text: String, linkColor: Color, codeBg: Color): AnnotatedString =
    remember(text, linkColor, codeBg) {
        buildAnnotatedString {
            appendInline(this, text, SpanStyle(), linkColor, codeBg)
        }
    }

// ───────────────────────────── 渲染 ─────────────────────────────

@Composable
fun MarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val linkColor = BrandOrange
    val codeBg = PaperSunken

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (markdown.isBlank()) {
            Text("（还没有内容）", color = InkFaint, fontSize = 14.sp)
            return@Column
        }
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val (size, weight) = when (block.level) {
                        1 -> 23.sp to FontWeight.Bold
                        2 -> 20.sp to FontWeight.Bold
                        3 -> 17.sp to FontWeight.Bold
                        else -> 15.sp to FontWeight.SemiBold
                    }
                    Column {
                        if (block.level <= 2) {
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            text = renderInline(block.text, linkColor, codeBg),
                            color = Ink,
                            fontSize = size,
                            fontWeight = weight,
                            lineHeight = (size.value * 1.4f).sp,
                        )
                    }
                }

                is MdBlock.Paragraph -> Text(
                    text = renderInline(block.text, linkColor, codeBg),
                    color = Ink,
                    fontSize = 15.sp,
                    lineHeight = 25.sp,
                )

                is MdBlock.Bullet -> Row(
                    modifier = Modifier.padding(start = (block.depth * 14).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 9.dp, end = 8.dp)
                            .width(5.dp)
                            .height(5.dp)
                            .background(BrandOrangeLine, RoundedCornerShape(3.dp)),
                    )
                    Text(
                        text = renderInline(block.text, linkColor, codeBg),
                        color = Ink,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                    )
                }

                is MdBlock.Ordered -> Row(
                    modifier = Modifier.padding(start = (block.depth * 14).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = block.marker,
                        color = BrandOrange,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 1.dp, end = 7.dp),
                    )
                    Text(
                        text = renderInline(block.text, linkColor, codeBg),
                        color = Ink,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                    )
                }

                is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(22.dp)
                            .background(BrandOrange, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = renderInline(block.text, linkColor, codeBg),
                        color = InkSoft,
                        fontSize = 14.sp,
                        lineHeight = 23.sp,
                        fontStyle = FontStyle.Italic,
                    )
                }

                is MdBlock.Code -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = PaperSunken,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (block.lang.isNotBlank()) {
                            Text(
                                text = block.lang,
                                color = InkFaint,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Text(
                            text = block.code,
                            color = Ink,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }

                MdBlock.Divider -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LineSoft),
                )
            }
        }
    }
}

/** 供外部复用的行内渲染（闪念正文里高亮 #标签 用同一套思路）。 */
fun highlightMemoTags(
    content: String,
    tagColor: Color,
    baseColor: Color,
): AnnotatedString = buildAnnotatedString {
    pushStyle(SpanStyle(color = baseColor))
    val re = Regex("#([\\p{L}\\p{N}_]+)")
    var last = 0
    for (m in re.findAll(content)) {
        if (m.range.first > last) {
            append(content.substring(last, m.range.first))
        }
        pushStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold))
        append(m.value)
        pop()
        last = m.range.last + 1
    }
    if (last < content.length) append(content.substring(last))
    pop()
}

/** 预览用的文字样式，编辑器里保持一致，切来切去不会有跳变。 */
val PreviewTextStyle = TextStyle(fontSize = 15.sp, lineHeight = 25.sp, color = Ink)
