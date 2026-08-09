package me.rerere.rikkahub.data.quota

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局额度快照持有者。
 *
 * 桥接「QuotaConsolePage 解析出的 snapshot」与「悬浮窗状态线/展开卡片」：
 * - QuotaConsolePage 每次 parseQuota 成功 → [addSnapshot] 更新对应 provider 的 snapshot
 * - AgentOverlay 订阅 [aggregate] 驱动状态线颜色
 *
 * 不入 DataStore（快照是易失数据，重启后需重新查询）；只存内存。
 */
object QuotaSnapshotHolder {

    /** providerId → snapshot 内存缓存。 */
    private val snapshots = mutableMapOf<String, QuotaSnapshot>()

    private val _aggregate = MutableStateFlow<QuotaAggregate?>(null)
    val aggregate: StateFlow<QuotaAggregate?> = _aggregate.asStateFlow()

    /** 更新单个 provider 的快照并重新汇总。 */
    fun addSnapshot(snapshot: QuotaSnapshot) {
        synchronized(this) {
            snapshots[snapshot.providerId] = snapshot
        }
        recompute()
    }

    /** 移除单个 provider（删除配置时）。 */
    fun removeProvider(providerId: String) {
        synchronized(this) {
            snapshots.remove(providerId)
        }
        recompute()
    }

    private fun recompute() {
        val all =
            synchronized(this) {
                snapshots.values.toList()
            }
        _aggregate.value = if (all.isEmpty()) null else aggregateQuotaStatus(all)
    }

    /** 清空（全部 provider 删除 / 手动重置）。 */
    fun clear() {
        synchronized(this) {
            snapshots.clear()
        }
        _aggregate.value = null
    }
}
