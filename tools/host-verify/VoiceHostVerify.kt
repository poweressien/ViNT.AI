// Host-side verification of ViNT's voice layer with REAL engines: sherpa-onnx ASR + TTS (the same models/C++ engine used on Android)
// and the real llama.cpp JNI engine. Test doubles are used ONLY where labelled (ScriptedLlm: deterministic text for event-order/interrupt tests).
// This proves orchestration and engine integration on a Linux x86_64 JVM. It does NOT prove Nigerian Pidgin accuracy, phone performance,
// microphone capture, speaker playback, or anything about the Compose UI.
import ng.pidgingpt.data.LanguageMode
import ng.pidgingpt.inference.*
import ng.pidgingpt.voice.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.random.Random

var passed = 0; var failed = 0
fun check(name: String, ok: Boolean, detail: String = "") { if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  $detail") } }
fun note(s: String) = println("      $s")
inline fun <T> throwsOf(f: () -> T): Throwable? = try { f(); null } catch (t: Throwable) { t }

fun readWav(f: File): Pair<FloatArray, Int> { val ais = AudioSystem.getAudioInputStream(f); val fmt = ais.format; val b = ais.readAllBytes()
    val n = b.size / 2; val out = FloatArray(n) { ((b[2 * it + 1].toInt() shl 8) or (b[2 * it].toInt() and 0xFF)).toShort() / 32768f }; return out to fmt.sampleRate.toInt() }
fun wer(ref: String, hyp: String): Double { fun n(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
    val r = n(ref); val h = n(hyp); var prev = IntArray(h.size + 1) { it }
    for (i in 1..r.size) { val cur = IntArray(h.size + 1); cur[0] = i; for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1); prev = cur }
    return prev[h.size].toDouble() / r.size }
fun pcm16(f: FloatArray) = ShortArray(f.size) { (f[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }

class CollectSink : AudioSink {
    val chunks = CopyOnWriteArrayList<FloatArray>(); @Volatile var stopped = false; val writesAfterStop = AtomicInteger(0); @Volatile var drains = 0
    override fun write(samples: FloatArray, sampleRate: Int): Boolean { if (stopped) { writesAfterStop.incrementAndGet(); return false }; chunks.add(samples); return true }
    override fun stopNow() { stopped = true }
    override fun drain() { drains++ }
    fun seconds(sr: Int) = chunks.sumOf { it.size }.toDouble() / sr
}
class Recorder : VoiceListener {
    val events = CopyOnWriteArrayList<String>(); val machine = VoiceTurnMachine(VoiceState.Idle); @Volatile var transcript = ""; @Volatile var reply = ""; @Volatile var timings: TurnTimings? = null
    @Volatile var cancelled = false; val finished = java.util.concurrent.CountDownLatch(1)
    init { machine.on(VoiceEvent.TalkPressed); machine.on(VoiceEvent.TalkEnded) }   // Idle -> Listening -> Transcribing as the controller would do
    override fun onEvent(e: VoiceEvent) { events.add(e.javaClass.simpleName); machine.on(e) }
    override fun onTranscript(text: String) { transcript = text }
    override fun onReplyPartial(text: String) { reply = text }
    override fun onFinished(replyText: String, timings: TurnTimings?, cancelled: Boolean) { reply = replyText; this.timings = timings; this.cancelled = cancelled; finished.countDown() }
}
/** TEST DOUBLE: deterministic text streamed in small pieces (used only to test turn orchestration, never to simulate AI in the app). */
class ScriptedLlm(private val text: String, private val delayMs: Long = 0) : Llm {
    @Volatile private var cancelled = false
    override fun reply(userText: String, onPiece: (String) -> Unit): Result<String> { cancelled = false; val sb = StringBuilder()
        for (w in text.split(" ")) { if (cancelled) break; val p = "$w "; sb.append(p); onPiece(p); if (delayMs > 0) Thread.sleep(delayMs) }; return Result.success(sb.toString()) }
    override fun cancel() { cancelled = true }
}
/** The mechanics model has only a 512-token window, so a SHORT system prompt is used here; Prompts.system(voice=true) is checked separately. */
class EngineLlm(val e: LlamaEngine, private val maxNew: Int) : Llm {
    override fun reply(userText: String, onPiece: (String) -> Unit): Result<String> { val sb = StringBuilder()
        return e.generate(listOf(ChatTurn("system", "You be ViNT."), ChatTurn("user", userText.takeLast(120))), maxNew, 0.7f) { sb.append(it); onPiece(it) }.map { sb.toString() } }
    override fun cancel() = e.cancel()
}

fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray { val bo = ByteArrayOutputStream(); ZipOutputStream(bo).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }; return bo.toByteArray() }

fun main(args: Array<String>) {
    val zips = File(args[0]); val mech = args[1]; val profilesJson = File(args[2]).readText(); val tmp = File(args[3]).apply { deleteRecursively(); mkdirs() }

    // ---------------------------------------------------------------- A. turn state machine
    run {
        val m = VoiceTurnMachine()
        check("machine: starts Off, mic closed", m.state is VoiceState.Off && !m.state.micOpen)
        check("machine: talk ignored while Off", m.on(VoiceEvent.TalkPressed) is VoiceState.Off)
        check("machine: start -> Idle", m.on(VoiceEvent.StartVoiceMode) is VoiceState.Idle)
        check("machine: press -> Listening (mic open)", m.on(VoiceEvent.TalkPressed).micOpen)
        check("machine: release -> Transcribing (mic closed)", m.on(VoiceEvent.TalkEnded).let { it is VoiceState.Transcribing && !it.micOpen })
        check("machine: transcript -> Thinking", m.on(VoiceEvent.TranscriptReady("x")) is VoiceState.Thinking)
        check("machine: press ignored while Thinking (no overlap)", m.on(VoiceEvent.TalkPressed) is VoiceState.Thinking)
        check("machine: first audio -> Speaking", m.on(VoiceEvent.FirstAudioQueued) is VoiceState.Speaking)
        check("machine: reply done while speaking stays Speaking", m.on(VoiceEvent.ReplyFinished) is VoiceState.Speaking)
        check("machine: speech finished -> Idle", m.on(VoiceEvent.SpeechFinished) is VoiceState.Idle)
        m.on(VoiceEvent.TalkPressed); m.on(VoiceEvent.TalkEnded); m.on(VoiceEvent.TranscriptReady("y")); m.on(VoiceEvent.FirstAudioQueued)
        check("machine: interrupt while speaking -> Idle, no recording", m.on(VoiceEvent.Interrupt).let { it is VoiceState.Idle && !it.micOpen })
        m.on(VoiceEvent.TalkPressed); check("machine: stop voice mode while listening -> Off", m.on(VoiceEvent.StopVoiceMode).let { it is VoiceState.Off && !it.micOpen })
        m.on(VoiceEvent.StartVoiceMode); m.on(VoiceEvent.TalkPressed); m.on(VoiceEvent.TalkEnded); check("machine: nothing heard -> Idle", m.on(VoiceEvent.NothingHeard) is VoiceState.Idle)
        m.on(VoiceEvent.Failed("boom")); check("machine: failure -> Error", m.state is VoiceState.Error)
        check("machine: restart from Error -> Idle", m.on(VoiceEvent.StartVoiceMode) is VoiceState.Idle)
        // random walk invariants
        val rnd = Random(7); val evs = listOf(VoiceEvent.StartVoiceMode, VoiceEvent.StopVoiceMode, VoiceEvent.TalkPressed, VoiceEvent.TalkEnded, VoiceEvent.TranscriptReady("t"), VoiceEvent.NothingHeard,
            VoiceEvent.FirstAudioQueued, VoiceEvent.ReplyFinished, VoiceEvent.SpeechFinished, VoiceEvent.Interrupt, VoiceEvent.Failed("f")); val w = VoiceTurnMachine(); var bad = 0
        repeat(20_000) { val e = evs[rnd.nextInt(evs.size)]; val before = w.state; val after = w.on(e)
            if (after.micOpen && after !is VoiceState.Listening) bad++
            if (e is VoiceEvent.StopVoiceMode && after !is VoiceState.Off) bad++
            if (after is VoiceState.Listening && before !is VoiceState.Listening && before !is VoiceState.Idle) bad++        // mic can only open from Idle
            if (after is VoiceState.Listening && e !is VoiceEvent.TalkPressed && before !is VoiceState.Listening) bad++ }
        check("machine: 20,000 random events keep all invariants", bad == 0, "violations=$bad")
    }

    // ---------------------------------------------------------------- B. VAD
    run {
        val sr = 16000; val rnd = Random(1)
        fun noise(sec: Double, amp: Int) = ShortArray((sec * sr).toInt()) { (rnd.nextInt(-amp, amp + 1)).toShort() }
        fun tone(sec: Double, amp: Double) = ShortArray((sec * sr).toInt()) { (amp * 32767 * Math.sin(2 * Math.PI * 220 * it / sr) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 3 * it / sr))).toInt().toShort() }
        fun run(vad: EnergyVad, pcm: ShortArray): List<Pair<EnergyVad.Event, Int>> { val ev = ArrayList<Pair<EnergyVad.Event, Int>>(); var i = 0
            while (i < pcm.size) { val n = minOf(480, pcm.size - i); val e = vad.accept(pcm.copyOfRange(i, i + n)); if (e != EnergyVad.Event.None) ev.add(e to (i + n) * 1000 / sr); i += n }; return ev }
        val synth = run(EnergyVad(), noise(0.6, 40) + tone(1.0, 0.3) + noise(1.6, 40))
        check("vad: synthetic burst -> SpeechStarted then SpeechEnded", synth.map { it.first } == listOf(EnergyVad.Event.SpeechStarted, EnergyVad.Event.SpeechEnded), synth.toString())
        check("vad: speech end reported ~0.9 s after the burst", synth.last().second in 2200..2800, synth.toString())
        check("vad: quiet noise alone gives no events", run(EnergyVad(), noise(5.0, 40)).isEmpty())
        check("vad: isolated 60 ms click is not speech", run(EnergyVad(), noise(0.6, 40) + tone(0.06, 0.5) + noise(1.5, 40)).none { it.first == EnergyVad.Event.SpeechStarted })
        check("vad: endless speech hits max length", run(EnergyVad(maxUtteranceMs = 3000), noise(0.4, 40) + tone(6.0, 0.3)).any { it.first == EnergyVad.Event.MaxLengthReached })
        val v = EnergyVad(); v.accept(tone(0.5, 0.3)); check("vad: level meter reports energy", v.level > 0.02f, "${v.level}")
        // REAL recorded human speech shipped with the ASR model packages
        val real = readWav(File(args[4], "0.wav")); val x = pcm16(real.first); check("vad: test wav is 16 kHz", real.second == 16000)
        val lead = noise(1.0, 30); val tail = noise(2.0, 30); val ev = run(EnergyVad(), lead + x + tail)
        val speechStartMs = 1000; val speechEndMs = 1000 + x.size * 1000 / sr
        check("vad: real speech -> SpeechStarted shortly after onset", ev.firstOrNull { it.first == EnergyVad.Event.SpeechStarted }?.second?.let { it in speechStartMs..(speechStartMs + 1500) } == true, ev.toString())
        check("vad: real speech -> SpeechEnded within ~2 s after the speech", ev.lastOrNull { it.first == EnergyVad.Event.SpeechEnded }?.second?.let { it in speechEndMs..(speechEndMs + 2000) } == true, "ev=$ev end=$speechEndMs")
        note("real-speech VAD events (ms): $ev ; speech spans $speechStartMs..$speechEndMs")
    }

    // ---------------------------------------------------------------- C. speech text
    run {
        check("text: emoji and markdown removed", SpeechText.clean("**Abeg** 😀 no wahala! 🇳🇬 see `code` here") == "Abeg no wahala! see code here", SpeechText.clean("**Abeg** 😀 no wahala! 🇳🇬 see `code` here"))
        check("text: Yoruba diacritics kept", SpeechText.clean("Ọ̀rẹ́ mi, ẹ kú àárọ̀") .contains("Ọ̀rẹ́"))
        check("text: urls and code blocks dropped", SpeechText.clean("go to https://example.com now ```val x = 1``` ok") == "go to now ok", SpeechText.clean("go to https://example.com now ```val x = 1``` ok"))
        check("text: bullets stripped", SpeechText.clean("- one\n- two\n1. three") == "one\ntwo\nthree", SpeechText.clean("- one\n- two\n1. three"))
        val full = "How body? I dey kampe, no wahala. Abeg make you wait small, I go come back. Wetin dey happen for market today?"
        val c1 = SpeechText.SentenceChunker(); val a = c1.push(full) + c1.flush()
        val c2 = SpeechText.SentenceChunker(); val b = ArrayList<String>(); full.forEach { b += c2.push(it.toString()) }; b += c2.flush()
        check("text: char-by-char streaming gives the same chunks as one push", a == b, "$a vs $b")
        check("text: chunks are whole sentences", a.size >= 2 && a.all { it.last() in ".?!" }, a.toString())
        check("text: nothing speakable -> no chunks", SpeechText.SentenceChunker().let { it.push("😀🙂 ... "); it.flush() }.isEmpty())
        val long = SpeechText.SentenceChunker(maxChars = 60); val lc = long.push("word ".repeat(40)); check("text: very long run without punctuation is still split", lc.isNotEmpty() && lc.all { it.length <= 70 }, lc.map { it.length }.toString())
    }

    // ---------------------------------------------------------------- C2. reasoning-block filter (Qwen3-family emit <think> blocks)
    run {
        fun stream(parts: List<String>): String { val f = ThinkFilter(); return parts.joinToString("") { f.push(it) } + f.flush() }
        fun chars(t: String) = t.map { it.toString() }
        check("think: block removed", stream(listOf("Hello <think>secret reasoning</think>world")) == "Hello world", stream(listOf("Hello <think>secret reasoning</think>world")))
        check("think: empty block + newlines (Qwen3 /no_think output)", stream(listOf("<think>\n\n</think>\n\nHow body?")) == "How body?", stream(listOf("<think>\n\n</think>\n\nHow body?")))
        val t = "<think>The user greets me. I should answer briefly.</think>\n\nNo wahala! <think>more</think>E go better."
        check("think: token-by-token streaming equals one-shot", stream(chars(t)) == stream(listOf(t)) && stream(listOf(t)) == "No wahala! E go better.", stream(chars(t)))
        check("think: unterminated block never leaks", stream(listOf("Hi <think>still thinking about", " the answer")) == "Hi ", stream(listOf("Hi <think>still thinking about", " the answer")))
        check("think: lone '<' text survives", stream(listOf("a < b and <thi")) == "a < b and <thi", stream(listOf("a < b and <thi")))
        check("think: tag split across tokens", stream(listOf("A<th", "ink>x</thi", "nk>B")) == "AB", stream(listOf("A<th", "ink>x</thi", "nk>B")))
        check("think: system prompt adds /no_think only when asked", Prompts.system(LanguageMode.NATURAL_PIDGIN).endsWith("/no_think").not() && Prompts.system(LanguageMode.NATURAL_PIDGIN, noThink = true).endsWith("/no_think"))
    }

    // ---------------------------------------------------------------- D. JSON + voice catalog (the real shipped file)
    run {
        check("json: parses nested values", (MiniJson.parse("""{"a":[1,2.5,true,null,"x\u00e9\n"],"b":{"c":-3}}""") as Map<*, *>)["b"] == mapOf("c" to -3L))
        check("json: rejects trailing garbage", throwsOf { MiniJson.parse("{} x") } != null); check("json: rejects unterminated string", throwsOf { MiniJson.parse("{\"a\":\"b}") } != null)
        check("json: rejects deep nesting bomb", throwsOf { MiniJson.parse("[".repeat(200) + "]".repeat(200)) } != null)
        val cat = VoiceCatalog.parse(profilesJson)
        check("catalog: real file parses (8 profiles)", cat.profiles.size == 8, "${cat.profiles.size}")
        check("catalog: 5 regional styles are unavailable and reference no model", cat.unavailable().size == 5 && cat.unavailable().all { it.modelId == null && !it.selectable && it.id.startsWith("ng-pidgin-") })
        check("catalog: no available profile claims a Nigerian Pidgin style", cat.profiles.filter { it.selectable }.all { it.nigerianPidginStyle == "none" })
        check("catalog: every selectable profile has license + attribution + source", cat.profiles.filter { it.selectable }.all { it.license.isNotBlank() && it.attribution.isNotBlank() && it.source.isNotBlank() })
        check("catalog: nothing installed -> no pick", cat.pick(null, emptySet()) == null)
        check("catalog: unavailable profile can never be picked even if requested", cat.pick("ng-pidgin-lagos", setOf("anything")) == null)
        val both = setOf("vits-piper-en_GB-northern_english_male-medium-int8", "vits-piper-en_GB-southern_english_female-low-int8")
        check("catalog: stored choice respected when installed", cat.pick("piper-en-gb-southern-female", both)?.id == "piper-en-gb-southern-female")
        check("catalog: stale choice falls back to first installed", cat.pick("kokoro-en-v0_19-speaker0", both)?.id == "piper-en-gb-northern-male")
        check("catalog: duplicate ids rejected", throwsOf { VoiceCatalog.parse("""{"profiles":[{"id":"a","status":"unavailable"},{"id":"a","status":"unavailable"}]}""") } != null)
        check("catalog: selectable profile without model rejected", throwsOf { VoiceCatalog.parse("""{"profiles":[{"id":"a","status":"available"}]}""") } != null)
        check("catalog: unavailable profile with a model rejected", throwsOf { VoiceCatalog.parse("""{"profiles":[{"id":"a","status":"unavailable","model_id":"m"}]}""") } != null)
    }

    // ---------------------------------------------------------------- E. safe unzip + model import of the REAL packaged models
    val storeRoot = File(tmp, "voice")
    run {
        val evil = File(tmp, "evil"); check("unzip: zip-slip '../' rejected", throwsOf { SafeUnzip.extract(zipOf("../../escape.txt" to byteArrayOf(1)).inputStream(), File(evil, "a")) } is SafeUnzip.UnsafeArchive)
        check("unzip: absolute path rejected", throwsOf { SafeUnzip.extract(zipOf("/etc/passwd" to byteArrayOf(1)).inputStream(), File(evil, "b")) } is SafeUnzip.UnsafeArchive)
        check("unzip: nothing escaped", !File(tmp, "escape.txt").exists() && !File(evil.parentFile.parentFile, "escape.txt").exists())
        check("unzip: size bomb rejected", throwsOf { SafeUnzip.extract(zipOf("big.bin" to ByteArray(200_000)).inputStream(), File(evil, "c"), maxTotalBytes = 100_000) } is SafeUnzip.UnsafeArchive)
        check("unzip: entry-count bomb rejected", throwsOf { SafeUnzip.extract(zipOf(*Array(30) { "f$it" to byteArrayOf(1) }).inputStream(), File(evil, "d"), maxEntries = 10) } is SafeUnzip.UnsafeArchive)
        val ok = File(evil, "e"); val n = SafeUnzip.extract(zipOf("model/a.txt" to "hi".toByteArray(), "model/sub/b.txt" to "yo".toByteArray()).inputStream(), ok)
        check("unzip: single top-level folder is flattened", n == 2 && File(ok, "a.txt").readText() == "hi" && File(ok, "sub/b.txt").exists())
        check("unzip: no temp folders left behind", evil.listFiles()!!.none { it.name.contains(".tmp-") })
        val map = mapOf("sherpa-onnx-moonshine-tiny-en-int8" to "asr", "sherpa-onnx-whisper-tiny" to "asr", "vits-piper-en_GB-northern_english_male-medium-int8" to "tts", "vits-piper-en_GB-southern_english_female-low-int8" to "tts", "kokoro-int8-en-v0_19" to "tts")
        for ((id, kind) in map) File(zips, "$id.zip").inputStream().use { SafeUnzip.extract(it, File(storeRoot, "$kind/$id")) }
        val store = SpeechModelStore(storeRoot)
        check("store: detects Moonshine + Whisper ASR", store.asrModels().map { it.family }.toSet() == setOf(AsrFamily.MOONSHINE, AsrFamily.WHISPER), store.asrModels().toString())
        check("store: detects Piper (x2) + Kokoro TTS", store.ttsModels().map { it.family }.sortedBy { it.name } == listOf(TtsFamily.KOKORO, TtsFamily.PIPER_VITS, TtsFamily.PIPER_VITS), store.ttsModels().toString())
        check("store: an empty/unknown folder is NOT reported as a model", File(storeRoot, "tts/empty").also { it.mkdirs() }.let { SpeechModelStore.detectTts(it) == null && SpeechModelStore.detectAsr(it) == null })
        check("store: whisper repack kept int8 only (small)", File(storeRoot, "asr/sherpa-onnx-whisper-tiny").listFiles()!!.size == 3)
        val cat = VoiceCatalog.parse(profilesJson); check("catalog+store: three installed profiles selectable", cat.installed(store.installedTtsIds()).size == 3)
    }
    val store = SpeechModelStore(storeRoot)
    val wavs = (0..2).map { "$it.wav" }; val refs = File(args[4], "trans.txt").readLines().associate { it.substringBefore(' ') to it.substringAfter(' ') }

    // ---------------------------------------------------------------- F. REAL ASR on real recorded English speech
    for (spec in store.asrModels()) {
        val t0 = System.nanoTime(); val asr = SherpaAsr.create(spec, 1); val loadS = (System.nanoTime() - t0) / 1e9
        var edits = 0.0; var words = 0; var dur = 0.0; var dec = 0.0
        for (w in listOf("0.wav", "1.wav", "8k.wav")) { val (x, sr) = readWav(File(args[4], w)); val s = System.nanoTime(); val hyp = asr.transcribe(x, sr); dec += (System.nanoTime() - s) / 1e9; dur += x.size.toDouble() / sr
            val ref = refs.getValue(w); val n = ref.split(" ").size; edits += wer(ref, hyp) * n; words += n }
        val cw = edits / words; check("asr[${spec.family}]: real English speech corpus WER < 0.25 (n=3)", cw < 0.25, "WER=$cw"); note("${spec.id}: load %.2fs, WER %.3f over 3 utterances, RTF %.3f (host, 1 core; NOT a phone)".format(loadS, cw, dec / dur))
        check("asr[${spec.family}]: silence does not crash", throwsOf { asr.transcribe(FloatArray(16000), 16000) } == null)
        asr.release()
    }
    // load/release cycles
    run { val spec = store.asrModels().first { it.family == AsrFamily.MOONSHINE }; var ok = true; repeat(3) { try { SherpaAsr.create(spec, 1).release() } catch (t: Throwable) { ok = false } }; check("asr: 3 create/release cycles", ok) }

    // ---------------------------------------------------------------- G. REAL TTS
    val piper = store.ttsModels().first { it.family == TtsFamily.PIPER_VITS && it.id.contains("northern") }
    run {
        val tts = SherpaTts.create(piper, 1); val chunks = ArrayList<FloatArray>()
        val text = "How body? I dey kampe, no wahala. Abeg make you wait small. Wetin dey happen for market today?"
        val t0 = System.nanoTime(); tts.speak(text, 0, 1.0f) { chunks.add(it.copyOf()); true }; val sec = (System.nanoTime() - t0) / 1e9
        val dur = chunks.sumOf { it.size }.toDouble() / tts.sampleRate
        check("tts[piper]: produces audio (> 2 s for a 4-sentence text)", dur > 2.0, "dur=$dur"); check("tts[piper]: audio is not silent", chunks.any { c -> c.any { Math.abs(it) > 0.05f } })
        check("tts[piper]: streams in several chunks", chunks.size >= 3, "chunks=${chunks.size}"); note("piper: %.1f s audio in %.1f s (RTF %.2f, host)".format(dur, sec, sec / dur))
        var got = 0; tts.speak(text, 0, 1.0f) { got += it.size; false }
        check("tts[piper]: returning false stops synthesis after the first chunk", got in 1 until (dur * tts.sampleRate * 0.6).toInt(), "got=$got of ${(dur * tts.sampleRate).toInt()}")
        check("tts[piper]: out-of-range speaker id is clamped, no crash", throwsOf { tts.speak("na so", 99, 1.0f) { true } } == null)
        tts.release()
        val fem = store.ttsModels().first { it.id.contains("southern_english_female") }; val t2 = SherpaTts.create(fem, 1); var n2 = 0; t2.speak("Abeg, no wahala.", 0, 1.0f) { n2 += it.size; true }; check("tts[piper female]: produces audio at ${t2.sampleRate} Hz", n2 > 8000, "n=$n2"); t2.release()
        val kok = store.ttsModels().first { it.family == TtsFamily.KOKORO }; val t3 = SherpaTts.create(kok, 1); var n3 = 0; t3.speak("Abeg, no wahala.", 0, 1.0f) { n3 += it.size; true }
        check("tts[kokoro]: produces audio, ${t3.numSpeakers} speakers", n3 > 8000 && t3.numSpeakers == 11, "n=$n3 spk=${t3.numSpeakers}"); t3.release()
    }

    // ---------------------------------------------------------------- H. full turn with real ASR + real TTS (+ scripted LLM for determinism)
    val moon = store.asrModels().first { it.family == AsrFamily.MOONSHINE }; val asr = SherpaAsr.create(moon, 1); val tts = SherpaTts.create(piper, 1)
    val (speech, speechSr) = readWav(File(args[4], "1.wav"))
    run {
        val sink = CollectSink(); val rec = Recorder(); val runner = VoiceTurnRunner(asr, ScriptedLlm("No wahala, I don hear you. Make we reason am together. E go better."), tts, sink, rec)
        thread { runner.run(speech, speechSr, 0, 1.0f) }; check("turn: completes", rec.finished.await(120, java.util.concurrent.TimeUnit.SECONDS))
        check("turn: transcript came from real ASR (non-empty)", rec.transcript.length > 20, rec.transcript)
        check("turn: events in order", rec.events.toList() == listOf("TranscriptReady", "FirstAudioQueued", "ReplyFinished", "SpeechFinished"), rec.events.toString())
        check("turn: state machine ends Idle (mic closed)", rec.machine.state is VoiceState.Idle && !rec.machine.state.micOpen, "${rec.machine.state}")
        check("turn: reply streamed and audio produced", rec.reply.contains("E go better") && sink.seconds(tts.sampleRate) > 3.0, "audio=${sink.seconds(tts.sampleRate)}")
        check("turn: sink drained once at end, not stopped", sink.drains == 1 && !sink.stopped); check("turn: timings recorded", rec.timings?.let { it.asrMs > 0 && it.firstAudioMs > 0 && it.totalMs >= it.firstAudioMs } == true, "${rec.timings}")
        note("turn timings (host, 1 core, NOT a phone): ${rec.timings}")
    }
    run {   // interrupt during speech
        val sink = CollectSink(); val rec = Recorder(); val runner = VoiceTurnRunner(asr, ScriptedLlm(("Wetin dey happen for market today? " + "Abeg make you wait small, I go come back soon. ").repeat(6), delayMs = 20), tts, sink, rec)
        thread { runner.run(speech, speechSr, 0, 1.0f) }
        val t0 = System.currentTimeMillis(); while (sink.chunks.isEmpty() && System.currentTimeMillis() - t0 < 60_000) Thread.sleep(10)
        val t1 = System.nanoTime(); runner.interrupt(); val done = rec.finished.await(20, java.util.concurrent.TimeUnit.SECONDS); val stopMs = (System.nanoTime() - t1) / 1_000_000
        check("interrupt: turn ends after interrupt", done && rec.cancelled); check("interrupt: playback stopped immediately", sink.stopped)
        check("interrupt: stops within 2 s", stopMs < 2000, "${stopMs}ms"); val after = sink.writesAfterStop.get(); Thread.sleep(300); check("interrupt: no audio written after stop settles", sink.writesAfterStop.get() == after)
        check("interrupt: no completion events fired", "ReplyFinished" !in rec.events && "SpeechFinished" !in rec.events, rec.events.toString()); note("interrupt took ${stopMs} ms")
    }
    run {   // silence -> nothing heard
        val sink = CollectSink(); val rec = Recorder(); val runner = VoiceTurnRunner(asr, ScriptedLlm("should not run"), tts, sink, rec)
        thread { runner.run(FloatArray(16000 * 2), 16000, 0, 1.0f) }; check("silence: turn completes", rec.finished.await(60, java.util.concurrent.TimeUnit.SECONDS))
        if (rec.transcript.isBlank()) { check("silence: NothingHeard, no reply, no audio", "NothingHeard" in rec.events && sink.chunks.isEmpty() && rec.machine.state is VoiceState.Idle, rec.events.toString()) }
        else { check("silence: ASR hallucinated text (finding); pipeline still completed cleanly", rec.machine.state !is VoiceState.Error); note("FINDING: Moonshine returned text for 2 s of digital silence: '${rec.transcript}'") }
    }
    run {   // no TTS installed -> text only
        val sink = CollectSink(); val rec = Recorder(); val runner = VoiceTurnRunner(asr, ScriptedLlm("No wahala. E go better."), null, sink, rec)
        thread { runner.run(speech, speechSr, 0, 1.0f) }; rec.finished.await(60, java.util.concurrent.TimeUnit.SECONDS)
        check("no-tts: reply text delivered, nothing spoken, turn ends Idle", rec.reply.contains("E go better") && sink.chunks.isEmpty() && rec.machine.state is VoiceState.Idle, "${rec.events} ${rec.machine.state}")
    }
    run {   // LLM failure surfaces as Error, not as fake text
        val sink = CollectSink(); val rec = Recorder(); val bad = object : Llm { override fun reply(userText: String, onPiece: (String) -> Unit) = Result.failure<String>(IllegalStateException("model not loaded")); override fun cancel() {} }
        thread { VoiceTurnRunner(asr, bad, tts, sink, rec).run(speech, speechSr, 0, 1.0f) }; rec.finished.await(60, java.util.concurrent.TimeUnit.SECONDS)
        check("llm failure: Error state, no spoken text, message kept", rec.machine.state is VoiceState.Error && (rec.machine.state as VoiceState.Error).message.contains("model not loaded") && sink.chunks.isEmpty(), "${rec.events} ${rec.machine.state}")
    }

    // ---------------------------------------------------------------- I. real ASR -> real llama.cpp engine (random mechanics model) -> real TTS
    run {
        val eng = LlamaEngine(); check("pipeline: llama engine loads mechanics model", eng.load(mech, 512, 2).isSuccess)
        val (clip, clipSr) = readWav(File(args[4], "0.wav"))
        run {   // a prompt that cannot fit the 512-token window must surface as an Error, never as invented text
            val s0 = CollectSink(); val r0 = Recorder(); val big = object : Llm { val inner = EngineLlm(eng, 20); override fun reply(userText: String, onPiece: (String) -> Unit) = inner.e.generate(listOf(ChatTurn("user", "wahala ".repeat(400))), 20, 0.7f) { onPiece(it) }.map { "" }; override fun cancel() = inner.cancel() }
            thread { VoiceTurnRunner(asr, big, tts, s0, r0).run(clip, clipSr, 0, 1.0f) }; r0.finished.await(120, java.util.concurrent.TimeUnit.SECONDS)
            check("pipeline: context overflow surfaces as Error with a clear message, nothing spoken", r0.machine.state is VoiceState.Error && (r0.machine.state as VoiceState.Error).message.contains("too long") && s0.chunks.isEmpty(), "${r0.events} ${r0.machine.state}") }
        val sink = CollectSink(); val rec = Recorder(); val runner = VoiceTurnRunner(asr, EngineLlm(eng, 40), tts, sink, rec)
        thread { runner.run(clip, clipSr, 0, 1.0f) }; check("pipeline: completes", rec.finished.await(180, java.util.concurrent.TimeUnit.SECONDS))
        check("pipeline: real ASR text reached the real engine and tokens came back", rec.transcript.isNotBlank() && rec.reply.isNotBlank(), "t='${rec.transcript}' r='${rec.reply}'")
        check("pipeline: reply was spoken (audio produced)", sink.seconds(tts.sampleRate) > 0.3, "audio=${sink.seconds(tts.sampleRate)}"); check("pipeline: ends Idle", rec.machine.state is VoiceState.Idle, "${rec.machine.state} ${rec.events}")
        note("random-model reply (gibberish by design): '${rec.reply.take(80)}' ; timings ${rec.timings}")
        // interrupt while the real engine is generating
        val sink2 = CollectSink(); val rec2 = Recorder(); val r2 = VoiceTurnRunner(asr, EngineLlm(eng, 300), tts, sink2, rec2)
        thread { r2.run(clip, clipSr, 0, 1.0f) }; val t0 = System.currentTimeMillis(); while (rec2.reply.isEmpty() && System.currentTimeMillis() - t0 < 120_000) Thread.sleep(5)
        r2.interrupt(); check("pipeline: interrupt during real generation ends the turn", rec2.finished.await(30, java.util.concurrent.TimeUnit.SECONDS) && rec2.cancelled)
        check("pipeline: engine usable again after interrupt", eng.generate(listOf(ChatTurn("user", "hi")), 4, 0.7f) {}.isSuccess); eng.unload()
    }
    asr.release(); tts.release()
    println("\n$passed passed, $failed failed"); System.exit(if (failed == 0) 0 else 1)
}
