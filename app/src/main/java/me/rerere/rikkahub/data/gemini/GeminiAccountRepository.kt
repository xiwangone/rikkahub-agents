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
     * Mirrors Antigravity's own onboarding: loadCodeAssist either hands back a project outright
     * or reports the tier to onboard against, and an account that has never used Code Assist is
     * provisioned one by onboardUser.
     */
    private suspend fun discoverProject(accessToken: String): String = withContext(Dispatchers.IO) {
        val loadResponse = client.newCall(
            Request.Builder()
                .url("$CODE_ASSIST_ENDPOINT/v1internal:loadCodeAssist")
                .antigravityHeaders(accessToken)
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

        readProjectId(load["cloudaicompanionProject"])
            ?.let { return@withContext it }

        val tierId = selectGeminiTier(load)?.get("id")?.jsonPrimitive?.contentOrNull ?: TIER_LEGACY

        // onboardUser returns a long-running operation that is usually already finished. When it
        // is not, Antigravity re-sends the same request rather than polling the operation by name,
        // so the provisioning it kicked off is picked up by the next call's response.
        var operation: JsonObject? = null
        for (attempt in 0 until ONBOARD_MAX_ATTEMPTS) {
            if (attempt > 0) delay(ONBOARD_RETRY_INTERVAL_MS)
            val response = client.newCall(
                Request.Builder()
                    .url("$CODE_ASSIST_ENDPOINT/v1internal:onboardUser")
                    .antigravityHeaders(accessToken)
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
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("onboardUser failed: ${response.code} $body")
            }
            val parsed = json.parseToJsonElement(body).jsonObject
            operation = parsed
            if (parsed["done"]?.jsonPrimitive?.booleanOrNull == true) break
        }

        val finished = operation ?: error("onboardUser returned nothing")
        readProjectId(finished["response"]?.jsonObject?.get("cloudaicompanionProject"))
            ?: error("onboardUser finished without returning a project: $finished")
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
        private const val TAG = "GeminiAccountRepository"
        private const val TIER_LEGACY = "legacy-tier"
        private const val ONBOARD_RETRY_INTERVAL_MS = 2_000L
        private const val ONBOARD_MAX_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private data class GeminiIdentity(
    val email: String = "",
    val name: String = "",
)

/**
 * The Cloud Code Assist backend gates model routing and quota on the client it believes it is
 * talking to, so every call identifies itself as `antigravity/hub/<version> <os>/<arch>`. Unlike
 * the Gemini CLI, Antigravity sends no `Client-Metadata` header: the same information travels in
 * the request body instead. The arch names follow Go's conventions, so an x86_64 device reports
 * `amd64`.
 */
internal fun Request.Builder.antigravityHeaders(accessToken: String): Request.Builder {
    val arch = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "x86_64" -> "amd64"
        "x86" -> "386"
        else -> "arm64"
    }
    return header("Authorization", "Bearer $accessToken")
        .header("User-Agent", "antigravity/hub/$ANTIGRAVITY_VERSION android/$arch")
}

internal fun clientMetadataJson(): JsonObject = buildJsonObject {
    put("ideType", "ANTIGRAVITY")
    put("platform", "PLATFORM_UNSPECIFIED")
    put("pluginType", "GEMINI")
}

internal const val ANTIGRAVITY_VERSION = "2.1.4"

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
