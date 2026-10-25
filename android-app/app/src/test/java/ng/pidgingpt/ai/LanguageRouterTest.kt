package ng.pidgingpt.ai

import ng.pidgingpt.data.LanguageMode
import ng.pidgingpt.inference.ChatTurn
import org.junit.Assert.*
import org.junit.Test

class LanguageRouterTest {
    @Test fun explicitEnglishWinsOverPidginText() { val d = LanguageRouter.decide("wetin dey happen abeg", ResponseLanguage.ENGLISH); assertEquals(ResponseLanguage.ENGLISH, d.response); assertTrue(d.explicit) }
    @Test fun explicitPidginWinsOverEnglishText() { val d = LanguageRouter.decide("Please explain photosynthesis to me", ResponseLanguage.PIDGIN); assertEquals(ResponseLanguage.PIDGIN, d.response) }
    @Test fun automaticEnglish() { val d = LanguageRouter.decide("Can you explain how a battery works?", ResponseLanguage.AUTOMATIC); assertEquals(ResponseLanguage.ENGLISH, d.response); assertFalse(d.explicit) }
    @Test fun automaticPidgin() { val d = LanguageRouter.decide("Abeg wetin dey happen for there?", ResponseLanguage.AUTOMATIC); assertEquals(ResponseLanguage.PIDGIN, d.response); assertFalse(d.mixed) }
    @Test fun mixedEnglishAndPidgin() {
        val d = LanguageRouter.decide("Can you please help me explain this report to my team, abeg make e simple", ResponseLanguage.AUTOMATIC); assertEquals(ResponseLanguage.PIDGIN, d.response); assertTrue(d.mixed) }
    @Test fun shortFollowUpInheritsPreviousPidginTurn() {
        val d = LanguageRouter.decide("ok thanks", ResponseLanguage.AUTOMATIC, listOf(ChatTurn("user", "abeg wetin dey happen"), ChatTurn("assistant", "e dey fine"))); assertEquals(ResponseLanguage.PIDGIN, d.response) }
    @Test fun ambiguousEnglishWordsAreNotMistakenForPidgin() { assertEquals(ResponseLanguage.ENGLISH, LanguageRouter.decide("I don't know where to go for lunch", ResponseLanguage.AUTOMATIC).response) }
    @Test fun routerNeverReturnsAutomatic() { for (t in listOf("", "a", "wetin", "hello there my friend")) assertNotEquals(ResponseLanguage.AUTOMATIC, LanguageRouter.decide(t, ResponseLanguage.AUTOMATIC).response) }

    @Test fun englishOnlyModelGetsConfigurablePidginHelp() {
        val d = LanguageDecision(ResponseLanguage.ENGLISH, true)
        assertTrue(PromptBuilder.system(d, ProviderKind.LOCAL_ENGLISH, LanguageMode.NATURAL_PIDGIN, false, false).contains("Nigerian Pidgin"))
        assertTrue(PromptBuilder.system(d, ProviderKind.LOCAL_ENGLISH, LanguageMode.NATURAL_PIDGIN, false, false, "CUSTOM HELP").contains("CUSTOM HELP")) }
    @Test fun mixedPromptDoesNotForceFormalEnglishOrStereotypes() {
        val s = PromptBuilder.system(LanguageDecision(ResponseLanguage.PIDGIN, false, mixed = true), ProviderKind.ONLINE, LanguageMode.NATURAL_PIDGIN, false, false)
        assertTrue(s.contains("same mix")); assertTrue(s.contains("stereotypes")) }
    @Test fun pidginPromptKeepsTheExistingProjectPrompt() { assertTrue(PromptBuilder.system(LanguageDecision(ResponseLanguage.PIDGIN, true), ProviderKind.LOCAL_ENGLISH, LanguageMode.NATURAL_PIDGIN, false, false).startsWith("You be ViNT")) }
    @Test fun qwen3GetsNoThinkAndVoiceGetsSpokenRules() {
        val s = PromptBuilder.system(LanguageDecision(ResponseLanguage.ENGLISH, true), ProviderKind.LOCAL_ENGLISH, LanguageMode.NATURAL_PIDGIN, true, true); assertTrue(s.endsWith("/no_think")); assertTrue(s.contains("spoken")) }
    @Test fun contextTrimmerRespectsLimitsAndKeepsNewestTurn() {
        val h = (1..10).map { ChatTurn(if (it % 2 == 1) "user" else "assistant", "m$it") }
        assertEquals(listOf("m8", "m9", "m10"), ContextTrimmer.trim(h, 3, 10_000).map { it.content })
        assertEquals(listOf("m10"), ContextTrimmer.trim(listOf(ChatTurn("user", "x".repeat(50)), ChatTurn("user", "m10")), 20, 5).map { it.content }) }
}
