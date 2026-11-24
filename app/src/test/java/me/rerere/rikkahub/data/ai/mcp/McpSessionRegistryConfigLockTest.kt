package me.rerere.rikkahub.data.ai.mcp

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.rerere.oauth.OAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsKeyStore
import me.rerere.rikkahub.data.datastore.SyntheticSettingsSecurityProvider
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.uuid.Uuid

/**
 * 深挖-C7：`forceResync` 与 `reconnectAfterDelay` 对 `session.config` 的写
 * 必须在 `lifecycleMutex` 内，否则 in-flight connectSession 会用旧的 syncedConfig
 * 把刚写入的配置覆盖掉（tools-only 变更静默丢失）。
 *
 * 竞争本身不可靠复现，这里直接钉死不变量：测试先占住目标 session 的 lifecycleMutex，
 * 再跑写配置的操作——锁没放之前配置必须纹丝不动，放锁之后才允许变新。
 * 写在锁外时第一步断言即失败（配置已提前变成新值）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class McpSessionRegistryConfigLockTest {
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeScope: AppScope? = null
    private var activeDataStoreScope: CoroutineScope? = null
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settingsFile: File
    private lateinit var registry: McpSessionRegistry
    private lateinit var store: SettingsStore
    private var securityProvider: SyntheticSettingsSecurityProvider? = null

    private val serverId = Uuid.parse("11111111-2222-4333-8444-555555555555")

    private fun configA() =
        McpServerConfig.SseTransportServer(
            id = serverId,
            commonOptions = McpCommonOptions(enable = true, name = "s1"),
            url = "http://127.0.0.1:1/mcp",
        )

    private fun configB() =
        McpServerConfig.SseTransportServer(
            id = serverId,
            commonOptions = McpCommonOptions(enable = true, name = "s1-renamed"),
            url = "http://127.0.0.1:1/mcp",
        )

    /** Pump Android Main while real DataStore work proceeds on IO. No coroutine implementation is mocked. */
    private fun <T> perform(block: suspend () -> T): Result<T> {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "Synthetic settings operation timed out" }
        return runCatching { runBlocking { task.await() } }
    }

    private fun stopCollector() {
        activeScope?.let { scope -> perform { scope.coroutineContext[Job]!!.cancelAndJoin() }.getOrThrow() }
        activeScope = null
    }

    private fun stopDataStoreScope() {
        activeDataStoreScope?.let { scope -> perform { scope.coroutineContext[Job]!!.cancelAndJoin() }.getOrThrow() }
        activeDataStoreScope = null
    }

    private fun openStore(): SettingsStore {
        val scope = AppScope()
        activeScope = scope
        return SettingsStore(RuntimeEnvironment.getApplication(), scope, dataStore)
    }

    private fun buildRegistry(store: SettingsStore): McpSessionRegistry {
        // 目标地址 127.0.0.1:1 永远拒绝连接，但必须保证连接失败是“快失败”：
        // callTimeout 给整次调用封顶（连接/读/等 SSE endpoint 事件都在内），
        // NO_PROXY 排除测试 JVM 继承到的任何代理配置把回环连接劫走后静默挂起。
        val okHttp =
            OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.SECONDS)
                .build()
        val ktorClient =
            HttpClient(OkHttp) {
                engine { preconfigured = okHttp }
                install(ContentNegotiation) {
                    json(Json { prettyPrint = true; isLenient = true })
                }
                install(SSE)
            }
        val coordinator =
            McpOAuthCoordinator(
                settingsStore = store,
                appScope = AppScope(),
                oauthClient = OAuthHttpClient(okHttp),
                discoveryClient = McpOAuthDiscoveryClient(okHttp),
                callbackServer = OAuthLoopbackCallbackServer(),
                authorizationLauncher = OAuthAuthorizationLauncher { _, _ -> },
                updateStatus = { _, _ -> },
            )
        return McpSessionRegistry(store, AppScope(), ktorClient, coordinator, McpStatusStore())
    }

    private fun setupWithConfigA() {
        val provider = SyntheticSettingsSecurityProvider()
        securityProvider = provider
        Security.insertProviderAt(provider, 1)
        SyntheticSettingsKeyStore.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SyntheticSettingsKeyStore.unavailable = false
        stopKoin()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        try {
            val context: Application = RuntimeEnvironment.getApplication()
            settingsFile = File(context.filesDir, "datastore/mcp-lock-test-${serverId}.preferences_pb")
            val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            activeDataStoreScope = seedScope
            dataStore = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { settingsFile })
            perform {
                dataStore.edit { values ->
                    values.clear()
                    values[SettingsStore.MCP_SERVERS] =
                        JsonInstant.encodeToString<List<McpServerConfig>>(listOf(configA()))
                }
            }.getOrThrow()
            store = openStore()
            assertTrue("seeded store initializes", perform { store.awaitInitialized() }.isSuccess)
            perform {
                store.settingsFlow.first { it.mcpServers.any { server -> server.id == serverId } }
            }.getOrThrow()
            registry = buildRegistry(store)
            // 127.0.0.1:1 直接拒绝连接：addClient 走完一次失败的 connect 即返回，session 留在表里。
            perform { registry.addClient(configA()) }.getOrThrow()
            assertEquals("s1", configOf(sessionOf(serverId)).commonOptions.name)
        } catch (e: Throwable) {
            teardown()
            throw e
        }
    }

    private fun teardown() {
        runCatching { stopCollector() }
        runCatching { stopDataStoreScope() }
        runCatching { stopKoin() }
        runCatching { workers.cancel() }
        SyntheticSettingsKeyStore.key = null
        SyntheticSettingsKeyStore.unavailable = false
        securityProvider?.let { Security.removeProvider(it.name) }
        securityProvider = null
    }

    private fun sessionOf(id: Uuid): Any {
        val field = McpSessionRegistry::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        val sessions = field.get(registry) as? ConcurrentHashMap<*, *>
            ?: error("sessions field is not a map")
        return sessions[id] ?: error("No session for $id")
    }

    private fun configOf(session: Any): McpServerConfig {
        val field = session.javaClass.getDeclaredField("config").apply { isAccessible = true }
        return field.get(session) as McpServerConfig
    }

    private fun lockOf(session: Any): Mutex {
        val field = session.javaClass.getDeclaredField("lifecycleMutex").apply { isAccessible = true }
        return field.get(session) as Mutex
    }

    /**
     * 从一个协程里占住 [mutex] 直到调用方放行（Mutex 必须由加锁的协程解锁，
     * 所以不能由测试线程直接 lock）。
     */
    private fun holdLock(mutex: Mutex): Pair<Job, CountDownLatch> {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            workers.launch {
                mutex.withLock {
                    locked.countDown()
                    release.await()
                }
            }
        assertTrue("test setup: lock holder never acquired the lock", locked.await(5, TimeUnit.SECONDS))
        return holder to release
    }

    private fun pollUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (check()) return true
            Thread.sleep(20)
        }
        return check()
    }

    private suspend fun invokeReconnectAfterDelay(session: Any, delayMs: Long) {
        val method =
            McpSessionRegistry::class.java.getDeclaredMethod(
                "reconnectAfterDelay",
                session.javaClass,
                Long::class.javaPrimitiveType,
                Continuation::class.java,
            ).apply { isAccessible = true }
        suspendCoroutine<Unit> { cont ->
            try {
                method.invoke(registry, session, delayMs, cont)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }
    }

    private fun switchSettingsToConfigB() {
        perform {
            dataStore.edit { values ->
                values[SettingsStore.MCP_SERVERS] =
                    JsonInstant.encodeToString<List<McpServerConfig>>(listOf(configB()))
            }
        }.getOrThrow()
        perform {
            store.settingsFlow.first { settings ->
                settings.mcpServers.any { it.id == serverId && it.commonOptions.name == "s1-renamed" }
            }
        }.getOrThrow()
    }

    @Test
    fun `forceResync waits for the lifecycle lock before adopting the new config`() {
        setupWithConfigA()
        try {
            switchSettingsToConfigB()
            val session = sessionOf(serverId)
            val (holder, release) = holdLock(lockOf(session))
            try {
                val job = workers.async { registry.forceResync(serverId) }
                val becameB =
                    pollUntil(timeoutMs = 1000) {
                        configOf(session).commonOptions.name == "s1-renamed"
                    }
                assertFalse("config must not change while the lifecycle lock is held", becameB)
                assertTrue("forceResync must still be waiting for the lock", job.isActive)
                release.countDown()
                perform { job.await() }.getOrThrow()
                assertEquals("s1-renamed", configOf(session).commonOptions.name)
            } finally {
                release.countDown()
                perform { holder.join() }.getOrThrow()
            }
        } finally {
            teardown()
        }
    }

    @Test
    fun `reconnectAfterDelay waits for the lifecycle lock before adopting the latest config`() {
        setupWithConfigA()
        try {
            switchSettingsToConfigB()
            val session = sessionOf(serverId)
            val (holder, release) = holdLock(lockOf(session))
            try {
                val job = workers.async { invokeReconnectAfterDelay(session, 0L) }
                val becameB =
                    pollUntil(timeoutMs = 1000) {
                        configOf(session).commonOptions.name == "s1-renamed"
                    }
                assertFalse("config must not change while the lifecycle lock is held", becameB)
                assertTrue("reconnectAfterDelay must still be waiting for the lock", job.isActive)
                release.countDown()
                perform { job.await() }.getOrThrow()
                assertEquals("s1-renamed", configOf(session).commonOptions.name)
            } finally {
                release.countDown()
                perform { holder.join() }.getOrThrow()
            }
        } finally {
            teardown()
        }
    }
}
