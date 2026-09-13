package me.rerere.rikkahub.utils

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.AppScope
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `blank update api returns current version without network`() =
        runBlocking {
            val client =
                OkHttpClient.Builder()
                    .addInterceptor {
                        throw AssertionError("Blank update API must not perform network requests")
                    }
                    .build()
            val checker =
                UpdateChecker(
                    client = client,
                    appScope = AppScope(),
                    apiUrl = "",
                    currentVersionName = "2.1.17",
                )

            // updateState 由 appScope 承载（SharingStarted.Lazily），取到成功态即可
            val state =
                withTimeout(5_000) {
                    checker.updateState.first { it is UiState.Success }
                }
            assertTrue(state is UiState.Success)
            val success = state as UiState.Success

            assertEquals("2.1.17", success.data.version)
            assertTrue(success.data.downloads.isEmpty())
        }
}
