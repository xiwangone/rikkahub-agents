package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabase_AutoMigration_47_48_Impl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration test for the 47 -> 48 auto-migration.
 *
 * Version 48 adds two columns to `ssh_hosts`, both with defaults so existing rows are
 * backfilled automatically:
 * - `platform` TEXT NOT NULL DEFAULT 'AUTO'
 * - `allowHostKeyRotation` INTEGER NOT NULL DEFAULT 0
 *
 * The tests create a version 47 database, insert representative pre-migration rows, run
 * the generated auto-migration, and assert that existing data is preserved byte-for-byte
 * while the new columns take their defaults. The last test also opens the migrated
 * database through Room itself and round-trips through [me.rerere.rikkahub.data.db.dao.SshHostDao],
 * proving the migrated database is usable by the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class Migration_47_48Test {
    private val testDbName = "migration-47-48-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun fullHostValues(): ContentValues = ContentValues().apply {
        put("name", "prod-server")
        put("host", "example.com")
        put("port", 2222)
        put("user", "deploy")
        put("password", "fixture-password-not-a-credential")
        put("privateKey", null as String?)
        put("passphrase", null as String?)
        put("vaultCredentialRef", "vault-key-1")
        put("templateRef", "template-a")
        put("fallbackHostsJson", "[\"backup-1\",\"backup-2\"]")
        put("jumpHost", "jump-1")
        put("sshOptions", "ConnectTimeout 5")
        put("createdAtMs", 1700000000000L)
    }

    private fun minimalHostValues(): ContentValues = ContentValues().apply {
        put("name", "minimal-host")
        put("host", "10.0.0.1")
        put("port", 22)
        put("user", "root")
        put("password", null as String?)
        put("privateKey", null as String?)
        put("passphrase", null as String?)
        put("vaultCredentialRef", null as String?)
        put("templateRef", null as String?)
        put("fallbackHostsJson", null as String?)
        put("jumpHost", null as String?)
        put("sshOptions", null as String?)
        put("createdAtMs", 1700000001000L)
    }

    private fun createVersion47DatabaseWithHosts() {
        helper.createDatabase(testDbName, 47).apply {
            insert("ssh_hosts", SQLiteDatabase.CONFLICT_NONE, fullHostValues())
            insert("ssh_hosts", SQLiteDatabase.CONFLICT_NONE, minimalHostValues())
            close()
        }
    }

    @Test
    fun migrate47To48_addsPlatformAndAllowHostKeyRotationColumns() {
        helper.createDatabase(testDbName, 47).apply { close() }

        // runMigrationsAndValidate also checks the resulting schema against 48.json.
        val db = helper.runMigrationsAndValidate(
            testDbName, 48, true, AppDatabase_AutoMigration_47_48_Impl()
        )

        val columns = db.query("PRAGMA table_info(ssh_hosts)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val typeIdx = cursor.getColumnIndexOrThrow("type")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            val defaultIdx = cursor.getColumnIndexOrThrow("dflt_value")
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(nameIdx),
                        Triple(
                            cursor.getString(typeIdx),
                            cursor.getInt(notNullIdx),
                            cursor.getString(defaultIdx)
                        )
                    )
                }
            }
        }
        db.close()

        // ALTER TABLE ADD COLUMN appends the new columns at the end of the table, so
        // platform/allowHostKeyRotation come after createdAtMs even though the entity
        // declares them before it. Room's schema validation is order-independent.
        assertEquals(
            "ssh_hosts should keep its 13 pre-migration columns plus the 2 new ones",
            listOf(
                "name", "host", "port", "user", "password", "privateKey", "passphrase",
                "vaultCredentialRef", "templateRef", "fallbackHostsJson", "jumpHost",
                "sshOptions", "createdAtMs", "platform", "allowHostKeyRotation"
            ),
            columns.keys.toList()
        )
        assertEquals(
            "platform should be TEXT NOT NULL DEFAULT 'AUTO'",
            Triple("TEXT", 1, "'AUTO'"),
            columns["platform"]
        )
        assertEquals(
            "allowHostKeyRotation should be INTEGER NOT NULL DEFAULT 0",
            Triple("INTEGER", 1, "0"),
            columns["allowHostKeyRotation"]
        )
    }

    @Test
    fun migrate47To48_preservesExistingHostsAndAppliesDefaults() {
        createVersion47DatabaseWithHosts()

        val db = helper.runMigrationsAndValidate(
            testDbName, 48, true, AppDatabase_AutoMigration_47_48_Impl()
        )

        val rows = db.query("SELECT * FROM ssh_hosts ORDER BY name ASC").use { cursor ->
            buildList {
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val hostIdx = cursor.getColumnIndexOrThrow("host")
                val portIdx = cursor.getColumnIndexOrThrow("port")
                val userIdx = cursor.getColumnIndexOrThrow("user")
                val passwordIdx = cursor.getColumnIndexOrThrow("password")
                val privateKeyIdx = cursor.getColumnIndexOrThrow("privateKey")
                val passphraseIdx = cursor.getColumnIndexOrThrow("passphrase")
                val vaultRefIdx = cursor.getColumnIndexOrThrow("vaultCredentialRef")
                val templateRefIdx = cursor.getColumnIndexOrThrow("templateRef")
                val fallbackIdx = cursor.getColumnIndexOrThrow("fallbackHostsJson")
                val jumpHostIdx = cursor.getColumnIndexOrThrow("jumpHost")
                val sshOptionsIdx = cursor.getColumnIndexOrThrow("sshOptions")
                val platformIdx = cursor.getColumnIndexOrThrow("platform")
                val rotationIdx = cursor.getColumnIndexOrThrow("allowHostKeyRotation")
                val createdAtIdx = cursor.getColumnIndexOrThrow("createdAtMs")
                while (cursor.moveToNext()) {
                    add(
                        mapOf(
                            "name" to cursor.getString(nameIdx),
                            "host" to cursor.getString(hostIdx),
                            "port" to cursor.getInt(portIdx),
                            "user" to cursor.getString(userIdx),
                            "password" to cursor.getString(passwordIdx),
                            "privateKey" to cursor.getString(privateKeyIdx),
                            "passphrase" to cursor.getString(passphraseIdx),
                            "vaultCredentialRef" to cursor.getString(vaultRefIdx),
                            "templateRef" to cursor.getString(templateRefIdx),
                            "fallbackHostsJson" to cursor.getString(fallbackIdx),
                            "jumpHost" to cursor.getString(jumpHostIdx),
                            "sshOptions" to cursor.getString(sshOptionsIdx),
                            "platform" to cursor.getString(platformIdx),
                            "allowHostKeyRotation" to cursor.getInt(rotationIdx),
                            "createdAtMs" to cursor.getLong(createdAtIdx)
                        )
                    )
                }
            }
        }
        db.close()

        assertEquals("Both pre-migration hosts should survive", 2, rows.size)

        val prod = rows.first { it["name"] == "prod-server" }
        assertEquals("example.com", prod["host"])
        assertEquals(2222, prod["port"])
        assertEquals("deploy", prod["user"])
        assertEquals("fixture-password-not-a-credential", prod["password"])
        assertEquals(null, prod["privateKey"])
        assertEquals(null, prod["passphrase"])
        assertEquals("vault-key-1", prod["vaultCredentialRef"])
        assertEquals("template-a", prod["templateRef"])
        assertEquals("[\"backup-1\",\"backup-2\"]", prod["fallbackHostsJson"])
        assertEquals("jump-1", prod["jumpHost"])
        assertEquals("ConnectTimeout 5", prod["sshOptions"])
        assertEquals(1700000000000L, prod["createdAtMs"])
        assertEquals("New platform column should default to AUTO", "AUTO", prod["platform"])
        assertEquals(
            "New allowHostKeyRotation column should default to 0 (false)",
            0,
            prod["allowHostKeyRotation"]
        )

        val minimal = rows.first { it["name"] == "minimal-host" }
        assertEquals("10.0.0.1", minimal["host"])
        assertEquals(22, minimal["port"])
        assertEquals("root", minimal["user"])
        assertEquals(null, minimal["password"])
        assertEquals(null, minimal["vaultCredentialRef"])
        assertEquals(1700000001000L, minimal["createdAtMs"])
        assertEquals("New platform column should default to AUTO", "AUTO", minimal["platform"])
        assertEquals(
            "New allowHostKeyRotation column should default to 0 (false)",
            0,
            minimal["allowHostKeyRotation"]
        )
    }

    @Test
    fun migrate47To48_migratedDatabaseOpensWithRoomAndDaoRoundTrips() {
        createVersion47DatabaseWithHosts()
        helper.runMigrationsAndValidate(
            testDbName, 48, true, AppDatabase_AutoMigration_47_48_Impl()
        ).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, AppDatabase::class.java, testDbName)
            .build()
        try {
            runBlocking {
                val migrated = database.sshHostDao().getByName("prod-server")
                assertTrue("Migrated host should be readable through the DAO", migrated != null)
                assertEquals("example.com", migrated!!.host)
                assertEquals(2222, migrated.port)
                assertEquals("AUTO", migrated.platform)
                assertEquals(false, migrated.allowHostKeyRotation)

                database.sshHostDao().upsert(
                    migrated.copy(
                        name = "windows-host",
                        host = "win.example.com",
                        platform = "WINDOWS",
                        allowHostKeyRotation = true
                    )
                )
                val windows = database.sshHostDao().getByName("windows-host")
                assertTrue("New host with explicit platform should be readable", windows != null)
                assertEquals("WINDOWS", windows!!.platform)
                assertEquals(true, windows.allowHostKeyRotation)

                assertEquals(
                    "DAO should see the migrated row plus the new row",
                    3,
                    database.sshHostDao().getAll().size
                )
            }
        } finally {
            database.close()
        }
    }
}
