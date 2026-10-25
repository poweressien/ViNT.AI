package ng.pidgingpt.ai

import ng.pidgingpt.inference.ChatTurn
import org.junit.Assert.*
import org.junit.Test

class AIProviderManagerTest {
    private fun kind(o: AiOutcome) = (o as AiOutcome.Completed).provider

    // ---- automatic selection, Wi-Fi, mobile data, validation
    @Test fun automaticWithValidatedInternetUsesOnline() { val r = Rig(); assertEquals(ProviderKind.ONLINE, kind(r.run().first)) }
    @Test fun automaticWithoutInternetUsesLocal() { val r = Rig(net = NetworkState.NONE); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first)); assertEquals(0, r.online.calls) }
    @Test fun wifiWithValidatedInternetIsOnline() { assertEquals(ProviderKind.ONLINE, kind(Rig(net = NetworkState.validated(TransportKind.WIFI)).run().first)) }
    @Test fun mobileDataWithValidatedInternetIsOnline() { assertEquals(ProviderKind.ONLINE, kind(Rig(net = NetworkState.validated(TransportKind.CELLULAR)).run().first)) }
    @Test fun connectedNetworkWithoutValidatedInternetUsesLocal() {
        val r = Rig(net = NetworkState.unvalidated(TransportKind.WIFI)); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first)); assertEquals(0, r.online.calls) }
    @Test fun unknownNetworkIsNotTreatedAsOnline() { val r = Rig(net = NetworkState.UNKNOWN); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first)) }
    @Test fun onlineNeedsConsent() { val r = Rig(consent = false); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first)); assertEquals(0, r.online.calls) }
    @Test fun unconfiguredOnlineIsSkipped() {
        val r = Rig(online = FakeProvider(ProviderKind.ONLINE, Availability.Unavailable(UnavailableReason.NOT_CONFIGURED, "no url"))); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first)) }

    // ---- Offline Only
    @Test fun offlineOnlyNeverSelectsOrEvenQueriesOnline() {
        val r = Rig(mode = AiMode.OFFLINE_ONLY); repeat(3) { i -> assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run(req("id$i")).first)) }
        assertEquals(0, r.online.calls); assertEquals(0, r.online.availabilityCalls)
        assertFalse(r.manager.status().willSendOnline) }
    @Test fun offlineOnlyBeatsForceAndPreferred() {
        val r = Rig(mode = AiMode.OFFLINE_ONLY, preferred = ProviderKind.ONLINE); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first))
        val o = r.run(req("x", force = ProviderKind.ONLINE)).first; assertTrue(o is AiOutcome.Rejected); assertEquals(0, r.online.calls) }
    @Test fun offlineOnlyIgnoresVoiceOnlineSetting() { val r = Rig(mode = AiMode.OFFLINE_ONLY); r.run(req(voice = true, allowOnline = true)); assertEquals(0, r.online.calls) }
    @Test fun voiceTurnsStayOfflineWhenOnlineNotAllowedForVoice() { val r = Rig(); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run(req(voice = true, allowOnline = false)).first)); assertEquals(0, r.online.calls) }

    // ---- fallback
    @Test fun cloudTimeoutFallsBackToLocalBeforeAnyText() {
        val r = Rig(online = FakeProvider(ProviderKind.ONLINE, script = failing(AiFailure.Timeout())))
        val (o, text) = r.run(); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(o)); assertEquals(1, (o as AiOutcome.Completed).switches.size); assertEquals("hi from LOCAL_ENGLISH", text) }
    @Test fun serverErrorFallsBack() { val r = Rig(online = FakeProvider(ProviderKind.ONLINE, script = failing(AiFailure.ServerUnavailable()))); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first)) }
    @Test fun networkErrorFallsBack() { val r = Rig(online = FakeProvider(ProviderKind.ONLINE, script = failing(AiFailure.Network()))); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first)) }
    @Test fun onlinePreferredFallsBackLocally() { val r = Rig(mode = AiMode.ONLINE_PREFERRED, online = FakeProvider(ProviderKind.ONLINE, script = failing(AiFailure.RateLimited()))); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first)) }
    @Test fun onlineFailureStartsCooldownThenRecoversOnNetworkChange() {
        val r = Rig(online = FakeProvider(ProviderKind.ONLINE, script = failing(AiFailure.ServerUnavailable()))); r.run(req("a"))
        r.run(req("b")); assertEquals(1, r.online.calls)                    // cooling down: not asked again
        assertTrue(r.manager.status().label.startsWith("Online service unavailable"))
        r.manager.onNetworkChanged(); r.run(req("c")); assertEquals(2, r.online.calls) }
    @Test fun badCredentialsStayBlockedUntilSettingsChange() {
        val r = Rig(online = FakeProvider(ProviderKind.ONLINE, script = failing(AiFailure.InvalidCredentials()))); r.run(req("a")); r.manager.onNetworkChanged(); r.run(req("b")); assertEquals(1, r.online.calls)
        r.manager.resetOnline(); r.run(req("c")); assertEquals(2, r.online.calls) }

    // ---- network loss during a request: no silent restart, no duplicate send
    @Test fun networkLossAfterTextStreamedKeepsPartialAndDoesNotResend() {
        val on = FakeProvider(ProviderKind.ONLINE, script = { _, emit -> emit("Hello wor"); throw AiException(AiFailure.Network("lost")) })
        val r = Rig(online = on); val (o, text) = r.run()
        assertTrue(o is AiOutcome.Failed); assertTrue((o as AiOutcome.Failed).partial); assertEquals("Hello wor", text)
        assertEquals(1, on.calls); assertEquals(0, r.english.calls) }
    @Test fun connectivityChangeMidRequestDoesNotChangeTheRunningPlan() {
        val r = Rig(); r.online.script = { _, emit -> r.net = NetworkState.NONE; emit("still online"); ProviderStats(1, 1, "chunks") }
        val (o, text) = r.run(); assertEquals(ProviderKind.ONLINE, kind(o)); assertEquals("still online", text); assertEquals(0, r.english.calls) }

    // ---- missing / failing local model
    @Test fun missingLocalModelAndNoOnlineIsRejectedWithAdviceAndNoSideEffects() {
        val r = Rig(net = NetworkState.NONE, english = FakeProvider(ProviderKind.LOCAL_ENGLISH, Availability.Unavailable(UnavailableReason.NOT_INSTALLED, "none")))
        val (o, text) = r.run(); assertTrue(o is AiOutcome.Rejected); assertTrue((o as AiOutcome.Rejected).advice.contains("Model")); assertEquals("", text)
        assertTrue(r.manager.status().label == "Local model not installed")
        r.english.avail = Availability.Available; assertTrue(r.run().first is AiOutcome.Completed) }    // same request id may be retried after the fix
    @Test fun localLoadFailureWithNoFallbackFailsClearly() {
        val r = Rig(mode = AiMode.OFFLINE_ONLY, english = FakeProvider(ProviderKind.LOCAL_ENGLISH, script = failing(AiFailure.LoadFailed("not enough memory"))))
        val (o, text) = r.run(); assertTrue(o is AiOutcome.Failed); assertEquals("", text); assertEquals("not enough memory", (o as AiOutcome.Failed).failure.message) }
    @Test fun nativeErrorDoesNotFallBackSilently() {
        val r = Rig(mode = AiMode.OFFLINE_ONLY, english = FakeProvider(ProviderKind.LOCAL_ENGLISH, script = failing(AiFailure.NativeError("decode"))))
        assertTrue(r.run().first is AiOutcome.Failed) }

    // ---- Pidgin provider registration (future model)
    @Test fun ineligiblePidginProviderIsNeverUsed() { val r = Rig(mode = AiMode.OFFLINE_ONLY); r.run(req(lang = ResponseLanguage.PIDGIN)); assertEquals(0, r.pidgin.calls); assertEquals(1, r.english.calls) }
    @Test fun registeredPidginProviderAnswersPidginRequestsFirstEvenWhenOnlineIsUsable() {
        val r = Rig(); r.pidgin.avail = Availability.Available
        assertEquals(ProviderKind.LOCAL_PIDGIN, kind(r.run(req("p", lang = ResponseLanguage.PIDGIN)).first)); assertEquals(0, r.online.calls)
        assertEquals(ProviderKind.ONLINE, kind(r.run(req("e", lang = ResponseLanguage.ENGLISH)).first)) }
    @Test fun pidginLoadFailureFallsBackToEnglishModel() {
        val r = Rig(mode = AiMode.OFFLINE_ONLY); r.pidgin.avail = Availability.Available; r.pidgin.script = failing(AiFailure.LoadFailed("bad file"))
        val o = r.run(req(lang = ResponseLanguage.PIDGIN)).first; assertEquals(ProviderKind.LOCAL_ENGLISH, kind(o)) }
    @Test fun userOverrideSelectsModelFirst() {
        val r = Rig(preferred = ProviderKind.LOCAL_ENGLISH); assertEquals(ProviderKind.LOCAL_ENGLISH, kind(r.run().first))
        r.pidgin.avail = Availability.Available; r.preferred = ProviderKind.LOCAL_PIDGIN; assertEquals(ProviderKind.LOCAL_PIDGIN, kind(r.run(req("z")).first)) }

    // ---- duplicates, history, cancellation
    @Test fun sameRequestIdIsNeverProcessedTwice() { val r = Rig(); r.run(req("dup")); assertTrue(r.run(req("dup")).first is AiOutcome.Duplicate); assertEquals(1, r.online.calls) }
    @Test fun secondRequestWhileBusyIsRejectedNotQueued() {
        val r = Rig(); var inner: AiOutcome? = null
        r.online.script = { _, emit -> inner = r.manager.submit(req("second"), {}); emit("x"); ProviderStats(1, 1, "chunks") }
        r.run(req("first")); assertTrue(inner is AiOutcome.Busy); assertEquals(1, r.online.calls) }
    @Test fun historyIsIdenticalAcrossProviderSwitch() {
        val h = listOf(ChatTurn("user", "hi"), ChatTurn("assistant", "hello"), ChatTurn("user", "wetin dey"))
        val r = Rig(online = FakeProvider(ProviderKind.ONLINE, script = failing(AiFailure.Timeout()))); r.run(req("h", history = h))
        assertEquals(h, r.online.lastRequest!!.history); assertEquals(h, r.english.lastRequest!!.history) }
    @Test fun cancelReachesTheActiveProviderAndSuppressesFallback() {
        val r = Rig(); r.online.script = { q, _ -> r.manager.cancel(); throw AiException(AiFailure.Network("closed")) }
        val o = r.run(req("c")).first; assertTrue(o is AiOutcome.Cancelled); assertEquals("c", r.online.cancelledId); assertEquals(0, r.english.calls) }
    @Test fun statusNamesTheActualProvider() {
        assertEquals("Online \u2014 Cloud AI", Rig().manager.status().label)
        assertEquals("Offline \u2014 Local English model", Rig(net = NetworkState.NONE).manager.status().label)
        assertEquals("Connecting\u2026", Rig(net = NetworkState.UNKNOWN).manager.status().label)
        val p = Rig(mode = AiMode.OFFLINE_ONLY); p.pidgin.avail = Availability.Available; p.preferred = ProviderKind.LOCAL_PIDGIN
        assertEquals("Offline \u2014 Local Pidgin model", p.manager.status(LanguageDecision(ResponseLanguage.PIDGIN, true)).label) }
    @Test fun onlineStatusWarnsThatMessagesAreSentOnline() { assertTrue(Rig().manager.status().willSendOnline) }
}
