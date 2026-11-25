package me.rerere.rikkahub.ui.pages.backup.tabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportExportCleanupTest {

    @Test
    fun `runDeleteExportTarget returns true when cleanup succeeds`() {
        var invoked = false
        val deleted = runDeleteExportTarget {
            invoked = true
            true
        }
        assertTrue(invoked)
        assertTrue(deleted)
    }

    @Test
    fun `runDeleteExportTarget returns false without throwing when cleanup throws`() {
        val deleted = runDeleteExportTarget {
            error("provider delete unsupported")
        }
        assertFalse(deleted)
    }
}
