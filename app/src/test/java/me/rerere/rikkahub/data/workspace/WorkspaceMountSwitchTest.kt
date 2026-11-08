package me.rerere.rikkahub.data.workspace

import me.rerere.workspace.WorkspaceShellBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceMountSwitchTest {

    @Test
    fun `default backend is PROOT and derived chrootEnabled stays synchronized`() {
        val switch = WorkspaceMountSwitch()
        assertEquals(WorkspaceShellBackend.PROOT, switch.backend)
        assertFalse(switch.chrootEnabled)

        switch.chrootEnabled = true
        assertEquals(WorkspaceShellBackend.ROOT_CHROOT, switch.backend)
        assertTrue(switch.chrootEnabled)

        switch.backend = WorkspaceShellBackend.DISABLED
        assertFalse(switch.chrootEnabled)

        switch.chrootEnabled = false
        assertEquals(WorkspaceShellBackend.PROOT, switch.backend)
        assertFalse(switch.chrootEnabled)
    }
}
