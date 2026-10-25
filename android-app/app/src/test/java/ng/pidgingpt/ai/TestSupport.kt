package ng.pidgingpt.ai

import ng.pidgingpt.inference.ChatTurn

/** Deterministic stand-in for a provider. No network, no model, no API key. */
class FakeProvider(
    override val kind: ProviderKind,
    var avail: Availability = Availability.Available,
    var script: (AiRequest, (String) -> Unit) -> ProviderStats = { _, emit -> emit("hi from $kind"); ProviderStats(1, 1, "tokens") },
) : AIProvider {
    override val id = "fake-$kind"
    override val capabilities = ProviderCapabilities(kind.name, true, kind == ProviderKind.ONLINE, setOf(ResponseLanguage.ENGLISH))
    var calls = 0; var availabilityCalls = 0; var cancelledId: String? = null; var lastRequest: AiRequest? = null
    override fun availability(): Availability { availabilityCalls++; return avail }
    override fun generate(request: AiRequest, onPiece: (String) -> Unit): ProviderStats { calls++; lastRequest = request; return script(request, onPiece) }
    override fun cancel(requestId: String) { cancelledId = requestId }
}

fun failing(f: AiFailure): (AiRequest, (String) -> Unit) -> ProviderStats = { _, _ -> throw AiException(f) }

fun req(id: String = "r1", text: String = "hello", lang: ResponseLanguage = ResponseLanguage.ENGLISH, voice: Boolean = false, allowOnline: Boolean = true,
        force: ProviderKind? = null, history: List<ChatTurn> = listOf(ChatTurn("user", text))) =
    AiRequest(id, history, 64, 0.7f, LanguageDecision(lang, explicit = true), voice, allowOnline, force)

class Rig(
    mode: AiMode = AiMode.AUTOMATIC, consent: Boolean = true, net: NetworkState = NetworkState.validated(TransportKind.WIFI),
    preferred: ProviderKind? = null, val english: FakeProvider = FakeProvider(ProviderKind.LOCAL_ENGLISH),
    val pidgin: FakeProvider = FakeProvider(ProviderKind.LOCAL_PIDGIN, Availability.Unavailable(UnavailableReason.NOT_INSTALLED, "none")),
    val online: FakeProvider = FakeProvider(ProviderKind.ONLINE),
) {
    var mode = mode; var consent = consent; var net = net; var preferred = preferred; var now = 1_000L
    val manager = AIProviderManager(listOf(english, pidgin, online), { this.net }, { AiPolicy(this.mode, this.preferred, this.consent, ResponseLanguage.AUTOMATIC) }, { now }, 30_000L)
    fun run(r: AiRequest = req()): Pair<AiOutcome, String> { val sb = StringBuilder(); val o = manager.submit(r, { sb.append(it) }); return o to sb.toString() }
}
