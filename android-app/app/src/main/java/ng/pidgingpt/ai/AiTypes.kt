package ng.pidgingpt.ai

import ng.pidgingpt.inference.ChatTurn

/** How ViNT chooses between the phone's own model and an online service. Pure Kotlin: unit-testable on any JVM. */
enum class AiMode { AUTOMATIC, OFFLINE_ONLY, ONLINE_PREFERRED }

/** What the user wants ViNT to answer in. AUTOMATIC = estimate from the message (see [LanguageRouter]). */
enum class ResponseLanguage { AUTOMATIC, ENGLISH, PIDGIN }

enum class ProviderKind {
    LOCAL_ENGLISH, LOCAL_PIDGIN, ONLINE;
    val isLocal: Boolean get() = this != ONLINE
}

enum class TransportKind { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

/**
 * What the OS says about the default network. [validated] means Android itself probed the network and found working internet
 * (NET_CAPABILITY_VALIDATED), which is false on captive portals, dead Wi-Fi and DNS failures. "Mobile data is on" is never used.
 */
data class NetworkState(val known: Boolean, val connected: Boolean, val validated: Boolean, val transport: TransportKind) {
    val usable: Boolean get() = known && connected && validated

    companion object {
        val UNKNOWN = NetworkState(false, false, false, TransportKind.NONE)
        val NONE = NetworkState(true, false, false, TransportKind.NONE)
        fun validated(t: TransportKind) = NetworkState(true, true, true, t)
        fun unvalidated(t: TransportKind) = NetworkState(true, true, false, t)
    }
}

enum class UnavailableReason {
    NOT_INSTALLED, LOAD_FAILED, NOT_CONFIGURED, NO_CONSENT, NO_NETWORK, NETWORK_NOT_VALIDATED, COOLING_DOWN, OFFLINE_ONLY
}

sealed class Availability {
    object Available : Availability()
    data class Unavailable(val reason: UnavailableReason, val message: String) : Availability()
}

/** Why a provider could not answer. [fallbackEligible] = another provider may be tried, but only if nothing was streamed yet. */
sealed class AiFailure(val message: String, val fallbackEligible: Boolean) {
    class Network(m: String = "The network connection failed.") : AiFailure(m, true)
    class Timeout(m: String = "The online service took too long to answer.") : AiFailure(m, true)
    class ServerUnavailable(m: String = "The online service is temporarily unavailable.") : AiFailure(m, true)
    class RateLimited(m: String = "The online service is rate limiting requests.") : AiFailure(m, true)
    class QuotaExhausted(m: String = "The online account has no quota left.") : AiFailure(m, true)
    class InvalidCredentials(m: String = "The online service rejected the credentials.") : AiFailure(m, true)
    class BadRequest(m: String = "The online service rejected the request.") : AiFailure(m, true)
    class NotConfigured(m: String = "Online AI is not configured.") : AiFailure(m, true)
    class NotInstalled(m: String = "No local model is installed.") : AiFailure(m, true)
    class LoadFailed(m: String = "The local model could not be loaded.") : AiFailure(m, true)
    class ContextTooLong(m: String = "The conversation is too long for this model.") : AiFailure(m, false)
    class NativeError(m: String = "The on-device runtime failed.") : AiFailure(m, false)
    class Unexpected(m: String = "Something unexpected went wrong.") : AiFailure(m, false)
}

class AiException(val failure: AiFailure) : Exception(failure.message)

/** Result of a language estimate or an explicit choice. [response] is never AUTOMATIC. */
data class LanguageDecision(val response: ResponseLanguage, val explicit: Boolean, val mixed: Boolean = false, val confidence: Float = 1f)

/**
 * One chat request. [history] holds the conversation WITHOUT a system prompt (each provider writes its own), ending with the new user turn,
 * so the same history stays coherent whichever provider answers. [id] is unique per user send and is the duplicate guard.
 */
data class AiRequest(
    val id: String, val history: List<ChatTurn>, val maxNewTokens: Int, val temperature: Float,
    val language: LanguageDecision, val voice: Boolean = false,
    val allowOnline: Boolean = true,          // false for voice turns unless the user allowed online replies in voice mode
    val forceProvider: ProviderKind? = null,  // used by "retry offline"
)

data class ProviderStats(val units: Int, val millis: Long, val unit: String, val cancelled: Boolean = false) {
    val perSecond: Double get() = if (millis == 0L) 0.0 else units * 1000.0 / millis
}

data class ProviderSwitch(val from: ProviderKind, val to: ProviderKind, val because: String)

sealed class AiOutcome {
    data class Completed(val provider: ProviderKind, val providerId: String, val stats: ProviderStats, val switches: List<ProviderSwitch>) : AiOutcome()
    data class Failed(val failure: AiFailure, val provider: ProviderKind?, val partial: Boolean, val switches: List<ProviderSwitch>) : AiOutcome()
    data class Cancelled(val provider: ProviderKind?, val partial: Boolean) : AiOutcome()
    /** Nothing usable. [advice] says how to fix it. The user's message is NOT consumed. */
    data class Rejected(val advice: String, val reasons: List<UnavailableReason>) : AiOutcome()
    object Duplicate : AiOutcome()
    object Busy : AiOutcome()
}

data class ProviderStatus(val label: String, val selected: ProviderKind?, val willSendOnline: Boolean, val detail: String? = null)
