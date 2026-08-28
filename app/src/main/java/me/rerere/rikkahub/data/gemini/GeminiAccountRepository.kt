package me.rerere.rikkahub.data.gemini

import android.os.Build
import android.util.Log
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
        val fresh = ensureFreshLocked(account, force = true)
        // Quota is informational, so a backend that will not report it must not turn a perfectly
        // good token refresh into a failure.
        runCatching { fetchUsageLocked(fresh) }.getOrDefault(fresh)
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

    /**
     * Refresh the account's quota from `fetchAvailableModels`, which reports it alongside the
     * model list rather than on an endpoint of its own.
     */
    private suspend fun fetchUsageLocked(account: GeminiAccount): GeminiAccount {
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("$CODE_ASSIST_ENDPOINT/v1internal:fetchAvailableModels")
                    .antigravityHeaders(account.accessToken)
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).await()
        }
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (response.code == 401) {
                replaceAccount(account.id) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
            }
            error("Failed to fetch Gemini usage: ${response.code} $body")
        }
        val snapshot = parseGeminiQuotaUsage(json.parseToJsonElement(body).jsonObject)
        if (snapshot == null) {
            // Keeping the previous snapshot beats blanking the card, but the user is then looking
            // at a stale reading, so say why rather than failing silently.
            Log.w(TAG, "fetchAvailableModels reported no quota; keeping the previous snapshot")
            return account
        }
        val updated = account.copy(usage = snapshot)
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

/**
 * Pick the tier to onboard against from a `loadCodeAssist` response.
 *
 * `currentTier` wins when present: it is the tier the account is already on, not a signal that
 * the account is unusable. Only when there is no current tier does the default entry in
 * `allowedTiers` apply. Returning null leaves the caller on the legacy tier, which is the same
 * fallback Antigravity uses.
 */
internal fun selectGeminiTier(load: JsonObject): JsonObject? =
    load["currentTier"]?.jsonObject
        ?: load["allowedTiers"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["isDefault"]?.jsonPrimitive?.booleanOrNull == true }

private const val WINDOW_DAILY = "daily"
private const val WINDOW_WEEKLY = "weekly"
private const val ONE_DAY_SECONDS = 24 * 60 * 60L

// Quota can arrive under any of these keys, singular or as an array. The two prefixed ones name
// their own window; the bare ones have to be classified from what is inside them.
private val QUOTA_FIELDS = listOf(
    "quotaInfo" to null,
    "quotaInfos" to null,
    "dailyQuotaInfo" to WINDOW_DAILY,
    "dailyQuotaInfos" to WINDOW_DAILY,
    "weeklyQuotaInfo" to WINDOW_WEEKLY,
    "weeklyQuotaInfos" to WINDOW_WEEKLY,
)

/**
 * Collapse the per-model quota in a `fetchAvailableModels` response into one reading per window.
 *
 * Returns null when the response carries no quota at all, which keeps a backend that stops
 * reporting it from wiping a snapshot the user is still looking at.
 */
internal fun parseGeminiQuotaUsage(
    root: JsonObject,
    nowMillis: Long = System.currentTimeMillis(),
): GeminiUsageSnapshot? {
    val models = root["models"] as? JsonObject ?: return null
    var daily: GeminiUsageWindow? = null
    var weekly: GeminiUsageWindow? = null
    for (modelElement in models.values) {
        val model = modelElement as? JsonObject ?: continue
        for ((field, declaredWindow) in QUOTA_FIELDS) {
            for (info in quotaInfosIn(model[field])) {
                val fraction = info["remainingFraction"]?.jsonPrimitive?.doubleOrNull ?: continue
                val resetsAt = parseQuotaResetTime(info["resetTime"]?.jsonPrimitive?.contentOrNull)
                val window = GeminiUsageWindow(fraction.coerceIn(0.0, 1.0), resetsAt)
                val id = declaredWindow ?: classifyQuotaWindow(info, resetsAt, nowMillis)
                if (id == WINDOW_WEEKLY) {
                    weekly = scarcerOf(weekly, window)
                } else {
                    daily = scarcerOf(daily, window)
                }
            }
        }
    }
    if (daily == null && weekly == null) return null
    return GeminiUsageSnapshot(daily = daily, weekly = weekly, updatedAt = nowMillis)
}

private fun quotaInfosIn(element: kotlinx.serialization.json.JsonElement?): List<JsonObject> =
    when (element) {
        is JsonObject -> listOf(element)
        is kotlinx.serialization.json.JsonArray -> element.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

private fun scarcerOf(current: GeminiUsageWindow?, candidate: GeminiUsageWindow) =
    if (current == null || candidate.remainingFraction < current.remainingFraction) {
        candidate
    } else {
        current
    }

private fun classifyQuotaWindow(
    info: JsonObject,
    resetsAt: Long?,
    nowMillis: Long,
): String {
    val source = listOfNotNull(
        info["windowId"]?.jsonPrimitive?.contentOrNull,
        info["windowLabel"]?.jsonPrimitive?.contentOrNull,
    ).joinToString(" ").lowercase()
    if (source.contains("week") || source.contains("7d")) return WINDOW_WEEKLY
    if (source.contains("day") || source.contains("24h")) return WINDOW_DAILY
    // Nothing labelled it, so fall back to how far out it resets: anything more than a day away
    // cannot be a daily window.
    val secondsUntilReset = resetsAt?.minus(nowMillis / 1000) ?: return WINDOW_DAILY
    return if (secondsUntilReset > ONE_DAY_SECONDS) WINDOW_WEEKLY else WINDOW_DAILY
}

private fun parseQuotaResetTime(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(raw).epochSecond }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(raw).toEpochSecond() }.getOrNull()
}

/**
 * Read a `cloudaicompanionProject` value, which comes back either as a bare string or as an
 * object carrying an `id` depending on the tier, so accept both rather than assuming one shape.
 */
internal fun readProjectId(element: kotlinx.serialization.json.JsonElement?): String? =
    ((element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        ?: (element as? JsonPrimitive)?.contentOrNull)
        ?.takeIf { it.isNotBlank() }

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
