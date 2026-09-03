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
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.TermuxCliCommandExecutor
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.network.SettingsProxyAuthenticator
import me.rerere.rikkahub.data.network.SettingsProxySelector
import me.rerere.rikkahub.data.network.SettingsSocks5Authenticator
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.RikkaHubAPI
import me.rerere.rikkahub.data.codex.CodexAccountRepository
import me.rerere.rikkahub.data.codex.CodexCredentialStore
import me.rerere.rikkahub.data.codex.CodexOAuthManager
import me.rerere.rikkahub.data.codex.CodexProvider
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_23_24
import me.rerere.rikkahub.data.db.migrations.Migration_28_29
import me.rerere.rikkahub.data.db.migrations.Migration_29_30
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.db.migrations.Migration_31_32
import me.rerere.rikkahub.data.db.migrations.Migration_32_33
import me.rerere.rikkahub.data.db.migrations.Migration_33_34
import me.rerere.rikkahub.data.db.migrations.Migration_34_35
import me.rerere.rikkahub.data.db.migrations.Migration_35_36
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.grok.GrokAccountRepository
import me.rerere.rikkahub.data.grok.GrokCredentialStore
import me.rerere.rikkahub.data.grok.GrokOAuthManager
import me.rerere.rikkahub.data.grok.GrokProvider
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
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
            Room
                .databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(
                    Migration_6_7,
                    Migration_11_12,
                    Migration_13_14,
                    Migration_14_15,
                    Migration_15_16,
                    Migration_23_24,
                    Migration_28_29,
                    Migration_29_30,
                    Migration_30_31,
                    Migration_31_32,
                    Migration_32_33,
                    Migration_33_34,
                    Migration_34_35,
                    Migration_35_36,
                ).addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            val dictDir = SimpleDictManager.extractDict(context)
                            val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                            cursor.use {
                                if (it.moveToFirst()) {
                                    val result = it.getString(0)
                                    val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                                    if (!success) {
                                        android.util.Log.e(
                                            "DataSourceModule",
                                            "jieba_dict failed: $result, path=${dictDir.absolutePath}",
                                        )
                                    }
                                }
                            }
                            db.execSQL(
                                me.rerere.rikkahub.data.db.fts.MESSAGE_FTS_CREATE_SQL
                                    .trimIndent(),
                            )
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
            get<AppDatabase>().workspaceDao()
        }

        single {
            get<AppDatabase>().folderDao()
        }

        single {
            get<AppDatabase>().vaultCredentialDao()
        }
        single {
            CredentialVaultRepository(get(), get())
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
                appEventBus = get(),
            )
        }

        single {
            GenerationHandler(
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

        single<OkHttpClient> {
            val settingsStore: SettingsStore = get()
            val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
                .build()
            java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
            val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
            val appliedProxySetting = AtomicReference(
                Triple(
                    initialNetworkSetting.proxyUrl,
                    initialNetworkSetting.proxyUsername,
                    initialNetworkSetting.proxyPassword,
                )
            )
            lateinit var client: OkHttpClient
            client = OkHttpClient.Builder()
                .proxySelector(SettingsProxySelector(settingsStore))
                .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followSslRedirects(true)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val networkSetting = settingsStore.settingsFlow.value.networkSetting
                    val currentProxySetting = Triple(
                        networkSetting.proxyUrl,
                        networkSetting.proxyUsername,
                        networkSetting.proxyPassword,
                    )
                    if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                        client.connectionPool.evictAll()
                    }

                    val originalRequest = chain.request()
                    val requestBuilder = originalRequest.newBuilder()
                        .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                    if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                        val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                            .trim()
                            .ifEmpty { "RikkaHub-Android/${BuildConfig.VERSION_NAME}" }
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
                .addInterceptor(AIRequestInterceptor())
                .apply {
                    // HEADERS-level logging prints Authorization: Bearer <api-key> to logcat.
                    // Debug-only so release builds never leak provider keys to logcat.
                    if (BuildConfig.DEBUG) {
                        addInterceptor(
                            HttpLoggingInterceptor().apply {
                                level = HttpLoggingInterceptor.Level.HEADERS
                            },
                        )
                    }
                }
                .build()
                .also { SearchService.init(it, get()) }
            client
        }

        single<OkHttpClient>(named("codex")) {
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followSslRedirects(true)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        single<OkHttpClient>(named("grok")) {
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followSslRedirects(true)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
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
                // 覆盖默认 backend provider：注入交互桥，使 Ask/Approval 走通知闭环
                pm.registerProvider(
                    "backend",
                    me.rerere.ai.provider.providers.backend.BackendProvider(
                        cliExecutor = TermuxCliCommandExecutor(
                            context = get(),
                            sshHostRepository = get(),
                            vaultRepository = get(),
                        ),
                        interactionHandler = get<me.rerere.rikkahub.data.ai.backend.BackendInteractionNotifier>(),
                    ),
                )
            }
        }

        single {
            WebDavSync(
                settingsStore = get(),
                json = get(),
                context = get(),
                httpClient = get(),
                appDatabase = get(),
            )
        }

        single<HttpClient> {
            HttpClient(OkHttp) {
                engine {
                    config {
                        connectTimeout(20, TimeUnit.SECONDS)
                        readTimeout(10, TimeUnit.MINUTES)
                        writeTimeout(120, TimeUnit.SECONDS)
                        followSslRedirects(true)
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
            )
        }

        single<Retrofit> {
            Retrofit
                .Builder()
                .baseUrl("https://api.rikka-ai.com")
                .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
                .build()
        }

        single<RikkaHubAPI> {
            get<Retrofit>().create(RikkaHubAPI::class.java)
        }
    }
