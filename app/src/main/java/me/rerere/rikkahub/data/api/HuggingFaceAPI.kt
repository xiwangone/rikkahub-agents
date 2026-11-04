package me.rerere.rikkahub.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.HfModelDetail
import me.rerere.rikkahub.data.model.HfModelSearchResult
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** HTTP non-2xx exception carrying status [code] for [HuggingFaceModelSearch]. */
class HuggingFaceHttpException(val code: Int) : IOException("HTTP $code")

/**
 * The public, unauthenticated HuggingFace Hub model API. No credentials are sent or accepted:
 * gated and private repos are surfaced through [HfModelDetail.gated] / [HfModelDetail.private]
 * on [getModel], never fetched with an access token.
 */
interface HuggingFaceAPI {
    suspend fun searchModels(
        search: String,
        filter: String,
        limit: Int,
    ): List<HfModelSearchResult>

    /** [repoId] is `<owner>/<name>`, e.g. "Qwen/Qwen3-4B-GGUF". */
    suspend fun getModel(
        repoId: String,
        blobs: Boolean,
    ): HfModelDetail

    companion object {
        private const val BASE_URL = "https://huggingface.co"

        fun create(httpClient: OkHttpClient): HuggingFaceAPI =
            object : HuggingFaceAPI {
                override suspend fun searchModels(
                    search: String,
                    filter: String,
                    limit: Int,
                ): List<HfModelSearchResult> =
                    withContext(Dispatchers.IO) {
                        val url =
                            "$BASE_URL/api/models".toHttpUrl().newBuilder()
                                .addQueryParameter("search", search)
                                .addQueryParameter("filter", filter)
                                .addQueryParameter("limit", limit.toString())
                                .build()
                        val request = Request.Builder().url(url).get().build()
                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw HuggingFaceHttpException(response.code)
                            val body = response.body?.string().orEmpty()
                            JsonInstant.decodeFromString<List<HfModelSearchResult>>(body)
                        }
                    }

                override suspend fun getModel(
                    repoId: String,
                    blobs: Boolean,
                ): HfModelDetail =
                    withContext(Dispatchers.IO) {
                        val cleanRepo = repoId.trim('/')
                        val url =
                            "$BASE_URL/api/models/$cleanRepo".toHttpUrl().newBuilder()
                                .addQueryParameter("blobs", blobs.toString())
                                .build()
                        val request = Request.Builder().url(url).get().build()
                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw HuggingFaceHttpException(response.code)
                            val body = response.body?.string().orEmpty()
                            JsonInstant.decodeFromString<HfModelDetail>(body)
                        }
                    }
            }
    }
}
