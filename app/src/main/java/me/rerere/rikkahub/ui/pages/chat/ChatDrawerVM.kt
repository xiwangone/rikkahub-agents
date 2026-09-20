package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val assistantIdFlow =
        settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()

    // 当前选中的文件夹筛选，null 表示「未归类」视图
    private val _selectedFolderId = MutableStateFlow<Uuid?>(null)
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()

    // 当前助手的文件夹列表（Room Flow，增删改自动刷新）
    val folders: StateFlow<List<Folder>> =
        assistantIdFlow
            .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 子代理运行分组是否展开（默认折叠）
    private val _subAgentExpanded = MutableStateFlow(false)
    val subAgentExpanded: StateFlow<Boolean> = _subAgentExpanded.asStateFlow()

    // 已折叠的日期分组（key = LocalDate.toString()）
    private val _collapsedDates = MutableStateFlow<Set<String>>(emptySet())
    val collapsedDates: StateFlow<Set<String>> = _collapsedDates.asStateFlow()

    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(
            assistantIdFlow,
            _selectedFolderId,
            _subAgentExpanded,
            _collapsedDates,
        ) { assistantId, folderId, subAgentExpanded, collapsedDates ->
            ConversationListQuery(
                assistantId = assistantId,
                folderId = folderId,
                subAgentExpanded = subAgentExpanded,
                collapsedDates = collapsedDates,
            )
        }.flatMapLatest { query ->
            val pagingSource =
                if (query.folderId == null) {
                    conversationRepo.getUnfiledConversationsOfAssistantPaging(query.assistantId)
                } else {
                    conversationRepo.getConversationsOfFolderPaging(query.folderId)
                }

            pagingSource.map { pagingData ->
                // ⚠ 顺序不可调换：insertSeparators 只能从「传入的项」推导分组标题。
                // 若先按折叠态过滤，被折叠分组内无存活项 → 标题不生成 → 整组连标题一起消失，
                // 且没有可点击的标题（折叠不可逆）。故先生成标题，再过滤。
                pagingData
                    .map { ConversationListItem.Item(it) }
                    .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                        when {
                            // 跨越「子代理运行 / 普通会话」边界时插入子代理分组标题
                            before is ConversationListItem.Item &&
                                after is ConversationListItem.Item &&
                                before.conversation.isSubAgentRun != after.conversation.isSubAgentRun -> {
                                ConversationListItem.SubAgentHeader
                            }

                            // 子代理运行分组位于列表最前时也要有标题
                            before == null &&
                                after is ConversationListItem.Item &&
                                after.conversation.isSubAgentRun -> {
                                ConversationListItem.SubAgentHeader
                            }

                            before == null && after is ConversationListItem.Item -> {
                                if (after.conversation.isPinned) {
                                    ConversationListItem.PinnedHeader
                                } else {
                                    dateHeaderOf(after.conversation)
                                }
                            }

                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    dateHeaderOf(after.conversation)
                                } else if (!after.conversation.isPinned) {
                                    val beforeDate =
                                        before.conversation.localDate()
                                    val afterDate =
                                        after.conversation.localDate()

                                    if (beforeDate != afterDate) {
                                        dateHeaderOf(after.conversation)
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            else -> {
                                null
                            }
                        }
                    }
                    // 折叠过滤放在标题生成之后：分组标题恒保留（可再次点击展开），只滤掉组内会话。
                    .filter { item ->
                        isConversationListItemVisible(
                            item = item,
                            subAgentExpanded = query.subAgentExpanded,
                            collapsedDates = query.collapsedDates,
                        )
                    }
            }
        }.cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    init {
        // 助手切换时重置文件夹筛选，回到「聊天」视图，
        // 避免继续显示上一个助手文件夹内的会话（文件夹是助手内分组）
        viewModelScope.launch {
            assistantIdFlow.collect {
                _selectedFolderId.value = null
            }
        }
    }

    fun saveScrollPosition(
        index: Int,
        offset: Int,
    ) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    fun selectFolder(folderId: Uuid?) {
        _selectedFolderId.value = folderId
    }

    fun toggleSubAgentExpanded() {
        _subAgentExpanded.value = !_subAgentExpanded.value
    }

    fun toggleDateCollapsed(key: String) {
        _collapsedDates.value =
            if (key in _collapsedDates.value) {
                _collapsedDates.value - key
            } else {
                _collapsedDates.value + key
            }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            folderRepo.createFolder(assistantId, trimmed)
        }
    }

    fun renameFolder(
        folderId: Uuid,
        name: String,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
        }
    }

    /**
     * 删除文件夹。若文件夹内有正在生成回复的会话，拒绝删除并返回 false（UI 层据此提示用户）。
     */
    fun deleteFolder(folderId: Uuid): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return false
        }
        viewModelScope.launch {
            // 经 ChatService 删除：会同步清空活跃 session 内存态的 folderId，避免整对象保存写回已删文件夹
            chatService.deleteFolder(folderId)
            if (_selectedFolderId.value == folderId) {
                _selectedFolderId.value = null
            }
        }
        return true
    }

    fun moveConversationToFolder(
        conversationId: Uuid,
        folderId: Uuid?,
    ) {
        viewModelScope.launch {
            // 经 ChatService 移动：活跃会话会先同步内存态，避免后续整对象保存覆盖 folder_id
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

    fun renameConversation(conversation: Conversation, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            chatService.saveConversation(
                conversation.id,
                conversation.copy(title = trimmed),
            )
        }
    }

    fun regenerateTitle(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateTitle(conversation.id, conversation, force = true)
        }
    }

    /**
     * 删除所有"子代理会话"（会话列表里被折叠的那些），但跳过 [exclude] 中正在生成的会话。
     * @return 实际删除的数量
     */
    suspend fun cleanSubAgentRuns(exclude: Set<Uuid>): Int {
        val assistantId = assistantIdFlow.first()
        val targets =
            conversationRepo
                .getConversationsOfAssistant(assistantId)
                .first()
                .filter { it.isSubAgentRun && it.id !in exclude }
        targets.forEach { conversationRepo.deleteConversation(it) }
        return targets.size
    }

    /** 会话所属日期的分组标题（折叠 key 与标签均取自该日期）。 */
    private fun dateHeaderOf(conversation: Conversation): ConversationListItem.DateHeader {
        val date = conversation.localDate()
        return ConversationListItem.DateHeader(date = date, label = getDateLabel(date))
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}

/** 会话列表分页源所需的状态（助手 / 文件夹筛选 + 折叠态）。 */
private data class ConversationListQuery(
    val assistantId: Uuid,
    val folderId: Uuid?,
    val subAgentExpanded: Boolean,
    val collapsedDates: Set<String>,
)

/**
 * 折叠态判定：**分组标题（DateHeader / PinnedHeader / SubAgentHeader）恒保留**，
 * 只过滤被折叠分组内的会话。
 *
 * 标题必须保留，否则折叠后无可点击目标 → 折叠不可逆、会话被永久隐藏。
 * 置顶会话（isPinned）不受日期折叠影响：它的分组标题是 PinnedHeader（不可点击），
 * 若跟着一起隐藏就同样无法恢复。
 */
internal fun isConversationListItemVisible(
    item: ConversationListItem,
    subAgentExpanded: Boolean,
    collapsedDates: Set<String>,
): Boolean =
    when (item) {
        is ConversationListItem.Item -> {
            val conversation = item.conversation
            when {
                conversation.isSubAgentRun -> subAgentExpanded
                conversation.isPinned -> true
                else -> conversation.localDate().toString() !in collapsedDates
            }
        }
        else -> true
    }

/** 会话所属的本地日期（列表分组与折叠 key 都用它）。 */
private fun Conversation.localDate(): LocalDate = updateAt.atZone(ZoneId.systemDefault()).toLocalDate()
