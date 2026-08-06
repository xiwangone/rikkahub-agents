package me.rerere.rikkahub.data.gemini

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiAccountRepository internal constructor(
    private val store: GeminiCredentialStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var state = store.read().let { stored ->
        stored.copy(
            accounts = stored.accounts.map { account ->
                if (
                    account.tokenStatus != GeminiTokenStatus.INVALID &&
                    account.expiresAt <= System.currentTimeMillis()
                ) {
                    account.copy(tokenStatus = GeminiTokenStatus.EXPIRED)
                } else {
                    account
                }
            }
        )
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<GeminiAccount>> = _accounts.asStateFlow()

    /**
     * Persist a freshly exchanged token set.
     *
     * Both the sign-in identity and the Cloud Code Assist project are resolved here, outside the
     * lock, because each is a network round trip and holding the mutex across them would stall
     * every concurrent generate request behind a sign-in.
     */
    suspend fun saveLogin(tokenJson: String): GeminiAccount {
        val token = json.parseToJsonElement(tokenJson).jsonObject
        val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing access token")
        val refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing refresh token. Sign in again and grant offline access.")
        val identity = fetchIdentity(accessToken)
        val projectId = discoverProject(accessToken)
        val expiresAt = System.currentTimeMillis() + (
            token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
            ) * 1000

        return mutex.withLock {
            val id = identity.email.ifBlank { projectId }
            val existing = state.accounts.firstOrNull { it.id == id }
            val account = GeminiAccount(
                id = id,
                name = identity.name.ifBlank { identity.email.ifBlank { "Google account" } },
                email = identity.email,
                projectId = projectId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                enabled = existing?.enabled ?: true,
                tokenStatus = GeminiTokenStatus.AVAILABLE,
            )
            updateState(
                state.copy(accounts = state.accounts.filterNot { it.id == account.id } + account)
            )
            account
        }
    }

    suspend fun acquireAccount(): GeminiAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Google account is signed in")
        repeat(state.accounts.size) {
            val index = selectGeminiAccountIndex(
                accounts = state.accounts,
                startIndex = state.nextAccountIndex,
            ) ?: error("No available Google account")
            val candidate = state.accounts[index]
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            val fresh = runCatching { ensureFreshLocked(candidate) }.getOrNull() ?: return@repeat
            return fresh
        }
        error("No available Google account")
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == accountId },
                nextAccountIndex = 0,
            )
        )
    }

    suspend fun refreshAccount(accountId: String): GeminiAccount = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Google account not found")
        ensureFreshLocked(account, force = true)
    }

    suspend fun refreshAll() {
        accounts.value.forEach { account ->
            runCatching { refreshAccount(account.id) }
        }
    }

    private suspend fun ensureFreshLocked(
        account: GeminiAccount,
        force: Boolean = false,
    ): GeminiAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return account
        }
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(GeminiOAuthManager.TOKEN_URL)
                    .post(
                        FormBody.Builder()
                            .add("client_id", GeminiOAuthManager.CLIENT_ID)
                            .add("client_secret", GeminiOAuthManager.CLIENT_SECRET)
                            .add("refresh_token", account.refreshToken)
                            .add("grant_type", "refresh_token")
                            .build()
                    )
                    .build()
            ).await()
        }
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            if (isGeminiRefreshAuthenticationFailure(response.code, responseBody, json)) {
                replaceAccount(account.id) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
            }
            error("Token refresh failed: ${response.code}")
        }
        val token = json.parseToJsonElement(responseBody).jsonObject
        val updated = account.copy(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing refreshed access token"),
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: account.refreshToken,
            expiresAt = System.currentTimeMillis() + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            tokenStatus = GeminiTokenStatus.AVAILABLE,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private suspend fun fetchIdentity(accessToken: String): GeminiIdentity =
        withContext(Dispatchers.IO) {
            val response = runCatching {
                client.newCall(
                    Request.Builder()
                        .url(USERINFO_URL)
                        .header("Authorization", "Bearer $accessToken")
                        .get()
                        .build()
                ).await()
            }.getOrNull() ?: return@withContext GeminiIdentity()
            if (!response.isSuccessful) {
                response.close()
                return@withContext GeminiIdentity()
            }
            val body = runCatching {
                json.parseToJsonElement(response.body.string()).jsonObject
            }.getOrNull() ?: return@withContext GeminiIdentity()
            GeminiIdentity(
                email = body["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = body["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

    /**
     * Resolve the `cloudaicompanionProject` this account generates against.
     *
     * Mirrors the Gemini CLI's own onboarding: loadCodeAssist reports the tier, and an account
     * that has never used Code Assist is onboarded onto the free tier through a long-running
     * operation that has to be polled until it reports done.
     */
    private suspend fun discoverProject(accessToken: String): String = withContext(Dispatchers.IO) {
        val loadResponse = client.newCall(
            Request.Builder()
                .url("$CODE_ASSIST_ENDPOINT/v1internal:loadCodeAssist")
                .geminiCliHeaders(accessToken)
                .post(
                    json.encodeToString(
                        buildJsonObject {
                            put("metadata", clientMetadataJson())
                        }
                    ).toRequestBody(JSON_MEDIA_TYPE)
                )
                .build()
        ).await()
        val loadBody = loadResponse.body.string()
        if (!loadResponse.isSuccessful) {
            error("loadCodeAssist failed: ${loadResponse.code} $loadBody")
        }
        val load = json.parseToJsonElement(loadBody).jsonObject

        load["cloudaicompanionProject"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return@withContext it }

        // An account already on a paid or Workspace tier is expected to bring its own GCP
        // project. The Gemini CLI reads it from GOOGLE_CLOUD_PROJECT, which does not exist on
        // Android, so there is nothing to fall back to and the failure has to be explicit.
        if (load["currentTier"] != null) {
            error(WORKSPACE_PROJECT_REQUIRED)
        }

        val tierId = load["allowedTiers"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["isDefault"]?.jsonPrimitive?.booleanOrNull == true }
            ?.get("id")?.jsonPrimitive?.contentOrNull
            ?: TIER_LEGACY
        if (tierId != TIER_FREE && tierId != TIER_LEGACY) {
            error(WORKSPACE_PROJECT_REQUIRED)
        }

        val onboardResponse = client.newCall(
            Request.Builder()
                .url("$CODE_ASSIST_ENDPOINT/v1internal:onboardUser")
                .geminiCliHeaders(accessToken)
                .post(
                    json.encodeToString(
                        buildJsonObject {
                            put("tierId", tierId)
                            put("metadata", clientMetadataJson())
                        }
                    ).toRequestBody(JSON_MEDIA_TYPE)
                )
                .build()
        ).await()
        val onboardBody = onboardResponse.body.string()
        if (!onboardResponse.isSuccessful) {
            error("onboardUser failed: ${onboardResponse.code} $onboardBody")
        }

        var operation = json.parseToJsonElement(onboardBody).jsonObject
        var attempt = 0
        while (operation["done"]?.jsonPrimitive?.booleanOrNull != true) {
            val name = operation["name"]?.jsonPrimitive?.contentOrNull
                ?: error("onboardUser returned no operation to poll")
            if (attempt >= POLL_MAX_ATTEMPTS) {
                error("Project provisioning did not finish after $POLL_MAX_ATTEMPTS attempts")
            }
            attempt++
            delay(POLL_INTERVAL_MS)
            val pollResponse = client.newCall(
                Request.Builder()
                    .url("$CODE_ASSIST_ENDPOINT/v1internal/$name")
                    .geminiCliHeaders(accessToken)
                    .get()
                    .build()
            ).await()
            val pollBody = pollResponse.body.string()
            if (!pollResponse.isSuccessful) {
                error("Failed to poll project provisioning: ${pollResponse.code} $pollBody")
            }
            operation = json.parseToJsonElement(pollBody).jsonObject
        }

        operation["response"]?.jsonObject
            ?.get("cloudaicompanionProject")?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error(WORKSPACE_PROJECT_REQUIRED)
    }

    private fun replaceAccount(
        accountId: String,
        transform: (GeminiAccount) -> GeminiAccount,
    ) {
        updateState(
            state.copy(
                accounts = state.accounts.map {
                    if (it.id == accountId) transform(it) else it
                }
            )
        )
    }

    private fun updateState(newState: GeminiAccountState) {
        state = newState
        store.write(newState)
        _accounts.value = newState.accounts
    }

    companion object {
        const val CODE_ASSIST_ENDPOINT = "https://cloudcode-pa.googleapis.com"
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json"
        private const val REFRESH_MARGIN_MS = 30_000L
        private const val TIER_FREE = "free-tier"
        private const val TIER_LEGACY = "legacy-tier"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val POLL_MAX_ATTEMPTS = 24
        private const val WORKSPACE_PROJECT_REQUIRED =
            "This Google account needs its own Cloud project for Code Assist, which this app " +
                "cannot provision. Use a personal account on the free tier instead."
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private data class GeminiIdentity(
    val email: String = "",
    val name: String = "",
)

/**
 * The Cloud Code Assist backend gates model routing and quota on the client it believes it is
 * talking to, so every call carries the Gemini CLI's own User-Agent and client metadata.
 */
internal fun Request.Builder.geminiCliHeaders(
    accessToken: String,
    modelId: String = "gemini-2.5-pro",
): Request.Builder {
    val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"
    return header("Authorization", "Bearer $accessToken")
        .header("User-Agent", "GeminiCLI/$GEMINI_CLI_VERSION/$modelId (android; $arch; terminal)")
        .header(
            "Client-Metadata",
            "ideType=IDE_UNSPECIFIED,platform=PLATFORM_UNSPECIFIED,pluginType=GEMINI",
        )
}

internal fun clientMetadataJson(): JsonObject = buildJsonObject {
    put("ideType", "IDE_UNSPECIFIED")
    put("platform", "PLATFORM_UNSPECIFIED")
    put("pluginType", "GEMINI")
}

internal const val GEMINI_CLI_VERSION = "0.46.0"

internal fun isGeminiRefreshAuthenticationFailure(
    statusCode: Int,
    responseBody: String,
    json: Json,
): Boolean {
    if (statusCode == 401) return true
    if (statusCode != 400) return false
    val errorCode = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return errorCode == "invalid_grant" || errorCode == "invalid_token"
}

internal fun selectGeminiAccountIndex(
    accounts: List<GeminiAccount>,
    startIndex: Int,
): Int? {
    if (accounts.isEmpty()) return null
    repeat(accounts.size) { offset ->
        val index = (startIndex + offset).mod(accounts.size)
        if (accounts[index].isAvailable()) return index
    }
    return null
}
