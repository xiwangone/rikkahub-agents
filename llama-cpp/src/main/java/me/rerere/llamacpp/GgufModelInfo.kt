package me.rerere.llamacpp

/**
 * The metadata the planner needs, read from a loaded model. Kept as a plain data class with
 * no native handle so the planner stays testable on the JVM.
 *
 * A field that the model does not declare arrives as 0 (or null for [slidingWindow]);
 * [ContextPlanner] treats an incomplete record as untrustworthy and falls back.
 */
data class GgufModelInfo(
    val nLayers: Int,
    val nEmbd: Int,
    val nHeadKv: Int,
    val nEmbdHeadK: Int,
    val nEmbdHeadV: Int,
    val nVocab: Int,
    val nCtxTrain: Int,
    /** Set for sliding-window-attention models such as the Gemma family. */
    val slidingWindow: Int?,
    val weightsBytes: Long,
) {
    /** Every field the KV cache formula reads must be positive to be usable. */
    val isComplete: Boolean
        get() = nLayers > 0 && nEmbd > 0 && nHeadKv > 0 && nEmbdHeadK > 0 &&
            nEmbdHeadV > 0 && nVocab > 0 && nCtxTrain > 0
}

/** KV cache element types, with the bytes-per-element llama.cpp uses for each. */
enum class KvCacheType(val id: String, val bytesPerElement: Double) {
    F16("f16", 2.0),
    Q8_0("q8_0", 1.0625), // 34/32
}

/** One declared tool and the byte cost of its JSON schema in the prompt. */
data class ToolDeclaration(val name: String, val jsonBytes: Int)

/**
 * The sizing decision. [nCtx] is both the context the engine is created with AND the
 * number every prompt budget is computed from. They are the same field on purpose.
 */
data class ContextPlan(
    val nCtx: Int,
    val cacheTypeK: KvCacheType,
    val cacheTypeV: KvCacheType,
    val nBatch: Int,
    val nUBatch: Int,
    val droppedToolNames: List<String>,
    val estimatedBytes: Long,
)
