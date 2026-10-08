package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 停手多久后提交一次顺序改动
internal const val ReorderCommitDelayMs = 400L

// 仍在拖动时最多等多久就把改动提交掉（避免用户长时间按住不放手导致迟迟不落盘）
private const val MaxDragWaitMs = 3000L
private const val DragWaitStepMs = 150L

/**
 * 拖动排序的「提交时机」封装。
 *
 * 背景：直接在拖动回调里把整份列表写回设置（DataStore），每移动一格就提交一次，
 * 会触发整列表重组 + 落盘 → 拖动明显卡顿。统一做法是：
 *  1. 拖动只改**本地顺序**（外部拿 [items] 渲染，立刻可见）；
 *  2. 停手 [commitDelayMs] 后提交一次；
 *  3. 仍在拖动就先不提交（写设置会压在拖动上造成长帧）；
 *  4. 离开页面（组合销毁）时兜底提交，避免拖完立刻返回导致顺序丢失。
 *
 * 用法：
 * ```
 * val reorder = rememberReorderCommitState(
 *     items = settings.ttsProviders,
 *     key = { it.id },
 *     onCommit = { vm.updateSettings(settings.copy(ttsProviders = it)) },
 * )
 * val state = rememberReorderableLazyListState(lazyListState) { from, to ->
 *     reorder.onMove(from.key, to.key)
 * }
 * // 列表渲染 reorder.items；item 的拖动句柄回调里接 reorder.onDragStart()/onDragStop()
 * ```
 *
 * @param items 当前持久化的列表（外部来源变化时同步进本地顺序）
 * @param key 列表项稳定标识，用于在拖动回调里定位项（不要用下标：列表可能含页面前置项）
 * @param enabled 关闭时 [onMove] 不生效（如列表处于过滤态，下标与全量不一致）
 * @param onCommit 顺序确定后回写设置（只在顺序真的变化时调用）
 */
@Composable
fun <T : Any> rememberReorderCommitState(
    items: List<T>,
    key: (T) -> Any,
    enabled: Boolean = true,
    commitDelayMs: Long = ReorderCommitDelayMs,
    onCommit: (List<T>) -> Unit,
): ReorderCommitState<T> {
    val latestItems by rememberUpdatedState(items)
    val latestKey by rememberUpdatedState(key)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val latestEnabled by rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()

    // 本地顺序（拖动期间的外部来源）：初始带上当前内容，避免首帧空列表闪烁
    val localItems =
        remember {
            mutableStateListOf<T>().apply { addAll(items) }
        }
    // 内容一致就不重置：提交后外部列表回流时，清空重建会白白触发整列重组（拖动中尤其明显）
    LaunchedEffect(items) {
        if (localItems != items) {
            localItems.clear()
            localItems.addAll(items)
        }
    }

    // 下面两个标志只给回调/协程读写，故意不用 Compose state（手势回调里写 state 会触发重组）
    val dragging = remember { booleanArrayOf(false) }
    val orderDirty = remember { booleanArrayOf(false) }
    var commitJob by remember { mutableStateOf<Job?>(null) }

    val commit: () -> Unit = {
        if (orderDirty[0]) {
            orderDirty[0] = false
            val current = latestItems
            val keyOf = latestKey
            // 按本地顺序重排当前集合；期间新增/删除的项原样保留在尾部
            val byOrder =
                localItems.mapNotNull { item ->
                    current.firstOrNull { keyOf(it) == keyOf(item) }
                }
            val rest =
                current.filter { item ->
                    byOrder.none { keyOf(it) == keyOf(item) }
                }
            val reordered = byOrder + rest
            if (reordered != current) {
                latestOnCommit(reordered)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 兜底提交：拖完立刻退出页面时，下面那个延时提交协程会被取消 → 不补这一步顺序就丢了
            commitJob?.cancel()
            commit()
        }
    }

    return ReorderCommitState(
        items = localItems,
        move = { fromKey, toKey ->
            if (latestEnabled) {
                val keyOf = latestKey
                val fromIdx = localItems.indexOfFirst { keyOf(it) == fromKey }
                val toIdx = localItems.indexOfFirst { keyOf(it) == toKey }
                if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                    localItems.add(toIdx, localItems.removeAt(fromIdx))
                    orderDirty[0] = true
                    commitJob?.cancel()
                    commitJob =
                        scope.launch {
                            delay(commitDelayMs)
                            // 用户又接着拖时先不提交：写设置会触发整列重组，落在拖动里就是一次明显卡顿
                            var waitedMs = 0L
                            while (dragging[0] && waitedMs < MaxDragWaitMs) {
                                delay(DragWaitStepMs)
                                waitedMs += DragWaitStepMs
                            }
                            commit()
                        }
                }
            }
        },
        dragStart = { dragging[0] = true },
        dragStop = {
            dragging[0] = false
            if (orderDirty[0] && commitJob?.isActive != true) {
                // 拖动结束却没有在跑的延时提交 → 立即补一次，别让顺序一直挂在本地
                commitJob =
                    scope.launch {
                        commit()
                    }
            }
        },
        flush = commit,
    )
}

/**
 * [rememberReorderCommitState] 返回的句柄：列表往外拿 [items] 渲染，拖动回调转发进来。
 */
class ReorderCommitState<T : Any> internal constructor(
    /** 本地顺序（拖动期间即为最新顺序），直接用于列表渲染 */
    val items: List<T>,
    private val move: (Any?, Any?) -> Unit,
    private val dragStart: () -> Unit,
    private val dragStop: () -> Unit,
    private val flush: () -> Unit,
) {
    /** 拖动经过一格时调用；传项 key 而不是下标 */
    fun onMove(
        fromKey: Any?,
        toKey: Any?,
    ) = move(fromKey, toKey)

    /** 拖动开始（配合 onDragStop 让延时提交避开拖动过程） */
    fun onDragStart() = dragStart()

    /** 拖动结束 */
    fun onDragStop() = dragStop()

    /** 立即提交未落盘的顺序（一般无需手动调用，离开页面会自动兜底） */
    fun commitNow() = flush()
}
