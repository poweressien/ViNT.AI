package ng.pidgingpt.ai

data class ProviderCapabilities(
    val displayName: String, val streaming: Boolean,
    /** True only for providers that transmit message text off the device. Offline Only must never select one. */
    val sendsDataOffDevice: Boolean,
    val languages: Set<ResponseLanguage>,
)

/** One way of answering a chat request. The UI never talks to a provider directly: it goes through [AIProviderManager]. */
interface AIProvider {
    val kind: ProviderKind
    val id: String
    val capabilities: ProviderCapabilities
    /** Cheap and side-effect free (no network, no model loading). */
    fun availability(): Availability
    /**
     * Blocking; call off the main thread. Streams text to [onPiece]. Returns stats on success or when cancelled.
     * Failures are thrown as [AiException]; a provider never invents text when it fails.
     */
    fun generate(request: AiRequest, onPiece: (String) -> Unit): ProviderStats
    /** Safe from any thread. A no-op if [requestId] is not the active request. */
    fun cancel(requestId: String)
}

interface LocalAIProvider : AIProvider { val modelFileName: String? }

interface OnlineAIProvider : AIProvider {
    /** Sends one tiny test message that contains NO chat content. Returns a human-readable result. */
    fun testConnection(): Result<String>
}
