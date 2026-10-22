package ng.pidgingpt.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ng.pidgingpt.ChatViewModel
import ng.pidgingpt.inference.EngineState
import ng.pidgingpt.voice.TtsFamily
import ng.pidgingpt.voice.VoiceState
import ng.pidgingpt.voice.VoiceStatus

private fun stateLabel(s: VoiceState, handsFree: Boolean): String = when (s) {
    VoiceState.Off -> "Voice mode is off. Nothing is listening."
    VoiceState.Idle -> "Ready. Hold the button to talk, or tap once to talk hands-free."
    VoiceState.Listening -> if (handsFree) "Listening… it stops when you pause. Tap to finish." else "Listening… release to send."
    VoiceState.Transcribing -> "Recognising speech…"
    VoiceState.Thinking -> "Thinking…"
    VoiceState.Speaking -> "Speaking…"
    is VoiceState.Error -> s.message
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun VoiceScreen(vm: ChatViewModel, goModel: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current; val activity = ctx as? Activity; val scope = rememberCoroutineScope()
    val ui by vm.voiceUi.collectAsState(); val engine by vm.engineState.collectAsState()
    var tick by remember { mutableIntStateOf(0) }; var importMsg by remember { mutableStateOf<String?>(null) }; var importing by remember { mutableStateOf(false) }
    fun micGranted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var hasMic by remember { mutableStateOf(micGranted()) }; var denied by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) { val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) hasMic = micGranted() }; owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> hasMic = ok; denied = !ok }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "model.zip"
        importing = true; importMsg = null
        scope.launch(Dispatchers.IO) { vm.importSpeechZip(uri, name).onSuccess { importMsg = "Imported as a ${if (it == "asr") "speech recognition" else "voice"} model." }.onFailure { importMsg = it.message ?: "Import failed." }; importing = false; tick++ }
    }
    val asr = remember(tick) { vm.speech.asrModels() }; val tts = remember(tick) { vm.speech.ttsModels() }
    val installedTts = remember(tick) { vm.speech.installedTtsIds() }
    val modeOn = ui.state !is VoiceState.Off && ui.state !is VoiceState.Error
    val busy = ui.state.busy; var handsFree by remember { mutableStateOf(false) }
    val llmReady = engine is EngineState.Ready

    Page("Voice", onBack, scroll = false) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- status
            Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(modeOn); Spacer(Modifier.width(8.dp)); Text(stateLabel(ui.state, handsFree), fontSize = 14.sp, modifier = Modifier.weight(1f)) }
            ui.notice?.let { Notice(it, Icons.Default.Info) }
            if (ui.state is VoiceState.Error) Notice((ui.state as VoiceState.Error).message)

            // ---- the three local components
            Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Three parts, all on this phone", fontWeight = FontWeight.SemiBold)
                @Composable fun part(title: String, ok: Boolean, detail: String) = Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp); secondary(detail, 12) }; Pill(if (ok) "Ready" else "Missing", ok) }
                part("1 · Speech recognition", asr.isNotEmpty(), if (asr.isNotEmpty()) (vm.selectedAsr()?.id ?: "") else "Import a speech recognition model below.")
                part("2 · Language model", llmReady, if (llmReady) (engine as EngineState.Ready).modelName else "Not ready. Load a model on the Model screen.")
                part("3 · Voice (speech output)", vm.selectedVoice() != null, vm.selectedVoice()?.displayName ?: "Optional. Without a voice, replies are shown but not spoken.")
                if (!llmReady) VintOutlinedButton("Open Model screen", goModel)
            }

            // ---- microphone permission
            if (!hasMic) Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Microphone access", fontWeight = FontWeight.SemiBold)
                secondary("ViNT uses the microphone only while voice mode is on and you are talking. Audio is processed on this phone and is never saved or sent anywhere.", 13)
                if (denied && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) {
                    secondary("Microphone access is blocked. Turn it on in the app's settings, or keep using text chat.", 13)
                    VintOutlinedButton("Open app settings", { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) })
                } else VintButton("Allow microphone", { permission.launch(Manifest.permission.RECORD_AUDIO) })
            }

            // ---- conversation
            if (ui.transcript.isNotEmpty() || ui.reply.isNotEmpty()) {
                if (ui.transcript.isNotEmpty()) Column(Modifier.fillMaxWidth().vintCard().padding(14.dp)) { secondary("You said (as recognised, may contain mistakes)", 11); Text(ui.transcript) }
                if (ui.reply.isNotEmpty()) Column(Modifier.fillMaxWidth().vintCard().padding(14.dp)) { secondary("ViNT", 11); Text(ui.reply) }
                ui.timings?.let { secondary("Last turn: recognition ${it.asrMs} ms · first audio ${if (it.firstAudioMs >= 0) "${it.firstAudioMs} ms" else "none"} · total ${it.totalMs} ms (measured on this phone)", 11) }
            }

            // ---- voices
            Section("Voice")
            val selected = vm.selectedVoice()
            vm.voiceCatalog.profiles.filter { it.selectable }.forEach { p ->
                val installed = p.modelId in installedTts; val isSel = selected?.id == p.id
                Row(Modifier.fillMaxWidth().vintCard().padding(12.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(isSel, { vm.settings.voiceProfileId = p.id; tick++ }, enabled = installed && !modeOn, colors = vintRadioColors())
                    Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium); secondary(p.accentDescription, 12)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(if (installed) "Installed" else "Not installed", installed); if (p.voiceLabel != "unspecified") Pill("${p.voiceLabel} voice")
                            if (p.status == VoiceStatus.EXPERIMENTAL) Pill("Experimental"); Pill("Not a Nigerian accent") }
                        secondary(p.statusReason, 11); secondary("License: ${p.license}", 11) } } }
            Column(Modifier.fillMaxWidth().vintCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nigerian Pidgin regional voices: not available yet", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                secondary("No licensed, consented Nigerian Pidgin speech data and no trained voice exist in this project yet, so none of these can be selected. A region is not a single accent; a real voice will be one consented speaker with a documented background.", 12)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { vm.voiceCatalog.unavailable().forEach { Pill(it.displayName.substringAfter("— ").substringBefore(" style")) } } }

            // ---- speech models
            Section("Speech models")
            secondary("ViNT never downloads models. Make the .zip files on your PC with tools/fetch_voice_models.py, copy them to this phone, then import them here.", 12)
            secondary("These recognisers are built for English. They often mis-hear Pidgin words, so treat what you see as approximate.", 12)
            asr.forEach { m -> Row(Modifier.fillMaxWidth().vintCard().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm.selectedAsr()?.id == m.id, { vm.settings.asrModelId = m.id; tick++ }, enabled = !modeOn, colors = vintRadioColors())
                Column(Modifier.weight(1f)) { Text(m.id, fontSize = 13.sp); secondary("Speech recognition · ${m.family.name.lowercase()}", 11) }
                IconButton({ vm.deleteSpeechModel("asr", m.id); tick++ }, enabled = !modeOn, colors = vintIconButtonColors()) { Icon(Icons.Default.Delete, "Delete ${m.id}", tint = Palette.TextSecondary) } } }
            tts.forEach { m -> Row(Modifier.fillMaxWidth().vintCard().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(m.id, fontSize = 13.sp); secondary("Voice · ${if (m.family == TtsFamily.KOKORO) "kokoro" else "piper"}", 11) }
                IconButton({ vm.deleteSpeechModel("tts", m.id); tick++ }, enabled = !modeOn, colors = vintIconButtonColors()) { Icon(Icons.Default.Delete, "Delete ${m.id}", tint = Palette.TextSecondary) } } }
            if (importing) VintProgress(Modifier.fillMaxWidth()); importMsg?.let { secondary(it, 12) }
            VintOutlinedButton("Import speech model (.zip)", { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !importing && !modeOn, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        // ---- fixed controls
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.state is VoiceState.Listening) Box(Modifier.fillMaxWidth().height(6.dp).background(Palette.Border, PillShape)) { Box(Modifier.fillMaxWidth((ui.level * 8f).coerceIn(0.02f, 1f)).fillMaxHeight().background(Palette.Green, PillShape)) }
            if (modeOn) {
                val canTalk = ui.state is VoiceState.Idle || ui.state is VoiceState.Listening; val listening = ui.state is VoiceState.Listening
                Box(Modifier.fillMaxWidth().height(72.dp).background(if (listening) Palette.Green else Palette.Surface, PillShape).border(1.dp, if (listening) Palette.Green else Palette.Border, PillShape)
                    .semantics { contentDescription = "Talk button. Press and hold to talk, or tap once to talk hands-free." }
                    .pointerInput(canTalk) { if (canTalk) detectTapGestures(onPress = { val t0 = System.currentTimeMillis(); val wasListening = ui.state is VoiceState.Listening; vm.voicePress(); handsFree = false
                        tryAwaitRelease(); val held = System.currentTimeMillis() - t0; vm.voiceRelease(if (wasListening) 0 else held); if (!wasListening && held < 350) handsFree = true }) },
                    contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Mic, null, tint = if (listening) Palette.OnGreen else if (canTalk) Palette.Green else Palette.TextDisabled)
                        Spacer(Modifier.width(10.dp)); Text(if (listening) "Listening…" else if (canTalk) "Hold to talk · tap for hands-free" else "Wait or stop", color = if (listening) Palette.OnGreen else if (canTalk) Palette.White else Palette.TextDisabled, fontWeight = FontWeight.Medium) } }
                if (busy || ui.state is VoiceState.Listening) VintOutlinedButton("Stop", { vm.voiceInterrupt() }, Modifier.fillMaxWidth())
                VintOutlinedButton("Stop voice mode", { vm.stopVoice() }, Modifier.fillMaxWidth())
            } else VintButton(if (ui.state is VoiceState.Error) "Try voice mode again" else "Start voice mode", { vm.startVoice() }, Modifier.fillMaxWidth(), enabled = hasMic && llmReady && asr.isNotEmpty())
            VintTextButton("Use text chat instead", onBack, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
