package me.rerere.rikkahub.web.routes

import kotlin.uuid.Uuid
import me.rerere.rikkahub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Path/query id parsing for the local web API: missing and malformed ids must be rejected, not defaulted. */
class RouteUtilsTest {
    @Test
    fun `valid uuid string parses`() {
        val text = "c0f69c03-7d45-4fd6-b915-4fba2b5988ee"

        assertEquals(Uuid.parse(text), text.toUuid())
    }

    @Test
    fun `null id reports the missing field name`() {
        try {
            (null as String?).toUuid("conversation id")
            fail("A missing id must be rejected")
        } catch (e: BadRequestException) {
            assertEquals("Missing conversation id", e.message)
        }
    }

    @Test
    fun `malformed id is rejected with the field name`() {
        try {
            "not-a-uuid".toUuid("assistant id")
            fail("A malformed id must be rejected")
        } catch (e: BadRequestException) {
            assertEquals("Invalid assistant id", e.message)
        }
    }

    @Test
    fun `default field name is used when none is given`() {
        try {
            "".toUuid()
            fail("An empty id must be rejected")
        } catch (e: BadRequestException) {
            assertEquals("Invalid id", e.message)
        }
    }
}
