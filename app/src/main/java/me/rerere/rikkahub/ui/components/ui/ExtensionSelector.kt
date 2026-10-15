package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Sparkles
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ai.LorebooksContent
import me.rerere.rikkahub.ui.components.ai.ModeInjectionsContent
import me.rerere.rikkahub.ui.components.ai.QuickMessagesContent
import me.rerere.rikkahub.ui.components.ai.SkillsContent
import org.koin.compose.koinInject

@Composable
fun ExtensionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    conversation: Conversation? = null,
    onUpdateConversation: ((Conversation) -> Unit)? = null,
    onNavigateToQuickMessages: () -> Unit = {},
    onNavigateToPrompts: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
) {
    val skillManager: SkillManager = koinInject()
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }

    LaunchedEffect(Unit) {
        // 打开扩展面板时清理运行时被删除的技能（残留的 enabledSkills 引用），
        // prune 顺带返回现存技能列表，避免重复读盘
        skills = skillManager.pruneOrphanedEnabledSkills()
    }

    val useConversationInjections =
        assistant.allowConversationPromptInjection && conversation != null && onUpdateConversation != null
    val selectedModeInjectionIds =
        if (useConversationInjections) {
            conversation.modeInjectionIds
        } else {
            assistant.modeInjectionIds
        }
    val selectedLorebookIds =
        if (useConversationInjections) {
            conversation.lorebookIds
        } else {
            assistant.lorebookIds
        }

    val pagerState = rememberPagerState { 4 }

    Column(
        modifier = modifier,
    ) {
        val extTabs = listOf(
            object : PagerTab {
                override val icon: ImageVector = HugeIcons.Message01
                override val label: String
                    @Composable get() = stringResource(R.string.extension_selector_tab_quick_messages)
            },
            object : PagerTab {
                override val icon: ImageVector = HugeIcons.MagicWand01
                override val label: String
                    @Composable get() = stringResource(R.string.extension_selector_tab_mode_injections)
            },
            object : PagerTab {
                override val icon: ImageVector = HugeIcons.BookOpen01
                override val label: String
                    @Composable get() = stringResource(R.string.extension_selector_tab_lorebooks)
            },
            object : PagerTab {
                override val icon: ImageVector = HugeIcons.Sparkles
                override val label: String
                    @Composable get() = stringResource(R.string.extension_selector_tab_skills)
            },
        )
        PagerTabs(
            pagerState = pagerState,
            tabs = extTabs,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) { page ->
            when (page) {
                0 -> {
                    if (settings.quickMessages.isNotEmpty()) {
                        QuickMessagesContent(
                            quickMessages = settings.quickMessages,
                            selectedIds = assistant.quickMessageIds,
                            onToggle = { id, checked ->
                                val newIds =
                                    if (checked) {
                                        assistant.quickMessageIds + id
                                    } else {
                                        assistant.quickMessageIds - id
                                    }
                                onUpdate(assistant.copy(quickMessageIds = newIds))
                            },
                            onManage = onNavigateToQuickMessages,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_quick_messages_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToQuickMessages,
                        )
                    }
                }

                1 -> {
                    if (settings.modeInjections.isNotEmpty()) {
                        ModeInjectionsContent(
                            modeInjections = settings.modeInjections,
                            selectedIds = selectedModeInjectionIds,
                            onToggle = { id, checked ->
                                val newIds =
                                    if (checked) {
                                        selectedModeInjectionIds + id
                                    } else {
                                        selectedModeInjectionIds - id
                                    }
                                if (useConversationInjections) {
                                    onUpdateConversation(conversation.copy(modeInjectionIds = newIds))
                                } else {
                                    onUpdate(assistant.copy(modeInjectionIds = newIds))
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_mode_injections_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                        )
                    }
                }

                2 -> {
                    if (settings.lorebooks.isNotEmpty()) {
                        LorebooksContent(
                            lorebooks = settings.lorebooks,
                            selectedIds = selectedLorebookIds,
                            onToggle = { id, checked ->
                                val newIds =
                                    if (checked) {
                                        selectedLorebookIds + id
                                    } else {
                                        selectedLorebookIds - id
                                    }
                                if (useConversationInjections) {
                                    onUpdateConversation(conversation.copy(lorebookIds = newIds))
                                } else {
                                    onUpdate(assistant.copy(lorebookIds = newIds))
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_lorebooks_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                        )
                    }
                }

                3 -> {
                    if (skills.isNotEmpty()) {
                        SkillsContent(
                            skills = skills,
                            enabledSkills = assistant.enabledSkills,
                            onToggle = { name, checked ->
                                val newSkills =
                                    if (checked) {
                                        assistant.enabledSkills + name
                                    } else {
                                        assistant.enabledSkills - name
                                    }
                                onUpdate(assistant.copy(enabledSkills = newSkills))
                            },
                            onManage = onNavigateToSkills,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_skills_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_skills),
                            onAction = onNavigateToSkills,
                        )
                    }
                }
            }
        }
    }
}
