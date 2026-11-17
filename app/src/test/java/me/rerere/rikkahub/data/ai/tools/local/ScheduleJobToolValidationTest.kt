package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleJobToolValidationTest {

    private val knownTools = listOf("post_notification", "telegram_send_message", "termux_run_command", "vault_ssh_exec")
    // Headless authorisation snapshots for the creation-time check (A1).
    private val yolo = ToolApprovalDefaults.HeadlessApprovalSnapshot(yoloEnabled = true, alwaysAllowGrants = emptySet())
    private val none = ToolApprovalDefaults.HeadlessApprovalSnapshot.NONE

    private fun base(): JsonObject = buildJsonObject {
        put("name", "test")
        put("mode", "llm")
        put("schedule_type", "cron")
        put("cron_expression", "@hourly")
        put("prompt", "do a thing")
    }

    @Test
    fun `valid llm cron job passes`() {
        val r = ScheduleJobValidator.validate(base(), knownTools, none)
        assertNull(r)
    }

    @Test
    fun `invalid cron returns invalid_cron`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "test")
            put("mode", "llm")
            put("schedule_type", "cron")
            put("cron_expression", "not a cron")
            put("prompt", "x")
        }, knownTools, none)
        assertNotNull(r)
        assertEquals("invalid_cron", r!!.code)
    }

    @Test
    fun `mode llm without prompt rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools, none)
        assertEquals("mutual_exclusive", r!!.code)
    }

    @Test
    fun `mode direct empty actions rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("actions", buildJsonArray { })
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools, yolo)
        assertEquals("empty_actions", r!!.code)
    }

    @Test
    fun `mode direct unknown tool rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "no_such_tool"); put("args", buildJsonObject { })
            }) })
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools, yolo)
        assertEquals("unknown_tool", r!!.code)
    }

    @Test
    fun `mode direct hardline-blocked rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "termux_run_command")
                put("args", buildJsonObject { put("command", "rm -rf /") })
            }) })
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools, yolo)
        assertEquals("hardline_blocked", r!!.code)
    }

    @Test
    fun `bounds inverted rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("start_at_unix_ms", 200L); put("end_at_unix_ms", 100L)
        }, knownTools, none)
        assertEquals("bounds_inverted", r!!.code)
    }

    @Test
    fun `bad timezone rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("timezone", "Mars/Olympus")
        }, knownTools, none)
        assertEquals("bad_timezone", r!!.code)
    }

    @Test
    fun `too many actions rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("schedule_type", "once"); put("at_unix_ms", 100L)
            put("actions", buildJsonArray {
                repeat(51) {
                    add(buildJsonObject {
                        put("tool", "post_notification")
                        put("args", buildJsonObject { put("title", "t"); put("body", "b") })
                    })
                }
            })
        }, knownTools, yolo)
        assertEquals("too_many_actions", r!!.code)
    }

    @Test
    fun `prompt too long rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("prompt", "a".repeat(4001))
        }, knownTools, none)
        assertEquals("prompt_too_long", r!!.code)
    }

    @Test
    fun `prompt at limit accepted`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("prompt", "a".repeat(4000))
        }, knownTools, none)
        assertNull(r)
    }

    @Test
    fun `bounds past rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("end_at_unix_ms", 1L)  // epoch ms — always in the past
        }, knownTools, none)
        assertEquals("bounds_past", r!!.code)
    }

    @Test
    fun `max_runs zero rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("max_runs", 0)
        }, knownTools, none)
        assertEquals("max_runs_invalid", r!!.code)
    }

    @Test
    fun `max_runs above the history retention ceiling rejected`() {
        // Without this, trim(keep = historyRetentionFor(maxRuns)) caps retention at
        // MAX_HISTORY_RETENTION while max_runs itself keeps climbing, so the job's
        // configured stop point becomes permanently unreachable via countSuccessful().
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("max_runs", me.rerere.rikkahub.service.MAX_HISTORY_RETENTION + 1)
        }, knownTools, none)
        assertEquals("max_runs_invalid", r!!.code)
    }

    @Test
    fun `max_runs at the history retention ceiling accepted`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("max_runs", me.rerere.rikkahub.service.MAX_HISTORY_RETENTION)
        }, knownTools, none)
        assertNull(r)
    }

    @Test
    fun `bad catchup value rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("catchup", "never")
        }, knownTools, none)
        assertEquals("bad_catchup", r!!.code)
    }

    @Test
    fun `bad tag uppercase rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("tags", buildJsonArray { add("HasUppercase") })
        }, knownTools, none)
        assertEquals("bad_tag", r!!.code)
    }

    // ---- Split validators (schedule_job_direct / schedule_job_llm) ----

    private fun directBase(): JsonObject = buildJsonObject {
        put("name", "test")
        put("schedule_type", "once")
        put("at_unix_ms", 100L)
        put("actions", buildJsonArray { add(buildJsonObject {
            put("tool", "post_notification")
            put("args", buildJsonObject { put("title", "t"); put("body", "b") })
        }) })
    }

    private fun llmBase(): JsonObject = buildJsonObject {
        put("name", "test")
        put("schedule_type", "cron")
        put("cron_expression", "@hourly")
        put("prompt", "do a thing")
    }

    @Test
    fun `validateDirect accepts valid direct input without mode`() {
        assertNull(ScheduleJobValidator.validateDirect(directBase(), knownTools, yolo))
    }

    @Test
    fun `validateDirect rejects prompt`() {
        val r = ScheduleJobValidator.validateDirect(buildJsonObject {
            put("name", "x")
            put("schedule_type", "once"); put("at_unix_ms", 100L)
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "post_notification"); put("args", buildJsonObject { })
            }) })
            put("prompt", "nope")
        }, knownTools, yolo)
        assertEquals("mutual_exclusive", r!!.code)
    }

    @Test
    fun `validateDirect rejects missing actions`() {
        val r = ScheduleJobValidator.validateDirect(buildJsonObject {
            put("name", "x")
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools, yolo)
        assertEquals("mutual_exclusive", r!!.code)
    }

    @Test
    fun `validateLlm accepts valid llm input without mode`() {
        assertNull(ScheduleJobValidator.validateLlm(llmBase()))
    }

    @Test
    fun `validateLlm rejects actions`() {
        val r = ScheduleJobValidator.validateLlm(buildJsonObject {
            put("name", "x")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("prompt", "p")
            put("actions", buildJsonArray { })
        })
        assertEquals("mutual_exclusive", r!!.code)
    }

    @Test
    fun `validateLlm rejects missing prompt`() {
        val r = ScheduleJobValidator.validateLlm(buildJsonObject {
            put("name", "x")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
        })
        assertEquals("mutual_exclusive", r!!.code)
    }

    @Test
    fun `legacy validate dispatches on mode`() {
        // llm via legacy entrypoint
        assertNull(ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("prompt", "p")
        }, knownTools, none))
        // direct via legacy entrypoint
        assertNull(ScheduleJobValidator.validate(directBase().toMutableMap().apply {
            put("mode", JsonPrimitive("direct"))
        }.let { buildJsonObject { it.forEach { (k, v) -> put(k, v) } } }, knownTools, yolo))
        // bad mode still rejected
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "bogus")
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools, none)
        assertEquals("bad_mode", r!!.code)
    }

    // ---- Headless authorisation (A1) ----

    private fun directWithTool(tool: String): JsonObject = buildJsonObject {
        put("name", "x")
        put("schedule_type", "once"); put("at_unix_ms", 100L)
        put("actions", buildJsonArray { add(buildJsonObject {
            put("tool", tool)
            put("args", buildJsonObject { put("command", "echo hi") })
        }) })
    }

    @Test
    fun `mode direct gated tool without authorization rejected`() {
        val r = ScheduleJobValidator.validateDirect(directWithTool("termux_run_command"), knownTools, none)
        assertNotNull(r)
        assertEquals("authorization_required", r!!.code)
        assertTrue(r.detail.contains("termux_run_command"))
        assertTrue(r.detail.contains("Always Allow"))
    }

    @Test
    fun `mode direct gated tool passes with YOLO`() {
        assertNull(ScheduleJobValidator.validateDirect(directWithTool("termux_run_command"), knownTools, yolo))
    }

    @Test
    fun `mode direct NO_ALWAYS_ALLOW tool rejected even with YOLO`() {
        val r = ScheduleJobValidator.validateDirect(directWithTool("vault_ssh_exec"), knownTools, yolo)
        assertNotNull(r)
        assertEquals("authorization_required", r!!.code)
        assertTrue(r.detail.contains("vault_ssh_exec"))
        assertTrue(r.detail.contains("can never run unattended"))
    }
}
