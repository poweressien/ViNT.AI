package ng.pidgingpt.ai

import java.util.concurrent.atomic.AtomicReference

data class AiPolicy(
    val mode: AiMode, val preferred: ProviderKind?, val onlineConsent: Boolean, val response: ResponseLanguage,
)

data class ProviderPlan(val order: List<AIProvider>, val skipped: List<Pair<ProviderKind, Availability.Unavailable>>)

/**
 * The only thing the UI calls to answer a message. Pure Kotlin (no Android classes) so every rule below is unit-tested with fake providers.
 *
 * Rules (see docs/HYBRID_AI.md):
 *  - The plan is fixed when the request is submitted. Connectivity changes later never restart or double-send a request.
 *  - OFFLINE_ONLY: the online provider is not even asked for availability, and is never in the plan.
 *  - A provider that fails BEFORE streaming anything may fall back to the next one. After text has streamed, there is no silent re-send:
 *    the partial reply is kept and the user chooses (retry / retry offline).
 *  - One request at a time; every request id is accepted once.
 */
class AIProviderManager(
    private val providers: List<AIProvider>,
    private val network: () -> NetworkState,
    private val policy: () -> AiPolicy,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cooldownMs: Long = 30_000L,
) {
    private val inFlight = AtomicReference<String?>(null)
    private val seen = LinkedHashSet<String>()
    @Volatile private var current: AIProvider? = null
    @Volatile private var cancelRequested = false
    @Volatile private var onlineBlockedUntil = 0L

    val busy: Boolean get() = inFlight.get() != null

    // ------------------------------------------------------------------ planning

    fun plan(language: LanguageDecision, allowOnline: Boolean = true, force: ProviderKind? = null): ProviderPlan {
        val pol = policy(); val net = network()
        val skipped = ArrayList<Pair<ProviderKind, Availability.Unavailable>>()
        fun provider(k: ProviderKind): AIProvider? = providers.firstOrNull { it.kind == k }
        fun usable(k: ProviderKind): AIProvider? {
            val p = provider(k) ?: run { skipped += k to Availability.Unavailable(UnavailableReason.NOT_INSTALLED, "Not installed."); return null }
            return when (val a = p.availability()) { Availability.Available -> p; is Availability.Unavailable -> { skipped += k to a; null } }
        }
        fun onlineUsable(): AIProvider? {
            fun no(r: UnavailableReason, m: String): AIProvider? { skipped += ProviderKind.ONLINE to Availability.Unavailable(r, m); return null }
            if (pol.mode == AiMode.OFFLINE_ONLY || !allowOnline) return no(UnavailableReason.OFFLINE_ONLY, "Online AI is off for this request.")
            if (!pol.onlineConsent) return no(UnavailableReason.NO_CONSENT, "You have not allowed sending messages online.")
            if (clock() < onlineBlockedUntil) return no(UnavailableReason.COOLING_DOWN, "The online service failed recently.")
            if (!net.known || !net.connected) return no(UnavailableReason.NO_NETWORK, "No internet connection.")
            if (!net.validated) return no(UnavailableReason.NETWORK_NOT_VALIDATED, "This network has no working internet (sign-in page, no data, or DNS problem).")
            return usable(ProviderKind.ONLINE)
        }

        val wantsPidgin = language.response == ResponseLanguage.PIDGIN
        val locals = ArrayList<AIProvider>()
        if (wantsPidgin) usable(ProviderKind.LOCAL_PIDGIN)?.let { locals += it }
        usable(ProviderKind.LOCAL_ENGLISH)?.let { locals += it }
        if (locals.isEmpty() && !wantsPidgin) usable(ProviderKind.LOCAL_PIDGIN)?.let { locals += it }   // last resort for English: only a Pidgin model that is installed AND marked available
        val online = onlineUsable()

        val base: List<AIProvider> = when (pol.mode) {
            AiMode.OFFLINE_ONLY -> locals
            AiMode.ONLINE_PREFERRED -> listOfNotNull(online) + locals
            // AUTOMATIC: a verified local Pidgin model answers Pidgin requests first; otherwise online when usable, else local.
            AiMode.AUTOMATIC -> if (wantsPidgin && locals.firstOrNull()?.kind == ProviderKind.LOCAL_PIDGIN) locals + listOfNotNull(online) else listOfNotNull(online) + locals
        }
        val wanted = force ?: pol.preferred
        val ordered = if (wanted == null) base else {
            val extra = if (wanted.isLocal && base.none { it.kind == wanted }) listOfNotNull(usable(wanted)) else emptyList()
            val first = (extra + base).filter { it.kind == wanted }
            if (force != null) first else first + (extra + base).filter { it.kind != wanted }
        }
        return ProviderPlan(ordered.distinctBy { it.kind }, skipped)
    }

    /** What the status line shows: the provider the NEXT message would use, never just "Wi-Fi connected". */
    fun status(language: LanguageDecision? = null, allowOnline: Boolean = true): ProviderStatus {
        val pol = policy(); val net = network()
        val lang = language ?: LanguageDecision(if (pol.response == ResponseLanguage.PIDGIN) ResponseLanguage.PIDGIN else ResponseLanguage.ENGLISH, explicit = true)
        val p = plan(lang, allowOnline)
        val first = p.order.firstOrNull()
        val onlineWanted = pol.mode != AiMode.OFFLINE_ONLY && allowOnline && pol.onlineConsent
        val cooling = onlineWanted && clock() < onlineBlockedUntil && net.usable
        return when {
            first == null -> ProviderStatus("Local model not installed", null, false,
                FixAdvice.build(p.skipped))
            first.kind == ProviderKind.ONLINE -> ProviderStatus("Online \u2014 Cloud AI", ProviderKind.ONLINE, true, "Your next message will be sent to the online service.")
            !net.known && onlineWanted && providers.any { it.kind == ProviderKind.ONLINE && it.availability() == Availability.Available } ->
                ProviderStatus("Connecting\u2026", first.kind, false, "Checking the network. Local model is ready meanwhile.")
            cooling -> ProviderStatus("Online service unavailable \u2014 switching to offline", first.kind, false, "Using the local model for now.")
            first.kind == ProviderKind.LOCAL_PIDGIN -> ProviderStatus("Offline \u2014 Local Pidgin model", first.kind, false)
            else -> ProviderStatus("Offline \u2014 Local English model", first.kind, false)
        }
    }

    // ------------------------------------------------------------------ execution

    fun submit(request: AiRequest, onPiece: (String) -> Unit, onProvider: (ProviderKind) -> Unit = {}, onSwitch: (ProviderSwitch) -> Unit = {}): AiOutcome {
        synchronized(seen) {
            if (!seen.add(request.id)) return AiOutcome.Duplicate
            if (seen.size > 256) { val it = seen.iterator(); it.next(); it.remove() }
        }
        if (!inFlight.compareAndSet(null, request.id)) { synchronized(seen) { seen.remove(request.id) }; return AiOutcome.Busy }
        cancelRequested = false
        try {
            val plan = plan(request.language, request.allowOnline, request.forceProvider)
            if (plan.order.isEmpty()) {
                synchronized(seen) { seen.remove(request.id) }              // nothing was attempted: the same message may be sent again once fixed
                return AiOutcome.Rejected(FixAdvice.build(plan.skipped), plan.skipped.map { it.second.reason })
            }
            val switches = ArrayList<ProviderSwitch>(); var emitted = 0
            val counting: (String) -> Unit = { if (it.isNotEmpty()) emitted++; onPiece(it) }
            var last: AiFailure? = null; var lastKind: ProviderKind? = null
            for ((i, p) in plan.order.withIndex()) {
                if (cancelRequested) return AiOutcome.Cancelled(lastKind, emitted > 0)
                current = p; onProvider(p.kind)
                try {
                    return AiOutcome.Completed(p.kind, p.id, p.generate(request, counting), switches)
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError) throw t
                    if (cancelRequested) return AiOutcome.Cancelled(p.kind, emitted > 0)
                    val f = (t as? AiException)?.failure ?: AiFailure.Unexpected("Unexpected error: ${t.javaClass.simpleName}")
                    if (p.kind == ProviderKind.ONLINE) onlineBlockedUntil = if (f is AiFailure.InvalidCredentials || f is AiFailure.NotConfigured || f is AiFailure.QuotaExhausted) Long.MAX_VALUE else clock() + cooldownMs
                    last = f; lastKind = p.kind
                    if (emitted > 0) return AiOutcome.Failed(f, p.kind, partial = true, switches = switches)   // never silently re-send after text streamed
                    val next = plan.order.getOrNull(i + 1)
                    if (next != null && f.fallbackEligible) { val sw = ProviderSwitch(p.kind, next.kind, f.message); switches += sw; onSwitch(sw); continue }
                    return AiOutcome.Failed(f, p.kind, partial = false, switches = switches)
                }
            }
            return AiOutcome.Failed(last ?: AiFailure.Unexpected(), lastKind, false, switches)
        } finally { current = null; inFlight.set(null) }
    }

    fun cancel() { cancelRequested = true; val id = inFlight.get(); if (id != null) current?.cancel(id) }

    /** Call when settings or credentials change, so a past credential failure does not keep the online provider blocked. */
    fun resetOnline() { onlineBlockedUntil = 0L }

    /** Call when the network state changes. A transient failure cooldown ends when connectivity changes; a credential failure does not. */
    fun onNetworkChanged() { if (onlineBlockedUntil != Long.MAX_VALUE) onlineBlockedUntil = 0L }
}

object FixAdvice {
    fun build(skipped: List<Pair<ProviderKind, Availability.Unavailable>>): String {
        val local = skipped.filter { it.first.isLocal }.map { it.second }
        val online = skipped.firstOrNull { it.first == ProviderKind.ONLINE }?.second
        val localFix = when {
            local.any { it.reason == UnavailableReason.LOAD_FAILED } -> "The local model could not be loaded (${local.first { it.reason == UnavailableReason.LOAD_FAILED }.message}). Open Model, free some memory, or pick a smaller model."
            else -> "Install the local English model: open Model, then Download (needs internet once) or Import a .gguf file."
        }
        val onlineFix = when (online?.reason) {
            UnavailableReason.OFFLINE_ONLY -> "Online AI is switched off (Offline Only), so nothing is sent online."
            UnavailableReason.NO_CONSENT -> "To use online AI, allow it in AI settings."
            UnavailableReason.NOT_CONFIGURED -> "Online AI is not set up: ${online.message}"
            UnavailableReason.NO_NETWORK -> "There is no internet connection right now."
            UnavailableReason.NETWORK_NOT_VALIDATED -> "This network has no working internet. Open a sign-in page or switch network."
            UnavailableReason.COOLING_DOWN -> "The online service failed a moment ago. Try again shortly."
            else -> "Online AI is not available."
        }
        return "ViNT cannot answer yet. $localFix $onlineFix Your message was kept."
    }
}
