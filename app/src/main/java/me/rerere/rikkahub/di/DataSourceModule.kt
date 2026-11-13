package me.rerere.rikkahub.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.TermuxCliCommandExecutor
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.remote.ModelsDevUpdater
import me.rerere.rikkahub.data.network.HostOverrideDns
import me.rerere.rikkahub.data.tunnel.TsnetSocketFactory
import me.rerere.rikkahub.data.network.SettingsProxyAuthenticator
import me.rerere.rikkahub.data.network.SettingsProxySelector
import me.rerere.rikkahub.data.network.SettingsSocks5Authenticator
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.codex.CodexAccountRepository
import me.rerere.rikkahub.data.codex.CodexCredentialStore
import me.rerere.rikkahub.data.codex.CodexOAuthManager
import me.rerere.rikkahub.data.codex.CodexProvider
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.backend.BackendSessionPathStore
import me.rerere.rikkahub.data.ai.backend.BackendInteractionNotifier
import me.rerere.ai.provider.providers.backend.SessionPathStore
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.DatabaseVersionGuard
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.gemini.GeminiAccountRepository
import me.rerere.rikkahub.data.gemini.GeminiCredentialStore
import me.rerere.rikkahub.data.gemini.GeminiOAuthManager
import me.rerere.rikkahub.data.gemini.GeminiProvider
import me.rerere.rikkahub.data.grok.GrokAccountRepository
import me.rerere.rikkahub.data.grok.GrokCredentialStore
import me.rerere.rikkahub.data.grok.GrokOAuthManager
import me.rerere.rikkahub.data.grok.GrokProvider
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.sync.BackupEncryptionManager
import me.rerere.rikkahub.data.sync.LogicalBackup
import me.rerere.rikkahub.data.sync.MigrationExporter
import me.rerere.rikkahub.data.sync.MigrationImporter
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val dataSourceModule =
    module {
        single {
            SettingsStore(context = get(), scope = get())
        }

        single {
            val context: Context = get()
            // 结构版本变化前先给库留快照：回滚到旧版本时才有原始数据可恢复。
            // 快照失败不阻断启动（磁盘满时阻断等于直接让应用无法打开），但必须告警：
            // 本次升级一旦出问题将没有快照可回滚。
            if (DatabaseVersionGuard.beforeOpen(context, AppDatabase.VERSION) is DatabaseVersionGuard.SnapshotResult.Failed) {
                AppLog.e("DataSourceModule", "database upgrade proceeding without a pre-upgrade snapshot; rollback to the previous version is unavailable")
            }
            val builder = Room
                .databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            APP_DATABASE_MIGRATIONS.forEach { builder.addMigrations(it) }
            builder
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            val dictDir = SimpleDictManager.extractDict(context)
                            val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                            cursor.use {
                                if (it.moveToFirst()) {
                                    val result = it.getString(0)
                                    val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                                    if (!success) {
                                        AppLog.e(
                                            "DataSourceModule",
                                            "jieba_dict failed: $result, path=${dictDir.absolutePath}",
                                        )
                                    }
                                }
                            }
                            me.rerere.rikkahub.data.db.fts.createMessageFtsTables(db)
                        }
                    },
                ).openHelperFactory(
                    RequerySQLiteOpenHelperFactory(
                        listOf(
                            RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                                options.customExtensions.add(
                                    SQLiteCustomExtension(
                                        context.applicationInfo.nativeLibraryDir + "/libsimple",
                                        null,
                                    ),
                                )
                                options
                            },
                        ),
                    ),
                ).build()
        }

        single {
            AssistantTemplateLoader(settingsStore = get())
        }

        single {
            PebbleEngine
                .Builder()
                .loader(get<AssistantTemplateLoader>())
                .defaultLocale(Locale.getDefault())
                .autoEscaping(false)
                .build()
        }

        single { TemplateTransformer(engine = get(), settingsStore = get()) }

        single {
            get<AppDatabase>().translationHistoryDao()
        }
        single {
            get<AppDatabase>().conversationDao()
        }

        single {
            get<AppDatabase>().memoryDao()
        }

        single {
            get<AppDatabase>().genMediaDao()
        }

        single {
            get<AppDatabase>().messageNodeDao()
        }

        single {
            get<AppDatabase>().managedFileDao()
        }

        single {
            get<AppDatabase>().favoriteDao()
        }

        single {
            get<AppDatabase>().mediaCreationDao()
        }

        single {
            get<AppDatabase>().workspaceDao()
        }

        single {
            get<AppDatabase>().folderDao()
        }

        single {
            get<AppDatabase>().vaultCredentialDao()
        }
        single {
            CredentialVaultRepository(get(), get(), get())
        }
        single {
            get<AppDatabase>().vaultAuditLogDao()
        }
        single {
            get<AppDatabase>().compressedArchiveDao()
        }
        single {
            VaultPreferences(get())
        }

        single {
            MessageFtsManager(get())
        }

        // Phase 24 — unified AgentRun ledger. DAO + the single shared writer/reader + the
        // boot-recovery sweep. AgentRunRepository has no cross-dependencies (only the DAO), so
        // there is no DI-cycle risk here.
        single { get<AppDatabase>().agentRunDao() }
        single { AgentRunRepository(get()) }
        single { AgentRunBootRecovery(context = get(), repository = get()) }

        single {
            McpManager(
                context = get(),
                settingsStore = get(),
                appScope = get(),
                filesManager = get(),
            )
        }

        single {
            GenerationLoop(
                context = get(),
                providerManager = get(),
                json = get(),
                memoryRepo = get(),
                conversationRepo = get(),
                aiLoggingManager = get(),
                systemPromptBuilder = get(),
            )
        }

        single {
            me.rerere.rikkahub.data.ai
                .SystemPromptBuilder()
        }

        // 模型能力目录：联网可更新的能力表（内置登记表未命中时兜底）
        single {
            me.rerere.rikkahub.data.ai.catalog.ModelCatalogRepository(
                context = get(),
                client = get(),
            )
        }

        single {
            ModelsDevUpdater(context = get(), client = get())
        }

        // 模型能力外置表（双轨：assets 基线 + 本机覆盖；覆盖语义）
        single {
            me.rerere.rikkahub.data.ai.catalog.ModelCapabilityOverrides(
                context = get(),
            )
        }

        single<OkHttpClient> {
            val settingsStore: SettingsStore = get()
            val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
                .build()
            java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
            val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
            val appliedNetworkSetting = AtomicReference(initialNetworkSetting)
            lateinit var client: OkHttpClient
            client = OkHttpClient.Builder()
                .proxySelector(SettingsProxySelector(settingsStore))
                .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
                .dns(HostOverrideDns(settingsStore))
                .socketFactory(TsnetSocketFactory())
                .connectTimeout(20, TimeUnit.SECONDS)
                // 注意：这里故意不设 callTimeout——生成请求要跑很久。listModels / 余额这类短请求
                // 由各 provider 用 shortRequestClient() 派生 30 秒 callTimeout 的 client（共享连接池）。
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(120, TimeUnit.SECONDS)
                // 禁止 https→http 降级重定向：共享客户端上挂着带凭据的请求（S3/WebDAV），
                // 降级会把 Authorization/Basic 凭据送到明文连接上。https→https 重定向不受影响。
                .followSslRedirects(false)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val networkSetting = settingsStore.settingsFlow.value.networkSetting
                    // Any network-setting change (proxy or pinned host addresses) must drop
                    // pooled connections, otherwise they keep using the previous route.
                    if (appliedNetworkSetting.getAndSet(networkSetting) != networkSetting) {
                        client.connectionPool.evictAll()
                    }

                    val originalRequest = chain.request()
                    val requestBuilder = originalRequest.newBuilder()
                        .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                    if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                        val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                            .trim()
                            .ifEmpty { "RikkaHub-Agents-Android/${BuildConfig.VERSION_NAME}" }
                        requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent)
                    }

                    chain.proceed(requestBuilder.build())
                }
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val contentTypeHeader = request.header("Content-Type")
                    if (
                        contentTypeHeader != null &&
                        contentTypeHeader.contains(";") &&
                        contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                    ) {
                        chain.proceed(
                            request
                                .newBuilder()
                                .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                                .build(),
                        )
                    } else {
                        chain.proceed(request)
                    }
                }
                .addNetworkInterceptor(RequestLoggingInterceptor())
                .apply {
                    // HEADERS-level logging prints Authorization: Bearer <api-key> to logcat.
                    // Debug-only so release builds never leak provider keys to logcat.
                    // Sensitive headers are redacted even in debug builds: a shared
                    // logcat still must not carry a provider key in plaintext.
                    if (BuildConfig.DEBUG) {
                        addInterceptor(
                            HttpLoggingInterceptor(OkHttpLoggingBridge).apply {
                                level = HttpLoggingInterceptor.Level.HEADERS
                                redactHeader("Authorization")
                                redactHeader("Proxy-Authorization")
                                redactHeader("x-api-key")
                                redactHeader("x-goog-api-key")
                                redactHeader("api-key")
                                redactHeader("Cookie")
                                redactHeader("Set-Cookie")
                            },
                        )
                    }
                }
                .build()
            client
        }

        // 账号型 provider（Codex / Grok / Gemini OAuth）的 client：从默认 client 派生。
        // 原先这里是「逐行复制默认参数但不带 proxy 配置」，于是用户在设置里配的代理对这三条
        // 链路无效 —— 没有 proxySelector 时 OkHttp 只回落到 JVM 默认 ProxySelector（默认行为：
        // 除非设了 https.proxyHost 系统属性，否则返回 DIRECT），也拿不到 proxyAuthenticator。
        // 这三家打的都是硬编码公网 SaaS 域名（chatgpt.com / api.openai.com / api.x.ai /
        // *.googleapis.com），不存在「内网地址被代理带走」的问题，所以直接继承是安全的。
        // newBuilder() 保留同样配置并共用默认 client 的连接池与 dispatcher：代理设置变更时
        // 默认 client 的拦截器 connectionPool.evictAll() 因此能一并生效。
        single<OkHttpClient>(named("codex")) {
            get<OkHttpClient>().newBuilder().build()
        }

        single<OkHttpClient>(named("gemini")) {
            get<OkHttpClient>().newBuilder().build()
        }

        single<OkHttpClient>(named("grok")) {
            get<OkHttpClient>().newBuilder().build()
        }

        single {
            GrokAccountRepository(
                store = GrokCredentialStore(context = get(), json = get()),
                client = get(named("grok")),
                json = get(),
            )
        }

        single {
            GrokOAuthManager(
                context = get(),
                scope = get<AppScope>(),
                client = get(named("grok")),
                repository = get(),
                json = get(),
            )
        }

        single {
            GeminiAccountRepository(
                store = GeminiCredentialStore(context = get(), json = get()),
                client = get(named("gemini")),
                json = get(),
            )
        }

        single {
            GeminiOAuthManager(
                context = get(),
                scope = get<AppScope>(),
                client = get(named("gemini")),
                repository = get(),
            )
        }

        single {
            CodexAccountRepository(
                store = CodexCredentialStore(context = get(), json = get()),
                client = get(named("codex")),
                json = get(),
            )
        }

        single {
            CodexOAuthManager(
                context = get(),
                scope = get<AppScope>(),
                client = get(named("codex")),
                repository = get(),
            )
        }

        single {
            val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = get()
            val codexRepository: CodexAccountRepository = get()
            val json: Json = get()
            ProviderManager(
                client = get(),
                context = get(),
                cliExecutor = TermuxCliCommandExecutor(
                    context = get(),
                    sshHostRepository = get(),
                    vaultRepository = get(),
                ),
            ).also { pm ->
                pm.registerProvider(
                    "local_litert",
                    me.rerere.locallm.litert.LiteRtProvider(
                        context = get(),
                        runtime = get(),
                        prefs = get(),
                        settingsUpdater = { transform ->
                            settingsStore.update { old -> old.copy(providers = transform(old.providers)) }
                        },
                    ),
                )
                // 本地 llama.cpp：ProviderSetting.LlamaCppLocal(providerKey="local_llamacpp") 可反序列化，
                // 不注册会在导入包含该键的配置时抛 IllegalArgumentException。
                pm.registerProvider(
                    "local_llamacpp",
                    me.rerere.llamacpp.LlamaCppProvider(
                        context = get(),
                        runtime = get(),
                        prefs = get(),
                    ),
                )
                pm.registerProvider(
                    "codex",
                    CodexProvider(
                        client = get(named("codex")),
                        repository = codexRepository,
                        json = json,
                        scope = get<AppScope>(),
                    ),
                )
                pm.registerProvider(
                    "grok",
                    GrokProvider(
                        client = get(named("grok")),
                        repository = get<GrokAccountRepository>(),
                        json = json,
                        scope = get<AppScope>(),
                    ),
                )
                pm.registerProvider(
                    "gemini_oauth",
                    GeminiProvider(
                        client = get(named("gemini")),
                        repository = get<GeminiAccountRepository>(),
                        json = json,
                    ),
                )
                // 覆盖默认 backend provider：注入交互桥，使 Ask/Approval 走通知闭环
                pm.registerProvider(
                    "backend",
                    me.rerere.ai.provider.providers.backend.BackendProvider(
                        cliExecutor = TermuxCliCommandExecutor(
                            context = get(),
                            sshHostRepository = get(),
                            vaultRepository = get(),
                        ),
                        interactionHandler = get<BackendInteractionNotifier>(),
                        // 会话路径落盘：App 重启后同一对话仍能 resume（而非退化成新建）
                        sessionPathStore = get<BackendSessionPathStore>(),
                    ),
                )
            }
        }

        single {
            BackendSessionPathStore(
                appContext = get(),
            )
        }

        // 接口绑定：UI 侧按接口注入（与上一实例同一个）
        single<SessionPathStore> { get<BackendSessionPathStore>() }

        single {
            BackupEncryptionManager(
                settingsStore = get(),
            )
        }

        single {
            LogicalBackup(
                json = get(),
                settingsStore = get(),
                vaultRepository = get(),
                sshHostRepository = get(),
                scheduledJobRepository = get(),
            )
        }

        single {
            MigrationExporter(
                webDavSync = get(),
                backupEncryptionManager = get(),
            )
        }

        single {
            MigrationImporter(
                webDavSync = get(),
                backupEncryptionManager = get(),
            )
        }

        single {
            WebDavSync(
                settingsStore = get(),
                json = get(),
                context = get(),
                httpClient = get(),
                appDatabase = get(),
                backupEncryptionManager = get(),
                logicalBackup = get(),
            )
        }

        single<HttpClient> {
            HttpClient(OkHttp) {
                engine {
                    config {
                        connectTimeout(20, TimeUnit.SECONDS)
                        readTimeout(10, TimeUnit.MINUTES)
                        writeTimeout(120, TimeUnit.SECONDS)
                        // 禁止 https→http 降级重定向：与共享 OkHttp 客户端同理，凭据不得落到明文连接。
                        followSslRedirects(false)
                        followRedirects(true)
                        retryOnConnectionFailure(true)
                    }
                }
            }
        }

        single {
            S3Sync(
                settingsStore = get(),
                json = get(),
                context = get(),
                httpClient = get(),
                appDatabase = get(),
                backupEncryptionManager = get(),
                logicalBackup = get(),
            )
        }

    }
