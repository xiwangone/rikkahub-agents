package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import me.rerere.ai.ui.StreamChunk

/** Attempt-local state: reset only before opening a new model stream. */
internal class GenerationStreamProgress {
    var receivedAnyChunk: Boolean = false
        private set
    var receivedMeaningfulOutput: Boolean = false
        private set

    fun record(chunk: StreamChunk): Boolean {
        receivedAnyChunk = true
        val meaningful = isMeaningfulStreamChunk(chunk)
        if (meaningful) receivedMeaningfulOutput = true
        return meaningful
    }

    fun reset() {
        receivedAnyChunk = false
        receivedMeaningfulOutput = false
    }
}

/** Observe real provider chunks before rendering/coalescing can buffer or discard them. */
internal fun Flow<StreamChunk>.trackGenerationStreamProgress(
    progress: GenerationStreamProgress,
    onMeaningfulOutput: () -> Unit = {},
    onNonMeaningfulCompletion: () -> Unit = {},
): Flow<StreamChunk> = onEach { chunk ->
    if (progress.record(chunk)) onMeaningfulOutput()
}.onCompletion { cause ->
    // Never replace a provider/downstream failure or cancellation with an empty-stream error.
    if (cause == null) {
        if (shouldReportEmptyGenerationStream(progress.receivedAnyChunk)) {
            throw IOException("Model stream closed without meaningful output")
        }
        if (!progress.receivedMeaningfulOutput) onNonMeaningfulCompletion()
    }
}
