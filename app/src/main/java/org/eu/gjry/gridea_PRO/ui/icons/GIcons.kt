package org.eu.gjry.gridea_PRO.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 手绘的一套线性图标。
 *
 * Material 官方图标集里没有 文件夹 / 同步 / 标签 / 时钟 / 上传 / 下载 / 置顶，
 * 与其为这七个图标拉一个几 MB 的图标库，不如自己按 24x24 画一遍——
 * 顺便统一了线宽和圆角，整屏看起来是一套东西。
 */
object GIcons {

    private const val LINE = 1.9f

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    /** 统一的描边样式：圆头圆角，看起来比默认的方头柔和。 */
    private fun ImageVector.Builder.stroke(block: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = LINE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    // ── PathBuilder 小工具 ──────────────────────────────────────

    private fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }

    private fun PathBuilder.polyline(vararg pts: Float) {
        if (pts.size < 4) return
        moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            lineTo(pts[i], pts[i + 1])
            i += 2
        }
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx, cy - r)
        arcTo(r, r, 0f, false, true, cx, cy + r)
        arcTo(r, r, 0f, false, true, cx, cy - r)
        close()
    }

    // ── 底部导航三件套 ──────────────────────────────────────────

    /** 文章：一页纸，加三条正文线。 */
    val Article: ImageVector by lazy {
        icon("Article") {
            stroke {
                // 纸张外框
                moveTo(7.5f, 3f)
                lineTo(16.5f, 3f)
                lineTo(20f, 6.5f)
                lineTo(20f, 21f)
                lineTo(4f, 21f)
                lineTo(4f, 3f)
                close()
                // 折角
                polyline(16.5f, 3f, 16.5f, 6.5f, 20f, 6.5f)
                // 正文
                line(7.5f, 11f, 16.5f, 11f)
                line(7.5f, 14.5f, 16.5f, 14.5f)
                line(7.5f, 18f, 13f, 18f)
            }
        }
    }

    /** 闪念：对话气泡 + 一道闪电。 */
    val Memo: ImageVector by lazy {
        icon("Memo") {
            stroke {
                moveTo(4f, 7.5f)
                quadTo(4f, 4f, 7.5f, 4f)
                lineTo(16.5f, 4f)
                quadTo(20f, 4f, 20f, 7.5f)
                lineTo(20f, 13.5f)
                quadTo(20f, 17f, 16.5f, 17f)
                lineTo(11.5f, 17f)
                lineTo(7f, 20.5f)
                lineTo(7f, 17f)
                quadTo(4f, 17f, 4f, 13.5f)
                close()
                // 闪电
                polyline(13.6f, 6.8f, 10.4f, 11.2f, 12.8f, 11.2f, 11f, 15.2f)
            }
        }
    }

    /** 同步：两条方向相反的箭头。 */
    val Sync: ImageVector by lazy {
        icon("Sync") {
            stroke {
                line(4f, 8.5f, 17.5f, 8.5f)
                polyline(14f, 5f, 17.5f, 8.5f, 14f, 12f)
                line(20f, 15.5f, 6.5f, 15.5f)
                polyline(10f, 12f, 6.5f, 15.5f, 10f, 19f)
            }
        }
    }

    // ── 功能图标 ────────────────────────────────────────────────

    /** 文件夹：首页右上角「更改文件夹」。 */
    val Folder: ImageVector by lazy {
        icon("Folder") {
            stroke {
                moveTo(3f, 9f)
                quadTo(3f, 5.8f, 5.4f, 5.8f)
                lineTo(9f, 5.8f)
                lineTo(11.4f, 8.6f)
                lineTo(18.6f, 8.6f)
                quadTo(21f, 8.6f, 21f, 11.2f)
                lineTo(21f, 16.6f)
                quadTo(21f, 19f, 18.6f, 19f)
                lineTo(5.4f, 19f)
                quadTo(3f, 19f, 3f, 16.6f)
                close()
            }
        }
    }

    /** 标签：吊牌 + 穿孔。 */
    val Tag: ImageVector by lazy {
        icon("Tag") {
            stroke {
                moveTo(3.4f, 12.6f)
                lineTo(12.6f, 3.4f)
                lineTo(20.6f, 3.4f)
                lineTo(20.6f, 11.4f)
                lineTo(11.4f, 20.6f)
                close()
                circle(17f, 7f, 1.5f)
            }
        }
    }

    /** 时钟：发布时间。 */
    val Clock: ImageVector by lazy {
        icon("Clock") {
            stroke {
                circle(12f, 12f, 8.2f)
                polyline(12f, 12f, 12f, 7f)
                polyline(12f, 12f, 16.2f, 13.8f)
            }
        }
    }

    /** 分类：文件夹套个小方块。 */
    val Category: ImageVector by lazy {
        icon("Category") {
            stroke {
                moveTo(3.5f, 5.5f)
                lineTo(9.5f, 5.5f)
                lineTo(11f, 7.5f)
                lineTo(15f, 7.5f)
                lineTo(15f, 11f)
                moveTo(3.5f, 5.5f)
                lineTo(3.5f, 18.5f)
                lineTo(11f, 18.5f)
                moveTo(12.5f, 12.5f)
                lineTo(20.5f, 12.5f)
                lineTo(20.5f, 20.5f)
                lineTo(12.5f, 20.5f)
                close()
            }
        }
    }

    val Upload: ImageVector by lazy {
        icon("Upload") {
            stroke {
                line(12f, 16.5f, 12f, 4.5f)
                polyline(7.5f, 9f, 12f, 4.5f, 16.5f, 9f)
                polyline(4f, 15f, 4f, 19.5f, 20f, 19.5f, 20f, 15f)
            }
        }
    }

    val Download: ImageVector by lazy {
        icon("Download") {
            stroke {
                line(12f, 4.5f, 12f, 16.5f)
                polyline(7.5f, 12f, 12f, 16.5f, 16.5f, 12f)
                polyline(4f, 15f, 4f, 19.5f, 20f, 19.5f, 20f, 15f)
            }
        }
    }

    /** 置顶：钉子挂到横杆上。 */
    val Pin: ImageVector by lazy {
        icon("Pin") {
            stroke {
                line(4f, 4.2f, 20f, 4.2f)
                line(12f, 9f, 12f, 20.5f)
                polyline(8f, 13f, 12f, 9f, 16f, 13f)
            }
        }
    }

    /** 隐藏：一只被划掉的眼镜。 */
    val EyeOff: ImageVector by lazy {
        icon("EyeOff") {
            stroke {
                moveTo(2.6f, 12f)
                quadTo(12f, 3.8f, 21.4f, 12f)
                quadTo(12f, 20.2f, 2.6f, 12f)
                close()
                circle(12f, 12f, 2.9f)
                line(5f, 19.4f, 19f, 4.6f)
            }
        }
    }

    /** 预览：同一只眼镜，不划。 */
    val Eye: ImageVector by lazy {
        icon("Eye") {
            stroke {
                moveTo(2.6f, 12f)
                quadTo(12f, 3.8f, 21.4f, 12f)
                quadTo(12f, 20.2f, 2.6f, 12f)
                close()
                circle(12f, 12f, 2.9f)
            }
        }
    }

    /** 预览开关用的「编辑态」图标。 */
    val Pencil: ImageVector by lazy {
        icon("Pencil") {
            stroke {
                moveTo(4f, 20.5f)
                lineTo(4f, 16.6f)
                lineTo(15.8f, 4.8f)
                lineTo(19.6f, 8.6f)
                lineTo(7.8f, 20.4f)
                close()
                polyline(13.4f, 7.2f, 17.2f, 11f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        icon("Trash") {
            stroke {
                line(4f, 6.8f, 20f, 6.8f)
                polyline(9.5f, 6.8f, 9.5f, 4.2f, 14.5f, 4.2f, 14.5f, 6.8f)
                polyline(6.2f, 6.8f, 7.2f, 20.2f, 16.8f, 20.2f, 17.8f, 6.8f)
                line(10.2f, 10.6f, 10.6f, 17f)
                line(13.8f, 10.6f, 13.4f, 17f)
            }
        }
    }

    /** 返回：一个左尖角。 */
    val Back: ImageVector by lazy {
        icon("Back") {
            stroke {
                polyline(15f, 4.5f, 7.5f, 12f, 15f, 19.5f)
            }
        }
    }

    /** 关闭：叉。 */
    val Close: ImageVector by lazy {
        icon("Close") {
            stroke {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
        }
    }

    /** 对勾。 */
    val Check: ImageVector by lazy {
        icon("Check") {
            stroke {
                polyline(5f, 12.6f, 10f, 17.6f, 19f, 6.6f)
            }
        }
    }

    /** 云/服务器：同步页用。 */
    val Cloud: ImageVector by lazy {
        icon("Cloud") {
            stroke {
                moveTo(7.2f, 18.5f)
                quadTo(3.5f, 18.5f, 3.5f, 15.4f)
                quadTo(3.5f, 12.8f, 6.2f, 12.2f)
                quadTo(6.8f, 8f, 11.2f, 8f)
                quadTo(15.2f, 8f, 16.2f, 11.4f)
                quadTo(20.5f, 11.6f, 20.5f, 15.1f)
                quadTo(20.5f, 18.5f, 16.8f, 18.5f)
                close()
            }
        }
    }
}
