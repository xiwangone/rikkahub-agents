package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRegistryTest {

    private fun makeRun(
        id: String,
        parentChat: String? = "chat-1",
        parentAssistant: String = "asst-1",
        status: SubAgentStatus = SubAgentStatus.PENDING,
        startedAtMs: Long = System.currentTimeMillis(),
    ): SubAgentRun = SubAgentRun(
        id = id,
        parentChatId = parentChat,
        parentAssistantId = parentAssistant,
        label = "label-$id",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = false,
        timeoutSeconds = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        status = status,
        startedAtMs = startedAtMs,
    )

    @Test fun `addPending puts run in flow`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a"))
        assertEquals(1, r.runs.value.size)
        assertEquals(SubAgentStatus.PENDING, r.runs.value["a"]?.status)
    }

    @Test fun `update transforms existing run`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a"))
        r.update("a") { it.copy(status = SubAgentStatus.SUCCEEDED, result = "done") }
        assertEquals(SubAgentStatus.SUCCEEDED, r.runs.value["a"]?.status)
        assertEquals("done", r.runs.value["a"]?.result)
    }

    @Test fun `update on missing id is no-op`() {
        val r = SubAgentRegistry()
        r.update("nope") { it.copy(status = SubAgentStatus.SUCCEEDED) }
        assertTrue(r.runs.value.isEmpty())
    }

    @Test fun `list active_only filters terminal`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("running", status = SubAgentStatus.RUNNING))
        r.addPending(makeRun("done", status = SubAgentStatus.SUCCEEDED))
        r.addPending(makeRun("failed", status = SubAgentStatus.FAILED))
        assertEquals(3, r.list(activeOnly = false).size)
        assertEquals(1, r.list(activeOnly = true).size)
        assertEquals("running", r.list(activeOnly = true)[0].id)
    }

    @Test fun `activeCountForAssistant counts only matching parent in non-terminal`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a1", parentAssistant = "asst-1", status = SubAgentStatus.RUNNING))
        r.addPending(makeRun("a2", parentAssistant = "asst-1", status = SubAgentStatus.SUCCEEDED))
        r.addPending(makeRun("b1", parentAssistant = "asst-2", status = SubAgentStatus.RUNNING))
        assertEquals(1, r.activeCountForAssistant("asst-1"))
        assertEquals(1, r.activeCountForAssistant("asst-2"))
    }

    @Test fun `requestCancel returns false when no job registered`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("a"))  // no Job
        assertFalse(r.requestCancel("a"))
    }

    @Test fun `cancelAllForParent returns count of cancellable jobs`() {
        val r = SubAgentRegistry()
        // Three runs from same parent chat, with mock Jobs (we fake them via SupervisorJob)
        for (id in listOf("a", "b", "c")) {
            val job = Job()
            r.addPending(makeRun(id, parentChat = "chat-X", status = SubAgentStatus.RUNNING), job = job)
        }
        // One run from a different parent
        r.addPending(
            makeRun("other", parentChat = "chat-Y", status = SubAgentStatus.RUNNING),
            job = Job(),
        )
        // One terminal run from chat-X — should not be cancelled
        r.addPending(makeRun("done", parentChat = "chat-X", status = SubAgentStatus.SUCCEEDED), job = null)
        assertEquals(3, r.cancelAllForParent("chat-X"))
    }

    @Test fun `unknown id returns null on get`() {
        val r = SubAgentRegistry()
        assertNull(r.get("nonexistent"))
    }

    // 深挖-C4：超龄 RUNNING/PENDING 的驱逐。stale 的判定是 startedAt + timeout 已过
    //（DEFAULT_TIMEOUT_SECONDS = 300s），这里用 6 分钟前的时间戳。
    private fun staleAtMs(): Long =
        System.currentTimeMillis() - (SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS * 1000L + 60_000L)

    @Test fun `prune evicts oldest stale RUNNING run at cap when no terminal run exists`() {
        val r = SubAgentRegistry()
        for (i in 0 until SubAgentDefaults.REGISTRY_LRU_CAP) {
            r.addPending(makeRun("stale-$i", status = SubAgentStatus.RUNNING, startedAtMs = staleAtMs() + i))
        }
        assertEquals(SubAgentDefaults.REGISTRY_LRU_CAP, r.runs.value.size)

        r.addPending(makeRun("new", status = SubAgentStatus.PENDING))

        assertEquals(SubAgentDefaults.REGISTRY_LRU_CAP, r.runs.value.size)
        assertNull(r.get("stale-0"))
        assertNotNull(r.get("stale-1"))
        assertNotNull(r.get("new"))
    }

    @Test fun `prune never evicts healthy RUNNING run at cap`() {
        val r = SubAgentRegistry()
        for (i in 0 until SubAgentDefaults.REGISTRY_LRU_CAP) {
            r.addPending(makeRun("run-$i", status = SubAgentStatus.RUNNING))
        }

        r.addPending(makeRun("extra", status = SubAgentStatus.PENDING))

        // 无终态、无超龄可驱逐时允许超 cap，绝不驱逐未超时的运行中 run。
        assertEquals(SubAgentDefaults.REGISTRY_LRU_CAP + 1, r.runs.value.size)
        assertNotNull(r.get("run-0"))
        assertNotNull(r.get("extra"))
    }

    @Test fun `prune evicts terminal run before stale RUNNING run`() {
        val r = SubAgentRegistry()
        r.addPending(makeRun("done", status = SubAgentStatus.SUCCEEDED, startedAtMs = staleAtMs()))
        for (i in 1 until SubAgentDefaults.REGISTRY_LRU_CAP) {
            r.addPending(makeRun("stale-$i", status = SubAgentStatus.RUNNING, startedAtMs = staleAtMs() + i))
        }
        assertEquals(SubAgentDefaults.REGISTRY_LRU_CAP, r.runs.value.size)

        r.addPending(makeRun("new", status = SubAgentStatus.PENDING))

        assertEquals(SubAgentDefaults.REGISTRY_LRU_CAP, r.runs.value.size)
        assertNull(r.get("done"))
        assertNotNull(r.get("stale-1"))
        assertNotNull(r.get("new"))
    }

    @Test fun `evicting stale RUNNING run cancels its job`() {
        val r = SubAgentRegistry()
        val job = Job()
        r.addPending(makeRun("stale-0", status = SubAgentStatus.RUNNING, startedAtMs = staleAtMs()), job = job)
        for (i in 1 until SubAgentDefaults.REGISTRY_LRU_CAP) {
            r.addPending(makeRun("stale-$i", status = SubAgentStatus.RUNNING, startedAtMs = staleAtMs() + i))
        }

        r.addPending(makeRun("new", status = SubAgentStatus.PENDING))

        assertNull(r.get("stale-0"))
        assertTrue(job.isCancelled)
    }
}
