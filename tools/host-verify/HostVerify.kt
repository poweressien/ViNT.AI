// Host-side verification: runs the REAL LlamaEngine.kt / GgufReader / Prompts on a JVM against the REAL pinned llama.cpp
// through the REAL JNI library. Uses a tiny random mechanics model -> proves plumbing only, says nothing about language quality.
import ng.pidgingpt.data.*
import ng.pidgingpt.inference.*
import java.io.File
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

var passed = 0; var failed = 0
fun check(name: String, ok: Boolean, detail: String = "") { if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  $detail") } }
inline fun <T> expectThrows(name: String, f: () -> T) { val r = runCatching(f); check(name, r.isFailure, "expected an exception") }

fun main(args: Array<String>) {
    val model = args[0]
    // ---- GGUF reader
    val info = File(model).inputStream().use { GgufReader.read(it) }
    check("gguf: architecture", info.architecture == "llama", "$info")
    check("gguf: name", info.name?.startsWith("mechanics-test") == true)
    check("gguf: context length", info.contextLength == 512L)
    check("gguf: quantization label", info.quantization == "F32", info.quantization ?: "null")
    check("gguf: chat template detected", info.hasChatTemplate)
    check("gguf: license key", info.license == "mechanics-test-only")
    expectThrows("gguf: rejects non-GGUF bytes") { GgufReader.read(ByteArrayInputStream("hello world, not a model".toByteArray())) }
    expectThrows("gguf: rejects truncated file") { GgufReader.read(ByteArrayInputStream(File(model).readBytes().copyOf(200))) }
    expectThrows("gguf: rejects empty file") { GgufReader.read(ByteArrayInputStream(ByteArray(0))) }

    // ---- engine lifecycle
    val e = LlamaEngine()
    check("engine: load of a non-GGUF file fails cleanly", e.load(File(model + ".nope").path, 512, 2).isFailure)
    val txt = File.createTempFile("fake", ".gguf").apply { writeText("not a model at all"); deleteOnExit() }
    check("engine: load of a corrupt file fails cleanly (no crash)", e.load(txt.path, 512, 2).isFailure)
    check("engine: generate without a model errors", e.generate(listOf(ChatTurn("user", "hi")), 8, 0.7f) {}.isFailure)
    check("engine: load real GGUF", e.load(model, 512, 2).isSuccess)

    val sb = StringBuilder(); var pieces = 0
    val r1 = e.generate(listOf(ChatTurn("system", "You be ViNT."), ChatTurn("user", "Guy, how body?")), 32, 0.8f) { sb.append(it); pieces++ }
    check("engine: generate returns stats", r1.isSuccess, r1.exceptionOrNull()?.message ?: "")
    val s1 = r1.getOrNull()
    check("engine: produced tokens (<= max)", s1 != null && s1.tokens in 1..32, "tokens=${s1?.tokens}")
    check("engine: streamed text pieces arrived", pieces > 0 && sb.isNotEmpty(), "pieces=$pieces")
    check("engine: not flagged cancelled", s1?.cancelled == false)
    println("      (tokens=${s1?.tokens}, ${"%.0f".format(s1?.tokensPerSec ?: 0.0)} tok/s on host with a 0.5 MB random model: plumbing only)")

    // UTF-8: emoji, Nigerian diacritics, CJK must cross JNI without crashing / corrupting the prompt
    val r2 = e.generate(listOf(ChatTurn("user", "Abeg ọ̀rẹ́ mi 😀🇳🇬 你好 wetin dey happen?")), 16, 0.7f) {}
    check("engine: emoji + diacritics prompt OK", r2.isSuccess, r2.exceptionOrNull()?.message ?: "")

    // cancellation from inside the stream
    var n = 0
    val r3 = e.generate(listOf(ChatTurn("user", "go on")), 400, 0.9f) { n++; if (n == 3) e.cancel() }
    check("engine: cancel stops generation early", r3.getOrNull()?.cancelled == true && (r3.getOrNull()?.tokens ?: 999) < 50, "${r3.getOrNull()}")
    val r3b = e.generate(listOf(ChatTurn("user", "again")), 8, 0.7f) {}
    check("engine: can generate again after a cancel", r3b.isSuccess && r3b.getOrNull()?.cancelled == false)

    // cancel from another thread while running
    var started = false
    val worker = thread { e.generate(listOf(ChatTurn("user", "long")), 450, 0.9f) { started = true; Thread.sleep(2) } }
    val t0 = System.currentTimeMillis(); while (!started && System.currentTimeMillis() - t0 < 5000) Thread.sleep(5)
    e.cancel(); worker.join(10_000)
    check("engine: cross-thread cancel ends the worker", !worker.isAlive)

    // context overflow -> clear error, no crash
    val huge = "wahala ".repeat(3000)
    val r4 = e.generate(listOf(ChatTurn("user", huge)), 8, 0.7f) {}
    check("engine: oversized prompt gives a clear error", r4.isFailure && r4.exceptionOrNull()!!.message!!.contains("too long"), r4.exceptionOrNull()?.message ?: "no error")

    // unload while a generation is running on another thread must wait for it, never crash
    var running = false
    val g = thread { e.generate(listOf(ChatTurn("user", "long")), 450, 0.9f) { running = true; Thread.sleep(2) } }
    val t1 = System.currentTimeMillis(); while (!running && System.currentTimeMillis() - t1 < 5000) Thread.sleep(5)
    e.unload(); g.join(15_000)
    check("engine: unload during generation is safe", !g.isAlive)
    check("engine: reload after unload-during-generation", e.load(model, 512, 2).isSuccess)

    // unload / reload / double-unload
    e.unload(); e.unload()
    check("engine: generate after unload errors", e.generate(listOf(ChatTurn("user", "hi")), 4, 0.7f) {}.isFailure)
    check("engine: reload after unload works", e.load(model, 512, 2).isSuccess && e.generate(listOf(ChatTurn("user", "hi")), 4, 0.7f) {}.isSuccess)
    repeat(5) { check("engine: repeated load/unload #$it", e.load(model, 512, 2).isSuccess.also { e.unload() }) }

    // ---- prompt windowing keeps system + newest turns
    val hist = (1..40).flatMap { listOf(ChatTurn("user", "q$it " + "x".repeat(200)), ChatTurn("assistant", "a$it " + "y".repeat(200))) }
    val win = Prompts.window(Prompts.system(LanguageMode.NATURAL_PIDGIN), hist, 512, 128)
    check("prompts: system first", win.first().role == "system" && win.first().content.startsWith("You be ViNT"))
    check("prompts: newest turn kept", win.last().content.startsWith("a40"))
    check("prompts: window is bounded", win.sumOf { it.content.length } <= (512 - 128) * 4 + 400, "${win.sumOf { it.content.length }}")
    check("prompts: strict mode differs", Prompts.system(LanguageMode.STRICT_PIDGIN) != Prompts.system(LanguageMode.NATURAL_PIDGIN))

    println("\n$passed passed, $failed failed")
    System.exit(if (failed == 0) 0 else 1)
}
