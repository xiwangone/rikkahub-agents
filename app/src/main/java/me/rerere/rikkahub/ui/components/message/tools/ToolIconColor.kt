package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.theme.extendColors

/**
 * 工具图标家族色组：语义键，不直接是颜色值。
 *
 * 图标本身是 HugeIcons 的单色线稿，颜色来自渲染时的 tint（见 [ToolUIRenderer.iconColor]）。
 * 色组只做“哪个家族用哪种颜色”的决策，档位解析集中在 [ToolIconColor.resolve]，避免
 * 110 个渲染器各自写色值。单元测试直接断言 [ToolUIRenderer.iconColorKey] 返回的色组，
 * 不需要跑 Compose。
 */
enum class ToolIconColor {
    /** 通讯类：Telegram 全家 + 消息/邮件/短信/通知 */
    BLUE,

    /** 文件类：文件操作全家 + 工作区读写 + 文件列表 */
    GREEN,

    /** 终端与智能类：shell/ssh/termux + 技能/记忆/子代理 */
    PURPLE,

    /** 动作类：手势全家 + 启动/输入 */
    ORANGE,

    /** 时间与数据类：定时任务/作业轮询/日历/报表 */
    TEAL,

    /** 媒体类：拍照/截图/语音/播放 */
    PINK,

    /** 系统类：设置/诊断/密钥库/设备控制 */
    GRAY,

    /** 危险操作：删除/停用/结束，跨家族覆盖 */
    RED,

    /** 未指定家族：保持旧外观（内容色 70% 透明）。 */
    DEFAULT,
}

/**
 * 色组 → 实际 tint：图标取 ExtendColors 的 6 档正色（与仓内 green6/red6 状态色惯例一致），
 * 灰组用 8 档（6 档 #949494 在浅色底上太淡）。DEFAULT 回退旧外观。
 *
 * 6 档在深浅两套色板里都是“正色”，同一档位不用分主题写两套逻辑。
 */
@Composable
fun ToolIconColor.resolve(): Color =
    when (this) {
        ToolIconColor.BLUE -> MaterialTheme.extendColors.blue6
        ToolIconColor.GREEN -> MaterialTheme.extendColors.green6
        ToolIconColor.PURPLE -> MaterialTheme.extendColors.purple6
        ToolIconColor.ORANGE -> MaterialTheme.extendColors.orange6
        ToolIconColor.TEAL -> MaterialTheme.extendColors.teal6
        ToolIconColor.PINK -> MaterialTheme.extendColors.pink6
        ToolIconColor.GRAY -> MaterialTheme.extendColors.gray8
        ToolIconColor.RED -> MaterialTheme.extendColors.red6
        ToolIconColor.DEFAULT -> LocalContentColor.current.copy(alpha = 0.7f)
    }
