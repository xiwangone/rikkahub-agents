package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for issue #42: launch_activity had no approval gate while its sibling launch_app
 * did. Asserts the fix without re-testing the whole [ToolApprovalDefaults] set.
 */
class ToolApprovalDefaultsTest {

    @Test
    fun `launch_activity requires approval, same as launch_app`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("launch_activity"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("launch_activity"))
    }

    @Test
    fun `keystore_delete_key and vault key mutators are approval gated`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("keystore_delete_key"))
        org.junit.Assert.assertFalse(ToolApprovalDefaults.allowsAlwaysAllow("keystore_delete_key"))
        assertTrue(ToolApprovalDefaults.requiresApproval("vault_gen_key"))
        assertTrue(ToolApprovalDefaults.requiresApproval("vault_credential_normalize_names"))
        assertTrue(ToolApprovalDefaults.requiresApproval("vault_pgp_sign"))
        assertTrue(ToolApprovalDefaults.requiresApproval("vault_pgp_decrypt"))
    }
}
