package me.rerere.rikkahub.ui.pages.mediacreation

import kotlin.uuid.Uuid

/** null 表示未进入多选；空集合表示仍在多选中但当前没有选中项。 */
internal object MediaCreationNodeSelection {
    fun enter(current: Set<Uuid>?, nodeId: Uuid?): Set<Uuid> =
        (current ?: emptySet()) + listOfNotNull(nodeId)

    fun exit(): Set<Uuid>? = null

    fun toggle(current: Set<Uuid>?, nodeId: Uuid): Set<Uuid>? =
        current?.let { if (nodeId in it) it - nodeId else it + nodeId }

    fun selectAll(current: Set<Uuid>?, allNodeIds: Set<Uuid>): Set<Uuid>? =
        current?.let { allNodeIds }

    fun clear(current: Set<Uuid>?): Set<Uuid>? =
        current?.let { emptySet() }
}
