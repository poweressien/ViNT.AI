package ng.pidgingpt.ai

import ng.pidgingpt.data.Compat
import ng.pidgingpt.data.GgufInfo
import ng.pidgingpt.data.ModelInfo
import ng.pidgingpt.inference.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelProfileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun gguf(arch: String = "qwen2") = GgufInfo(3, 0, 1, arch, "Test", "apache-2.0", "Q4_K_M", 4096, 1024, 24, true)
    private fun info(name: String, arch: String = "qwen2") = ModelInfo(File(tmp.root, name), 1000, gguf(arch), null)

    @Test fun installingAPidginNamedFileNeverMakesItPidginCapable() {
        assertFalse(ModelProfile("p", "Pidgin", "pidgin.gguf", role = ModelRole.PIDGIN).pidginEligible)
        assertFalse(ModelProfile("p", "Pidgin", "pidgin.gguf", role = ModelRole.PIDGIN, markedAvailable = true, pidginEvidence = Evidence.OBSERVED).pidginEligible)
        assertFalse(ModelProfile("p", "Pidgin", "pidgin.gguf", role = ModelRole.PIDGIN, markedAvailable = true, pidginEvidence = Evidence.UNTESTED).pidginEligible) }
    @Test fun evaluatedOrUserConfiguredAndMarkedAvailableIsEligible() {
        assertTrue(ModelProfile("p", "P", "p.gguf", role = ModelRole.PIDGIN, markedAvailable = true, pidginEvidence = Evidence.EVALUATED).pidginEligible)
        assertTrue(ModelProfile("p", "P", "p.gguf", role = ModelRole.PIDGIN, markedAvailable = true, pidginEvidence = Evidence.USER_CONFIGURED).pidginEligible)
        assertFalse(ModelProfile("p", "P", "p.gguf", role = ModelRole.ENGLISH, markedAvailable = true, pidginEvidence = Evidence.EVALUATED).pidginEligible)
        assertFalse(ModelProfile("p", "P", "p.gguf", role = ModelRole.PIDGIN, markedAvailable = false, pidginEvidence = Evidence.EVALUATED).pidginEligible) }
    @Test fun registryRoundTripsAndSurvivesCorruption() {
        val f = File(tmp.root, "reg.json"); val reg = ModelRegistry(f)
        val p = ModelProfile("q", "Qwen \"1.5B\"\n", "q.gguf", architecture = "qwen2", license = "Apache-2.0", provenance = "Downloaded", sha256 = "ab", sizeBytes = 5, pidginEvidence = Evidence.OBSERVED)
        reg.upsert(p); assertEquals(p, ModelRegistry(f).get("q.gguf"))
        reg.upsert(p.copy(runtime = RuntimeStatus.LOADED_OK)); assertEquals(1, reg.all().size); assertEquals(RuntimeStatus.LOADED_OK, reg.all().single().runtime)
        f.writeText("{not json"); assertTrue(ModelRegistry(f).all().isEmpty()) }

    private class FakeEngine(var failLoad: Boolean = false) : InferenceEngine {
        var loads = 0; var loadedPath: String? = null
        override fun load(modelPath: String, nCtx: Int, nThreads: Int): Result<Unit> { loads++; return if (failLoad) Result.failure(IllegalStateException("boom")) else { loadedPath = modelPath; Result.success(Unit) } }
        override fun generate(turns: List<ChatTurn>, maxNewTokens: Int, temperature: Float, onToken: (String) -> Unit): Result<GenStats> { onToken("<think>x</think>Hello"); return Result.success(GenStats(2, 10)) }
        override fun cancel() {}
        override fun unload() { loadedPath = null }
    }

    private fun host(models: List<ModelInfo>, engine: FakeEngine, reg: ModelRegistry, ok: Boolean = true) =
        LocalModelHost(engine, { models }, { _, _ -> Compat(ok, if (ok) null else "Too big for free memory right now") }, reg, { 2048 }, { 2 }, { null })

    @Test fun oneEngineNeverLoadsTheSameModelTwiceAndRecordsRuntimeTruth() {
        val reg = ModelRegistry(File(tmp.root, "r.json")); val e = FakeEngine(); val m = info("en.gguf"); val h = host(listOf(m), e, reg)
        assertTrue(h.ensureLoaded(m).isSuccess); assertTrue(h.ensureLoaded(m).isSuccess); assertEquals(1, e.loads)
        assertEquals(RuntimeStatus.LOADED_OK, reg.get("en.gguf")!!.runtime) }
    @Test fun loadFailureIsReportedAndRecordedNotHidden() {
        val reg = ModelRegistry(File(tmp.root, "r.json")); val e = FakeEngine(failLoad = true); val m = info("en.gguf"); val h = host(listOf(m), e, reg)
        assertTrue(h.ensureLoaded(m).isFailure); assertTrue(h.state.value is EngineState.Failed); assertEquals(RuntimeStatus.LOAD_FAILED, reg.get("en.gguf")!!.runtime) }
    @Test fun insufficientMemoryBlocksTheLoadWithoutCallingTheEngine() {
        val reg = ModelRegistry(File(tmp.root, "r.json")); val e = FakeEngine(); val m = info("en.gguf"); val h = host(listOf(m), e, reg, ok = false)
        assertTrue(h.ensureLoaded(m).isFailure); assertEquals(0, e.loads) }
    @Test fun localProviderSelectsByRoleAndOnlyEligiblePidginModels() {
        val reg = ModelRegistry(File(tmp.root, "r.json")); val en = info("en.gguf"); val pg = info("pg.gguf"); val e = FakeEngine(); val h = host(listOf(en, pg), e, reg)
        val pidgin = LocalProvider(ProviderKind.LOCAL_PIDGIN, h, { 2048 }, { ng.pidgingpt.data.LanguageMode.NATURAL_PIDGIN }, { "" })
        val english = LocalProvider(ProviderKind.LOCAL_ENGLISH, h, { 2048 }, { ng.pidgingpt.data.LanguageMode.NATURAL_PIDGIN }, { "" })
        assertTrue(pidgin.availability() is Availability.Unavailable)
        reg.upsert(h.defaultProfile(pg).copy(role = ModelRole.PIDGIN, markedAvailable = true, pidginEvidence = Evidence.USER_CONFIGURED))
        assertEquals(Availability.Available, pidgin.availability()); assertEquals("pg.gguf", pidgin.modelFileName); assertEquals("en.gguf", english.modelFileName) }
    @Test fun localProviderFiltersThinkBlocksAndReturnsStats() {
        val reg = ModelRegistry(File(tmp.root, "r.json")); val m = info("en.gguf"); val h = host(listOf(m), FakeEngine(), reg)
        val p = LocalProvider(ProviderKind.LOCAL_ENGLISH, h, { 2048 }, { ng.pidgingpt.data.LanguageMode.NATURAL_PIDGIN }, { "" })
        val sb = StringBuilder(); val st = p.generate(req(), { sb.append(it) }); assertEquals("Hello", sb.toString()); assertEquals("tokens", st.unit) }
    @Test fun localProviderWithNoModelThrowsNotInstalledNeverInventsText() {
        val reg = ModelRegistry(File(tmp.root, "r.json")); val h = host(emptyList(), FakeEngine(), reg)
        val p = LocalProvider(ProviderKind.LOCAL_ENGLISH, h, { 2048 }, { ng.pidgingpt.data.LanguageMode.NATURAL_PIDGIN }, { "" })
        val sb = StringBuilder(); try { p.generate(req(), { sb.append(it) }); fail() } catch (e: AiException) { assertTrue(e.failure is AiFailure.NotInstalled) }; assertEquals("", sb.toString()) }
}
