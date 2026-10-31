package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移段的编解码契约：清单要能被导入侧认出来、凭证明文档要能无损往返、
 * 只有清单的空包不能被当成「可应用」——否则导入会成功但什么也没写回。
 */
class MigrationExporterTest {

    private fun entryNames() =
        mapOf(
            "manifest" to "${MigrationPackage.DIR}/${MigrationPackage.MANIFEST_NAME}",
            "credentials" to "${MigrationPackage.DIR}/${MigrationPackage.CREDENTIALS_NAME}",
            "providers" to "${MigrationPackage.DIR}/${MigrationPackage.PROVIDERS_NAME}",
            "sshHosts" to "${MigrationPackage.DIR}/${MigrationPackage.SSH_HOSTS_NAME}",
            "scheduledJobs" to "${MigrationPackage.DIR}/${MigrationPackage.SCHEDULED_JOBS_NAME}",
        )

    @Test
    fun `manifest round-trips and is recognized by the importer`() {
        val manifest =
            MigrationPackage.buildManifest(
                exportedAt = 42L,
                items = listOf("DATABASE", "SETTINGS"),
            )
        val parsed = MigrationPackage.parseManifest(MigrationPackage.encodeManifest(manifest))

        assertEquals(42L, parsed?.exportedAt)
        assertEquals(listOf("DATABASE", "SETTINGS"), parsed?.items)
        assertEquals(MigrationPackage.CURRENT_VERSION, parsed?.version)
    }

    @Test
    fun `credentials round-trip without losing fields`() {
        val credentials =
            listOf(
                MigrationCredential(
                    name = "TOKEN_A",
                    value = "plain",
                    type = "Git",
                    group = "Git",
                    metaJson = """{"endpoint":"https://example.invalid"}""",
                    publicKey = "ssh-ed25519 AAAA",
                ),
            )

        assertEquals(
            credentials,
            MigrationPackage.parseCredentials(MigrationPackage.encodeCredentials(credentials)),
        )
    }

    @Test
    fun `a manifest-only package has no applicable payload`() {
        val names = entryNames()

        assertFalse(MigrationPackage.hasLogicalPayload(listOf(names.getValue("manifest"))))
        assertTrue(MigrationPackage.hasLogicalPayload(listOf(names.getValue("credentials"))))
        assertTrue(MigrationPackage.hasLogicalPayload(listOf(names.getValue("scheduledJobs"))))
    }

    /** v1 旧包（无 ssh/jobs 段）必须仍可导入；高于当前版本则整体拒绝，避免静默丢段。 */
    @Test
    fun `v1 manifest still imports while future versions are rejected`() {
        val v1 = MigrationManifest(version = 1, exportedAt = 1L, items = listOf("SETTINGS"))
        assertEquals(1, MigrationPackage.parseManifest(MigrationPackage.encodeManifest(v1))?.version)

        val v3 = MigrationManifest(version = 3, exportedAt = 1L, items = listOf("SETTINGS"))
        assertNull(MigrationPackage.parseManifest(MigrationPackage.encodeManifest(v3)))
    }

    @Test
    fun `logical domains round-trip through the manifest`() {
        val items =
            listOf("DATABASE", "PROVIDERS", "VAULT_CREDENTIALS", "SSH_HOSTS", "SCHEDULED_JOBS")
        val parsed =
            MigrationPackage.parseManifest(
                MigrationPackage.encodeManifest(MigrationPackage.buildManifest(exportedAt = 0L, items = items)),
            )

        assertEquals(items, parsed?.items)
    }

    @Test
    fun `only per-domain items count as logical selections`() {
        assertTrue(LogicalDomains.anySelected(listOf("PROVIDERS")))
        assertTrue(LogicalDomains.anySelected(listOf("SCHEDULED_JOBS")))
        assertFalse(LogicalDomains.anySelected(listOf("DATABASE", "SETTINGS", "AVATARS")))
    }
}
