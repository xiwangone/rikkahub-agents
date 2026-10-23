package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import me.rerere.ai.ui.StreamChunk

/** State for one provider stream subscription; reset before each retry/step attempt. */
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

/** Observe raw provider chunks before rendering/coalescing can buffer or discard them. */
internal fun Flow<StreamChunk>.trackGenerationStreamProgress(
    progress: GenerationStreamProgress,
    onMeaningfulOutput: () -> Unit = {},
    onNonMeaningfulCompletion: () -> Unit = {},
): Flow<StreamChunk> = onEach { chunk ->
    if (progress.record(chunk)) onMeaningfulOutput()
}.onCompletion { cause ->
    // Only a successful empty completion is synthesized as a retryable failure. Never replace
    // an upstream/downstream exception or cancellation with the empty-stream error.
    if (cause == null) {
        if (shouldReportEmptyGenerationStream(progress.receivedAnyChunk)) {
            throw IOException("Model stream closed without meaningful output")
        }
        if (!progress.receivedMeaningfulOutput) onNonMeaningfulCompletion()
    }
}
