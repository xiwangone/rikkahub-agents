package me.rerere.ai.util

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 错误体解析的回归：状态码与服务端的机器可读 code/type 必须随 [HttpException] 一起带出去 ——
 * 下游的重试策略靠状态码区分「确定性 4xx」，失败分类器靠 code 识别内容风控等语义
 * （各家文案措辞不同，code 才是稳定判据）。
 */
class ErrorParserTest {

    @Test
    fun `状态码与服务端 code type 被带到 HttpException`() {
        val detail = Json.parseToJsonElement(
            """{"error":{"message":"Your request was rejected","type":"invalid_request_error","code":"content_policy_violation"}}""",
        ).parseErrorDetail(400)
        assertEquals("Your request was rejected", detail.message)
        assertEquals(400, detail.statusCode)
        assertEquals("invalid_request_error", detail.providerErrorType)
        assertEquals("content_policy_violation", detail.providerErrorCode)
    }

    @Test
    fun `顶层 code 形态（阿里百炼 DataInspectionFailed）也能取到`() {
        val detail = Json.parseToJsonElement(
            """{"code":"DataInspectionFailed","message":"Input data may contain inappropriate content."}""",
        ).parseErrorDetail(400)
        assertEquals(400, detail.statusCode)
        assertEquals("DataInspectionFailed", detail.providerErrorCode)
    }

    @Test
    fun `没有错误字段时整对象序列化且仍带状态码`() {
        val detail = Json.parseToJsonElement("""{"model":"deepseek-flash"}""").parseErrorDetail(400)
        assertEquals("""{"model":"deepseek-flash"}""", detail.message)
        assertEquals(400, detail.statusCode)
    }

    @Test
    fun `顶层 type 为笼统 error 时取嵌套的更具体值`() {
        val detail = Json.parseToJsonElement(
            """{"type":"error","error":{"type":"MissingSessionID","message":"Request is missing x-opencode-session"}}""",
        ).parseErrorDetail(400)
        assertEquals("MissingSessionID", detail.providerErrorType)
        assertEquals(400, detail.statusCode)
    }

    @Test
    fun `流内错误没有 HTTP 状态码时保持 null`() {
        val detail = Json.parseToJsonElement("""{"message":"stream aborted"}""").parseErrorDetail()
        assertEquals("stream aborted", detail.message)
        assertNull(detail.statusCode)
        assertNull(detail.providerErrorCode)
    }
}
