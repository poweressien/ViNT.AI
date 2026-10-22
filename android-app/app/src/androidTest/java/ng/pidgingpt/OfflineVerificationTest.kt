package ng.pidgingpt

import androidx.test.platform.app.InstrumentationRegistry
import ng.pidgingpt.data.*
import ng.pidgingpt.inference.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Run with the device in airplane mode and Wi-Fi off:  ./gradlew connectedAndroidTest
 * Requires a real GGUF at <filesDir>/models/test.gguf (adb push, then run-as copy; see docs/OFFLINE_VERIFICATION.md).
 * STATUS: NOT executed yet (no device/emulator/SDK in the build sandbox).
 */
class OfflineVerificationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun onlyMicrophonePermissionDeclared() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertEquals(listOf("android.permission.RECORD_AUDIO"), info.requestedPermissions.orEmpty().toList())   // microphone only; no INTERNET
    }
    @Test fun slangSearchWorks() { assertTrue(SlangRepository(ctx).search("wahala").isNotEmpty()) }
    @Test fun historyPersists() {
        val s = ConversationStore(ctx); val id = s.newConversation("offline-test"); s.addMessage(id, "user", "How body?")
        assertEquals("How body?", ConversationStore(ctx).messages(id).first().content); s.delete(id)
    }
    @Test fun modelLoadsAndGenerates() {
        val f = File(ctx.filesDir, "models/test.gguf"); org.junit.Assume.assumeTrue("no test model provisioned", f.exists())
        val e = LlamaEngine(); e.load(f.path, 1024, 4).getOrThrow()
        val sb = StringBuilder()
        val st = e.generate(listOf(ChatTurn("user", "Guy, how body?")), 32, 0.7f) { sb.append(it) }.getOrThrow()
        e.unload(); assertTrue(sb.isNotBlank()); println("tok/s=${st.tokensPerSec}")
    }
}
