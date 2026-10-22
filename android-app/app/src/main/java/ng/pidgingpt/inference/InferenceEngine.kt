package ng.pidgingpt.inference

data class ChatTurn(val role: String, val content: String)  // role: system | user | assistant

data class GenStats(val tokens: Int, val millis: Long, val cancelled: Boolean = false) {
    val tokensPerSec: Double get() = if (millis == 0L) 0.0 else tokens * 1000.0 / millis
}

/** Model lifecycle. Ready is only ever set AFTER the native runtime has actually initialised the model. */
sealed class EngineState {
    object NotInstalled : EngineState()                 // no .gguf file imported yet
    object Unloaded : EngineState()                     // a file exists but is not loaded
    object Loading : EngineState()
    data class Ready(val modelName: String, val nCtx: Int) : EngineState()
    data class Failed(val message: String) : EngineState()
}

/** Generation lifecycle (separate from model lifecycle). */
sealed class GenState {
    object Idle : GenState()
    object Generating : GenState()
    data class Cancelled(val tokens: Int) : GenState()
    data class Failed(val message: String) : GenState()
}

interface InferenceEngine {
    fun load(modelPath: String, nCtx: Int, nThreads: Int): Result<Unit>
    /** Streams generated text to [onToken]. Honours [cancel]; a cancelled run returns stats with cancelled=true. */
    fun generate(turns: List<ChatTurn>, maxNewTokens: Int, temperature: Float, onToken: (String) -> Unit): Result<GenStats>
    fun cancel()
    fun unload()
}
