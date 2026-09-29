package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R

/**
 * 提问卡的中性模型 —— 本地工具（`ask_user` 工具参数）与接入（服务端提问注解）两种来源
 * 都归一到它；渲染层只认这个模型，不关心问题从哪来。
 */
internal data class AskPromptQuestion(
    val id: String,
    val question: String,
    val options: List<String> = emptyList(),
    /** text | single | multi */
    val selectionType: String = "text",
)

/** 提问卡的交互阶段：由各来源决定，渲染层据此切换可交互 / 只读。 */
internal enum class AskPromptStatus {
    Pending,
    Submitting,
    Answered,
    Stale,
}

/** 作答提交方式：显式提交（选完点提交）或点选即答。 */
internal enum class AskPromptSubmitMode { Explicit, Immediate }

/**
 * 提问卡的渲染层（**与来源解耦**）：只负责「问题 + 选项 + 作答输入 + 提交」。
 *
 * - 外壳（折叠链式步骤 / 卡片容器）、图标与标题由各调用方自理；
 * - 作答以 [AskPromptQuestion.id] 为键回传，格式转换由调用方处理
 *   （本地工具转 `{"answers": …}` JSON，接入转纯文本答案）；
 * - 已答态下，单选项按 [answeredLabels] 回显选中。
 *
 * @param answeredLabels 只读展示态下各问题要显示的用户答案。
 * @param statusText 追加在卡片底部的状态文案（已提交 / 已失效等）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskPromptCard(
    questions: List<AskPromptQuestion>,
    status: AskPromptStatus,
    modifier: Modifier = Modifier,
    answeredLabels: Map<String, String> = emptyMap(),
    statusText: String? = null,
    submitMode: AskPromptSubmitMode = AskPromptSubmitMode.Explicit,
    onAnswers: ((answers: Map<String, String>) -> Unit)? = null,
) {
    val answers = remember { mutableStateMapOf<String, String>() }
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }
    val interactive = status == AskPromptStatus.Pending && onAnswers != null

    fun collect(): Map<String, String> =
        questions.associate { q ->
            q.id to
                when (q.selectionType) {
                    "multi" ->
                        (
                            multiAnswers[q.id].orEmpty().toList() +
                                listOfNotNull(answers[q.id]?.takeIf { it.isNotBlank() })
                        ).joinToString(", ")
                    else -> answers[q.id] ?: ""
                }
        }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        questions.forEach { q ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = q.question,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (q.options.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        q.options.forEach { option ->
                            val selectedOptions = multiAnswers[q.id] ?: emptySet()
                            val selected =
                                if (q.selectionType == "multi") {
                                    option in selectedOptions
                                } else {
                                    (answers[q.id] ?: answeredLabels[q.id]) == option
                                }
                            FilterChip(
                                selected = selected,
                                enabled = interactive,
                                onClick = {
                                    if (q.selectionType == "multi") {
                                        multiAnswers[q.id] =
                                            if (option in selectedOptions) selectedOptions - option
                                            else selectedOptions + option
                                    } else {
                                        answers[q.id] = option
                                    }
                                    if (submitMode == AskPromptSubmitMode.Immediate &&
                                        q.selectionType != "multi"
                                    ) {
                                        onAnswers?.invoke(collect())
                                    }
                                },
                                label = {
                                    Text(option, style = MaterialTheme.typography.labelSmall)
                                },
                            )
                        }
                    }
                } else {
                    when {
                        interactive ->
                            OutlinedTextField(
                                value = answers[q.id] ?: "",
                                onValueChange = { answers[q.id] = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                            )
                        status == AskPromptStatus.Answered -> {
                            val label = answeredLabels[q.id].orEmpty()
                            if (label.isNotBlank()) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (interactive && submitMode == AskPromptSubmitMode.Explicit) {
            FilledTonalButton(
                onClick = { onAnswers?.invoke(collect()) },
                enabled =
                    questions.isNotEmpty() &&
                        questions.all { q ->
                            when (q.selectionType) {
                                "multi" ->
                                    !multiAnswers[q.id].isNullOrEmpty() ||
                                        !answers[q.id].isNullOrBlank()
                                else -> !answers[q.id].isNullOrBlank()
                            }
                        },
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(
                    imageVector = HugeIcons.Tick01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.chat_message_tool_submit),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        statusText?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 把作答汇总成本地 `ask_user` 工具要求的 `{"answers": {问题 id → 答案}}` JSON。 */
internal fun buildAskAnswersJson(answers: Map<String, String>): String =
    buildJsonObject {
        put(
            "answers",
            buildJsonObject {
                answers.forEach { (id, value) -> put(id, JsonPrimitive(value)) }
            },
        )
    }.toString()
