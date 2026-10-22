package ng.pidgingpt.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ng.pidgingpt.ChatViewModel
import ng.pidgingpt.data.*
import ng.pidgingpt.inference.EngineState
import ng.pidgingpt.inference.GenState
import java.text.DateFormat
import java.util.Date

enum class Screen { Onboarding, Chat, History, Dictionary, Model, Voice, Settings, About }

const val TAGLINE = "Your Pidgin. Your AI. Offline."

@Composable fun AppNav(vm: ChatViewModel) {
    var screen by remember { mutableStateOf(if (vm.settings.onboarded) Screen.Chat else Screen.Onboarding) }
    BackHandler(enabled = screen != Screen.Chat && screen != Screen.Onboarding) { screen = Screen.Chat }
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Crossfade(screen, label = "screen") { s ->
            when (s) {
                Screen.Onboarding -> OnboardingScreen { vm.settings.onboarded = true; screen = if (vm.engineState.value is EngineState.NotInstalled) Screen.Model else Screen.Chat }
                Screen.Chat -> ChatScreen(vm) { screen = it }
                Screen.History -> HistoryScreen(vm, onOpen = { vm.openChat(it); screen = Screen.Chat }) { screen = Screen.Chat }
                Screen.Dictionary -> DictionaryScreen(vm) { screen = Screen.Chat }
                Screen.Model -> ModelScreen(vm) { screen = Screen.Chat }
                Screen.Voice -> VoiceScreen(vm, goModel = { screen = Screen.Model }) { screen = Screen.Chat }
                Screen.Settings -> SettingsScreen(vm) { screen = Screen.Chat }
                Screen.About -> AboutScreen(vm) { screen = Screen.Chat }
            }
        }
    }
}

// ---------------------------------------------------------------- shared bits

@Composable internal fun secondary(text: String, size: Int = 13, modifier: Modifier = Modifier) = Text(text, color = Palette.TextSecondary, fontSize = size.sp, modifier = modifier, lineHeight = (size + 5).sp)

@Composable internal fun Pill(text: String, active: Boolean = false) = Text(text, fontSize = 11.sp, color = if (active) Palette.Green else Palette.TextSecondary,
    modifier = Modifier.border(1.dp, if (active) Palette.Green else Palette.Border, PillShape).padding(horizontal = 10.dp, vertical = 4.dp))

/** Errors/warnings are palette-only: outline icon + label + white border (no red). */
@Composable internal fun Notice(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.ErrorOutline, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().vintCard(RoundedCornerShape(16.dp)).border(1.dp, Palette.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(text, fontSize = 13.sp, color = Palette.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Page(title: String, onBack: () -> Unit, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) = Scaffold(
    containerColor = Palette.Background, contentColor = Palette.White,
    topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Background, titleContentColor = Palette.White, navigationIconContentColor = Palette.White),
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onBack, colors = vintIconButtonColors()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { p ->
    Column(Modifier.padding(p).padding(horizontal = 16.dp).fillMaxSize().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable internal fun Section(title: String) = Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 1.2.sp, color = Palette.Green, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))

private fun statusLabel(e: EngineState, g: GenState): String = when {
    g is GenState.Generating -> "Generating…"
    e is EngineState.Loading -> "Loading model…"
    e is EngineState.Ready -> "Ready · ${e.modelName}"
    e is EngineState.Failed -> "Model failed to load"
    e is EngineState.Unloaded -> "Model not loaded"
    else -> "No model installed"
}

// ---------------------------------------------------------------- onboarding

@Composable fun OnboardingScreen(done: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Background).padding(28.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp)); BrandMark(112.dp); Spacer(Modifier.height(20.dp))
        Text("ViNT", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.White)
        Spacer(Modifier.height(6.dp)); Text(TAGLINE, fontSize = 16.sp, color = Palette.TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        listOf(Icons.Default.AirplanemodeActive to "Works offline. Chat runs on your phone, so airplane mode is fine.",
            Icons.Default.Lock to "Private. Chats, settings and the slang dictionary stay on this device.",
            Icons.Default.Memory to "Needs a real model file. ViNT does not download one. You import a .gguf yourself, and until you do, chat stays off.").forEach { (i, t) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).vintCard().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(i, null, tint = Palette.Green); Spacer(Modifier.width(14.dp)); Text(t, color = Palette.White, fontSize = 14.sp, lineHeight = 20.sp) } }
        Spacer(Modifier.height(28.dp)); VintButton("Continue", done, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- chat

private val starters = listOf("Guy, how body?", "Teach me new Pidgin slang.", "Explain this thing for Pidgin.",
    "Wetin this expression mean?", "Give me one funny Pidgin story.", "Help me understand this English sentence for Pidgin.")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ChatScreen(vm: ChatViewModel, go: (Screen) -> Unit) {
    val msgs by vm.messages.collectAsState(); val engine by vm.engineState.collectAsState(); val gen by vm.genState.collectAsState()
    val stats by vm.lastStats.collectAsState()
    var input by remember { mutableStateOf("") }; var menu by remember { mutableStateOf(false) }; var confirmClear by remember { mutableStateOf(false) }
    val list = rememberLazyListState(); val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    val ready = engine is EngineState.Ready; val busy = gen is GenState.Generating
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.content?.length) { if (msgs.isNotEmpty()) list.scrollToItem(msgs.lastIndex) }

    if (confirmClear) VintDialog("Clear this conversation?", "This deletes it from your phone. It cannot be undone.", "Clear", { confirmClear = false; vm.deleteChat(vm.conversationId) }, { confirmClear = false })

    Scaffold(containerColor = Palette.Background, contentColor = Palette.White, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Background, titleContentColor = Palette.White, actionIconContentColor = Palette.White), title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(30.dp); Spacer(Modifier.width(10.dp))
                Column { Text("ViNT", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(ready); Spacer(Modifier.width(6.dp))
                        Text(statusLabel(engine, gen), fontSize = 11.sp, color = Palette.TextSecondary, maxLines = 1) } } } },
            actions = {
                IconButton({ vm.newChat() }, colors = vintIconButtonColors()) { Icon(Icons.Default.Add, "New conversation") }
                IconButton({ go(Screen.Voice) }, colors = vintIconButtonColors()) { Icon(Icons.Default.Mic, "Voice chat") }
                IconButton({ go(Screen.History) }, colors = vintIconButtonColors()) { Icon(Icons.Default.History, "Conversation history") }
                Box {
                    IconButton({ menu = true }, colors = vintIconButtonColors()) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }, containerColor = Palette.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Palette.Border)) {
                        listOf("Slang dictionary" to Screen.Dictionary, "Model" to Screen.Model, "Settings & privacy" to Screen.Settings, "About ViNT" to Screen.About)
                            .forEach { (n, s) -> DropdownMenuItem(text = { Text(n, color = Palette.White) }, onClick = { menu = false; go(s) }) }
                        HorizontalDivider(color = Palette.Border)
                        DropdownMenuItem(text = { Text("Clear this conversation", color = Palette.White) }, onClick = { menu = false; confirmClear = true })
                    } } })
    }, bottomBar = {
        Column(Modifier.background(Palette.Background).imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val g = gen) {
                is GenState.Failed -> Notice(g.message, onClick = { vm.dismissGenError() })
                is GenState.Cancelled -> Notice("Generation stopped after ${g.tokens} tokens. The partial reply is saved.", Icons.Default.Info) { vm.dismissGenError() }
                else -> {}
            }
            if (busy) VintProgress(Modifier.fillMaxWidth())
            else stats?.let { Text("%.1f tokens/s · %d tokens".format(it.tokensPerSec, it.tokens), fontSize = 10.sp, color = Palette.TextSecondary, modifier = Modifier.padding(start = 12.dp)) }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), enabled = ready,
                    placeholder = { Text(if (ready) "Talk to ViNT…" else "Load a model to chat") }, maxLines = 5,
                    shape = RoundedCornerShape(28.dp), colors = vintTextFieldColors())
                Spacer(Modifier.width(8.dp))
                if (busy) FilledIconButton({ vm.stop() }, Modifier.size(52.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Surface, contentColor = Palette.Green)) { Icon(Icons.Default.Stop, "Stop generating") }
                else FilledIconButton({ vm.send(input); input = "" }, Modifier.size(52.dp), enabled = ready && input.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Green, contentColor = Palette.OnGreen, disabledContainerColor = Palette.Surface, disabledContentColor = Palette.TextDisabled)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (msgs.isEmpty()) Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ready) {
                    Text("How body? Wetin you wan talk?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    secondary("Pick one to start, or type your own.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        starters.forEach { s -> AssistChip({ vm.send(s) }, { Text(s) }, shape = PillShape, colors = vintChipColors(), border = AssistChipDefaults.assistChipBorder(true, borderColor = Palette.Border)) } }
                } else ModelMissingCard(engine, onSetup = { go(Screen.Model) }, onLoad = { vm.loadModel() })
            } else LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(msgs, key = { it.id }) { m ->
                    val mine = m.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 320.dp).let { if (mine) it.background(Palette.Green, BubbleShape) else it.vintCard(BubbleShape) }.padding(14.dp)) {
                            Text(m.content.ifEmpty { "…" }, color = if (mine) Palette.OnGreen else Palette.White, lineHeight = 22.sp)
                            if (!mine && m.content.isNotEmpty()) IconButton({ (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ViNT", m.content)) },
                                Modifier.size(32.dp).align(Alignment.End), colors = vintIconButtonColors()) { Icon(Icons.Default.ContentCopy, "Copy response", Modifier.size(16.dp), tint = Palette.TextSecondary) }
                        } } }
            }
            if (msgs.size > 4 && list.canScrollForward) SmallFloatingActionButton({ scope.launch { list.animateScrollToItem(msgs.lastIndex) } }, Modifier.align(Alignment.BottomEnd).padding(12.dp),
                containerColor = Palette.Surface, contentColor = Palette.Green, shape = PillShape) { Icon(Icons.Default.KeyboardArrowDown, "Scroll to latest") }
        }
    }
}

@Composable private fun ModelMissingCard(engine: EngineState, onSetup: () -> Unit, onLoad: () -> Unit) {
    Column(Modifier.fillMaxWidth().vintCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandMark(48.dp)
        when (engine) {
            EngineState.NotInstalled -> { Text("No model installed", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                secondary("ViNT answers with a real language model that runs on your phone. None is installed yet, so chat is off. Import a .gguf model file to begin.", 14)
                VintButton("Set up model", onSetup, Modifier.fillMaxWidth()) }
            EngineState.Unloaded -> { Text("Model not loaded", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                secondary("A model file is on this phone but it is not loaded into memory yet.", 14)
                VintButton("Load model", onLoad, Modifier.fillMaxWidth()) }
            EngineState.Loading -> { Text("Loading model…", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); VintProgress(Modifier.fillMaxWidth()); secondary("Large models can take a while on first load.", 14) }
            is EngineState.Failed -> { Text("Model failed to load", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Notice(engine.message)
                VintOutlinedButton("Open Model screen", onSetup, Modifier.fillMaxWidth()) }
            is EngineState.Ready -> {}
        }
    }
}

// ---------------------------------------------------------------- history

@Composable fun HistoryScreen(vm: ChatViewModel, onOpen: (Long) -> Unit, onBack: () -> Unit) {
    var q by remember { mutableStateOf("") }; var tick by remember { mutableIntStateOf(0) }
    var rename by remember { mutableStateOf<Conversation?>(null) }; var delete by remember { mutableStateOf<Conversation?>(null) }; var clearAll by remember { mutableStateOf(false) }
    val convs = remember(q, tick) { vm.store.conversations(q) }
    rename?.let { c -> var t by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog({ rename = null }, shape = CardShape, containerColor = Palette.Surface, tonalElevation = 0.dp, titleContentColor = Palette.White,
            title = { Text("Rename conversation") }, text = { OutlinedTextField(t, { t = it }, singleLine = true, shape = PillShape, colors = vintTextFieldColors()) },
            confirmButton = { VintTextButton("Save", { if (t.isNotBlank()) vm.store.rename(c.id, t.trim()); rename = null; tick++ }) },
            dismissButton = { TextButton({ rename = null }, colors = ButtonDefaults.textButtonColors(contentColor = Palette.White)) { Text("Cancel") } }) }
    delete?.let { c -> VintDialog("Delete conversation?", "“${c.title}” will be removed from your phone.", "Delete", { vm.deleteChat(c.id); delete = null; tick++ }, { delete = null }) }
    if (clearAll) VintDialog("Clear all history?", "Every saved conversation will be deleted from your phone. This cannot be undone.", "Clear all", { vm.clearAllChats(); clearAll = false; tick++ }, { clearAll = false })

    Page("Conversations", onBack, scroll = false) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search conversations") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = PillShape, colors = vintTextFieldColors())
        if (convs.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(56.dp); Spacer(Modifier.height(12.dp)); secondary(if (q.isBlank()) "No saved conversations yet." else "Nothing matches “$q”.", 14) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(convs, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().vintCard().clickable { onOpen(c.id) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(c.title, maxLines = 1, fontWeight = FontWeight.Medium); secondary(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.updatedAt)), 11) }
                    IconButton({ rename = c }, colors = vintIconButtonColors()) { Icon(Icons.Default.Edit, "Rename", tint = Palette.TextSecondary) }
                    IconButton({ delete = c }, colors = vintIconButtonColors()) { Icon(Icons.Default.Delete, "Delete", tint = Palette.TextSecondary) } } } }
        if (convs.isNotEmpty()) VintOutlinedButton("Clear all history", { clearAll = true }, Modifier.fillMaxWidth().padding(bottom = 12.dp))
    }
}

// ---------------------------------------------------------------- dictionary

@OptIn(ExperimentalLayoutApi::class)
@Composable fun DictionaryScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val total = remember { runCatching { vm.slang.count() }.getOrNull() }
    val hits = remember(q) { runCatching { vm.slang.search(q) }.getOrDefault(emptyList()) }
    Page("Slang dictionary", onBack, scroll = false) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search an expression, spelling or meaning") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = PillShape, colors = vintTextFieldColors())
        secondary(if (total != null) "$total entries in this build. Search runs on your phone." else "Dictionary could not be opened.", 12)
        if (q.isNotBlank() && hits.isEmpty()) secondary("No entry for “$q”. ViNT will not guess a meaning that is not in its data.", 13)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(hits) { e ->
                Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.expression, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Palette.Green)
                    Text(e.meaning, lineHeight = 21.sp)
                    if (e.contextual.isNotEmpty()) secondary(e.contextual)
                    if (e.examples.isNotEmpty()) { secondary("Examples", 11); e.examples.forEach { Text("“$it”", fontSize = 14.sp) } }
                    if (e.altSpellings.isNotEmpty()) secondary("Also spelled: " + e.altSpellings.joinToString(", "))
                    if (e.related.isNotEmpty()) secondary("Related: " + e.related.joinToString(" · "))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(if (e.humanVerified) "Human verified" else "Not verified", e.humanVerified)
                        if (e.synthetic) Pill("Synthetic")
                        Pill("Confidence %.2f".format(e.confidence))
                        if (e.category.isNotEmpty()) Pill(e.category); if (e.tone.isNotEmpty()) Pill(e.tone) }
                    secondary("Source: ${e.source} · License: ${e.license}", 11)
                    if (e.provenance.isNotEmpty()) secondary("Provenance: ${e.provenance}", 11)
                } } }
    }
}

// ---------------------------------------------------------------- model management

@Composable fun ModelScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val engine by vm.engineState.collectAsState()
    var tick by remember { mutableIntStateOf(0) }; var msg by remember { mutableStateOf<String?>(null) }; var err by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Long?>(null) }; var toDelete by remember { mutableStateOf<ModelInfo?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "model.gguf"
        err = null; msg = null; progress = 0
        scope.launch(Dispatchers.IO) {
            vm.models.import(uri, name) { progress = it }
                .onSuccess { vm.settings.modelFileName = it.file.name; msg = "Imported ${it.file.name}. SHA-256 ${it.sha256}. Not loaded yet: tap Load." }
                .onFailure { err = it.message ?: "Import failed." }
            progress = null; vm.refreshModelState(); tick++
        }
    }
    val list = remember(tick, engine) { vm.models.list() }
    toDelete?.let { m -> VintDialog("Delete model?", "${m.file.name} (${Formatter.formatFileSize(ctx, m.sizeBytes)}) will be removed from this phone.", "Delete", { vm.deleteModel(m); toDelete = null; tick++ }, { toDelete = null }) }

    Page("Model", onBack) {
        secondary("ViNT runs a language model that you import as a .gguf file. Nothing is downloaded by the app. Copy the file to your phone first, then import it here.", 14)
        when (val s = engine) {
            EngineState.NotInstalled -> Notice("No model installed. Chat will not work until you import a real model.", Icons.Default.Info)
            is EngineState.Failed -> Notice(s.message)
            EngineState.Loading -> { secondary("Loading model…", 13); VintProgress(Modifier.fillMaxWidth()) }
            else -> {}
        }
        progress?.let { p -> secondary("Importing… ${Formatter.formatFileSize(ctx, p)}", 13); VintProgress(Modifier.fillMaxWidth()) }
        err?.let { Notice(it) }; msg?.let { secondary(it, 12) }
        VintButton("Import .gguf model", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = progress == null)
        secondary("Free storage: ${Formatter.formatFileSize(ctx, vm.models.freeStorageBytes())} · Models: ${Formatter.formatFileSize(ctx, vm.models.totalModelBytes())}", 12)
        if (list.isNotEmpty()) Section("Installed")
        list.forEach { m ->
            val g = m.gguf; val c = vm.models.compat(m, vm.settings.contextTokens)
            val isReady = (engine as? EngineState.Ready)?.modelName == m.file.name
            Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(g?.name ?: m.file.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
                    Pill(if (isReady) "Ready" else if (g == null) "Invalid" else "Not loaded", isReady) }
                secondary("${m.file.name} · ${Formatter.formatFileSize(ctx, m.sizeBytes)}", 12)
                if (g != null) secondary(listOfNotNull(g.architecture?.let { "Architecture: $it" }, g.quantization?.let { "Quantization: $it" },
                    g.contextLength?.let { "Trained context: $it" }, g.blockCount?.let { "Layers: $it" }, "Chat template: ${if (g.hasChatTemplate) "included" else "missing (default used)"}").joinToString(" · "), 12)
                if (g?.license != null) secondary("License (from file): ${g.license}", 12) else secondary("License: not stated in the file. Check the model's source before sharing.", 12)
                c.warning?.let { Notice(it, if (c.ok) Icons.Default.Info else Icons.Default.ErrorOutline) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isReady) VintOutlinedButton("Unload", { vm.unloadModel() }) else VintButton("Load", { vm.loadModel(m.file.name) }, enabled = c.ok && engine !is EngineState.Loading)
                    VintOutlinedButton("Delete", { toDelete = m }) } }
        }
        secondary("“Ready” appears only after the on-device runtime has loaded the model successfully. A model that is merely installed is never reported as ready. A general-purpose model is not a Pidgin-trained model: see Settings → About for what is and is not verified.", 12)
    }
}

// ---------------------------------------------------------------- settings

@Composable fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(vm.settings.languageMode) }; var maxNew by remember { mutableFloatStateOf(vm.settings.maxNewTokens.toFloat()) }
    var ctxT by remember { mutableFloatStateOf(vm.settings.contextTokens.toFloat()) }; var temp by remember { mutableFloatStateOf(vm.settings.temperature) }
    var retention by remember { mutableIntStateOf(vm.settings.retentionDays) }; var clear by remember { mutableStateOf(false) }
    val hasInternet = remember { @Suppress("DEPRECATION") runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().any { it == "android.permission.INTERNET" } }.getOrNull() }
    if (clear) VintDialog("Clear all history?", "Every saved conversation will be deleted from your phone.", "Clear all", { vm.clearAllChats(); clear = false }, { clear = false })

    Page("Settings & privacy", onBack) {
        Section("Language")
        Column(Modifier.fillMaxWidth().vintCard().padding(8.dp)) {
            listOf(LanguageMode.NATURAL_PIDGIN to "Natural Nigerian Pidgin — technical terms stay in English where needed (default)", LanguageMode.STRICT_PIDGIN to "Strict Nigerian Pidgin — Pidgin all the way").forEach { (m, label) ->
                Row(Modifier.fillMaxWidth().clickable { mode = m; vm.settings.languageMode = m }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == m, { mode = m; vm.settings.languageMode = m }, colors = vintRadioColors()); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 14.sp) } } }
        Section("Generation")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp)) {
            Text("Max reply length: ${maxNew.toInt()} tokens", fontSize = 14.sp); Slider(maxNew, { maxNew = it; vm.settings.maxNewTokens = it.toInt() }, valueRange = 32f..1024f, colors = vintSliderColors())
            Text("Context window: ${ctxT.toInt()} tokens", fontSize = 14.sp); Slider(ctxT, { ctxT = it; vm.settings.contextTokens = (it / 256).toInt().coerceAtLeast(2) * 256 }, valueRange = 512f..4096f, colors = vintSliderColors())
            secondary("Reload the model to apply a new context size. Bigger context uses more memory.", 12)
            Text("Temperature: %.2f".format(temp), fontSize = 14.sp); Slider(temp, { temp = it; vm.settings.temperature = it }, valueRange = 0.1f..1.2f, colors = vintSliderColors()) }
        Section("Local history")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Keep conversations for", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0 to "Forever", 30 to "30 days", 90 to "90 days", 365 to "1 year").forEach { (d, l) ->
                val sel = retention == d
                Text(l, fontSize = 12.sp, color = if (sel) Palette.OnGreen else Palette.White, modifier = Modifier.background(if (sel) Palette.Green else Palette.Surface, PillShape).border(1.dp, if (sel) Palette.Green else Palette.Border, PillShape)
                    .clickable { retention = d; vm.settings.retentionDays = d; vm.store.purgeOlderThan(d) }.padding(horizontal = 12.dp, vertical = 8.dp)) } }
            VintOutlinedButton("Clear all history", { clear = true }) }
        Section("Storage")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            fun sz(b: Long) = Formatter.formatFileSize(ctx, b)
            secondary("Models: ${sz(vm.models.totalModelBytes())}", 13); secondary("Conversations: ${sz(ctx.getDatabasePath("conversations.db").takeIf { it.exists() }?.length() ?: 0L)}", 13)
            secondary("Free on this phone: ${sz(vm.models.freeStorageBytes())}", 13) }
        Section("Privacy & offline status")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fun row(ok: Boolean?, t: String) = @Composable { Row(verticalAlignment = Alignment.Top) { Icon(if (ok == true) Icons.Default.CheckCircle else Icons.Default.Info, null, tint = if (ok == true) Palette.Green else Palette.TextSecondary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(t, fontSize = 13.sp) } }
            row(hasInternet?.not(), if (hasInternet == false) "This app declares no network permission." else if (hasInternet == true) "This build declares the INTERNET permission." else "Could not read this app's permissions.")()
            row(true, "Replies are generated on this phone by the model you import.")()
            row(true, "Microphone is used only while voice mode is on and you are talking. Speech recognition and speech output run on this phone. Audio is never saved or sent.")()
            row(true, "Chats, settings and the slang dictionary are stored only on this phone.")()
            row(null, "This checks what the app declares. It is not a traffic audit. To check for yourself, switch on airplane mode and chat.")() }
    }
}

// ---------------------------------------------------------------- about

@Composable fun AboutScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val model = remember { vm.models.list().firstOrNull { it.file.name == vm.settings.modelFileName } }
    val entries = remember { runCatching { vm.slang.count() }.getOrNull() }
    Page("About ViNT", onBack) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(96.dp); Spacer(Modifier.height(12.dp)); Text("ViNT", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(TAGLINE, color = Palette.TextSecondary); secondary("Version $version", 12) }
        Section("Model attribution")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (model?.gguf == null) secondary("No model selected. When you load one, its name, architecture and license (as written in the file) appear here.", 13)
            else { Text(model.gguf.name ?: model.file.name, fontWeight = FontWeight.Bold)
                secondary("Architecture: ${model.gguf.architecture ?: "unknown"} · Quantization: ${model.gguf.quantization ?: "unknown"}", 12)
                secondary("License: ${model.gguf.license ?: "not stated in the file. Check the model's source."}", 12) }
            secondary("ViNT does not choose or ship a model. Whatever you import keeps its own license. A general-purpose model is not described as Pidgin-trained unless that is verified.", 12) }
        Section("Dataset attribution")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            secondary("Slang dictionary: ${entries ?: "?"} entries in this build. Each entry shows its source, license, confidence and verification status. Current seed entries are project-authored drafts, labeled synthetic and not yet verified by native speakers. It is a small supporting dictionary, not a language corpus.", 13) }
        Section("Speech attribution")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            secondary("Speech recognition and voices run through sherpa-onnx (Apache-2.0) and ONNX Runtime. Voices and recognisers you import keep their own licenses (shown on the Voice screen): Piper voices are CC BY-SA 4.0, Kokoro is Apache-2.0, Whisper and Moonshine (English) are MIT. Piper and Kokoro text-to-speech use eSpeak NG phoneme data and code (GPL-3.0-or-later). No Nigerian accent voice is included.", 12) }
        Section("Limitations")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Reply quality depends entirely on the model you import. It can be wrong, stiff, or invent facts.", "Slang meanings the model has not learned may be guessed. Check anything important.",
                "Speed and memory depend on your phone. Large models may not run.", "Version $version is an early build. Offline behaviour should be confirmed on your own device.").forEach { secondary("• $it", 13) } }
    }
}
