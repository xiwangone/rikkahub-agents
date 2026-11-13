package me.rerere.rikkahub.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.migrations.APP_DATABASE_MIGRATIONS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ImportedDatabaseReconciler] 的 JVM 覆盖（Robolectric 提供真实 SQLite）。
 *
 * 1. 常量与导出 schema 同步：当前版本跟随 [AppDatabase.VERSION]；2.4.x 基线（v27）的 hash、
 *    补建表 DDL 与 27.json 逐字一致；[ImportedDatabaseReconciler.POST_BASELINE_SENTINELS]
 *    覆盖 27 → 当前 在已有表上新增的每一列（以后再加列忘了同步，这里直接红）。
 * 2. issues #10/#11 回归（2.4.x 备份导入后首启 "duplicate column name"）：用 27.json 建出与
 *    2.4.1 备份同形的库（共享表 = v27，删掉本应用独有表，stamp 成 24 + 外来 identity）：
 *    - 不 reconcile 直接交给 Room：重放 24→25 撞列崩溃；
 *    - reconcile 后：被改写成真正的 v27 文件，Room 走正常 27→当前 迁移链打开、校验通过，数据保留。
 * 3. 本应用当前版本的真实备份：reconcile 不改动版本，Room 照常打开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class ImportedDatabaseReconcilerTest {

    private val testDb = "reconciler-legacy-241-unit-test"

    // 2.4.1 备份的真实标记：user_version 24 + 它自己的（外来）identity
    private val legacyVersion = 24
    private val legacyIdentity = "0ea1aaebfa031c7995c45a1e35822e1a"

    private val appSpecificTables = listOf(
        "scheduled_jobs", "scheduled_job_runs", "ssh_hosts", "telegram_chats",
        "workflows", "workflow_runs", "agent_runs",
    )

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        context.deleteDatabase(testDb)
    }

    // ---------- 常量与 schema 导出同步 ----------

    private val schemaDir: File =
        listOf(
            "app/schemas/me.rerere.rikkahub.data.db.AppDatabase",
            "schemas/me.rerere.rikkahub.data.db.AppDatabase",
        ).map(::File).first { it.isDirectory }

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject["database"]!!.jsonObject

    private fun JsonObject.entities(): Map<String, JsonObject> =
        this["entities"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["tableName"]!!.jsonPrimitive.content }

    @Test
    fun `expected version follows AppDatabase VERSION`() {
        assertEquals(AppDatabase.VERSION, ImportedDatabaseReconciler.EXPECTED_VERSION)
    }

    @Test
    fun `legacy baseline constants match schema 27`() {
        val db = schema(ImportedDatabaseReconciler.LEGACY_BASELINE_VERSION)
        assertEquals("27", db["version"]!!.jsonPrimitive.content)
        assertEquals(
            ImportedDatabaseReconciler.LEGACY_BASELINE_IDENTITY_HASH,
            db["identityHash"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `app specific DDL is byte-identical to schema 27`() {
        val entities = schema(ImportedDatabaseReconciler.LEGACY_BASELINE_VERSION).entities()
        val expected = appSpecificTables.flatMap { table ->
            val e = entities.getValue(table)
            listOf(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) +
                e["indices"]?.jsonArray.orEmpty().map {
                    it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                }
        }
        assertEquals(expected, ImportedDatabaseReconciler.APP_SPECIFIC_DDL)
        ImportedDatabaseReconciler.APP_SPECIFIC_DDL.forEach {
            assertTrue("not idempotent: $it", it.contains(" IF NOT EXISTS "))
        }
    }

    @Test
    fun `post baseline sentinels cover every column added after v27`() {
        val base = schema(ImportedDatabaseReconciler.LEGACY_BASELINE_VERSION).entities()
        val current = schema(AppDatabase.VERSION).entities()
        fun columns(e: JsonObject) = e["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
        val added = base.keys.intersect(current.keys).flatMap { table ->
            (columns(current.getValue(table)) - columns(base.getValue(table))).map { table.lowercase() to it }
        }.toSet()
        val sentinels = ImportedDatabaseReconciler.POST_BASELINE_SENTINELS.map { (t, c) -> t.lowercase() to c }.toSet()
        assertEquals(added, sentinels)
        // 基线哨兵列必须确实存在于 v27
        val baseConversation = columns(base.getValue("ConversationEntity"))
        ImportedDatabaseReconciler.LEGACY_BASELINE_SENTINELS.forEach { (_, c) -> assertTrue(c in baseConversation) }
    }

    // ---------- 2.4.x 导入回归 ----------

    /**
     * Robolectric 的 native SQLite 运行时没有 Linux aarch64 构建（CI 的 x86_64 正常）。
     * 只在该平台跳过真正开库的用例，避免本地 arm64 环境误报；其余平台照常执行。
     */
    private fun assumeNativeSqlite() {
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        assumeFalse(
            "Robolectric native SQLite is not available on Linux $arch",
            os.startsWith("Linux") && (arch == "aarch64" || arch == "arm64"),
        )
    }


    /** 用 27.json 建库、写一条会话，再降级成 2.4.1 备份的样子（无独有表、user_version 24、外来 identity）。 */
    private fun createLegacy241Backup() {
        helper.createDatabase(testDb, ImportedDatabaseReconciler.LEGACY_BASELINE_VERSION).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("c1", "hello", "[]", 1_000L, 1_000L),
            )
            close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(testDb).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            appSpecificTables.forEach { raw.execSQL("DROP TABLE IF EXISTS `$it`") }
            raw.execSQL("UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf<Any?>(legacyIdentity))
            raw.version = legacyVersion
        }
    }

    private fun openWithRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, testDb)
            .addMigrations(*APP_DATABASE_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun rawVersion(): Int =
        SQLiteDatabase.openDatabase(context.getDatabasePath(testDb).absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            .use { it.version }

    @Test
    fun `without reconcile a 2_4_1 backup reproduces the duplicate column crash`() {
        assumeNativeSqlite()
        createLegacy241Backup()
        val room = openWithRoom()
        try {
            room.openHelper.writableDatabase.query("SELECT COUNT(*) FROM ConversationEntity").use { it.moveToFirst() }
            fail("expected Room to crash replaying the 24 -> 25 migration on a 2.4.1 file")
        } catch (expected: Throwable) {
            val chain = generateSequence(expected) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue(
                "expected a duplicate-column failure, got: $chain",
                chain.contains("custom_system_prompt") || chain.contains("duplicate column"),
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun `after reconcile a 2_4_1 backup migrates to the current schema and keeps data`() {
        assumeNativeSqlite()
        createLegacy241Backup()

        val result = ImportedDatabaseReconciler.reconcileDatabaseFile(context.getDatabasePath(testDb))
        assertTrue("reconcile should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(ImportedDatabaseReconciler.LEGACY_BASELINE_VERSION, rawVersion())

        val room = openWithRoom()
        try {
            val db = room.openHelper.writableDatabase // 走 27 → 当前 迁移链并按当前 schema 校验
            assertEquals(AppDatabase.VERSION, db.version)
            db.query("SELECT title FROM ConversationEntity WHERE id = 'c1'").use { c ->
                assertTrue("seeded conversation row should survive the restore", c.moveToFirst())
                assertEquals("hello", c.getString(0))
            }
            for (table in appSpecificTables) {
                db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                    assertTrue("app-specific table $table should exist after reconcile", c.moveToFirst())
                    assertEquals("app-specific table $table should start empty", 0, c.getInt(0))
                }
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `a current-version backup is left at its version and opens`() {
        assumeNativeSqlite()
        helper.createDatabase(testDb, AppDatabase.VERSION).close()

        val result = ImportedDatabaseReconciler.reconcileDatabaseFile(context.getDatabasePath(testDb))
        assertTrue(result.isSuccess)
        assertEquals(AppDatabase.VERSION, rawVersion())

        val room = openWithRoom()
        try {
            room.openHelper.writableDatabase.query("SELECT COUNT(*) FROM ConversationEntity").use { assertTrue(it.moveToFirst()) }
        } finally {
            room.close()
        }
    }

    @Test
    fun `a missing file is a successful no-op`() {
        val missing = File(context.cacheDir, "does-not-exist.db")
        assertTrue(ImportedDatabaseReconciler.reconcileDatabaseFile(missing).isSuccess)
    }
}
