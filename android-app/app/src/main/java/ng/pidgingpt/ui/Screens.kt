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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ng.pidgingpt.ChatViewModel
import ng.pidgingpt.ai.*
import ng.pidgingpt.net.CatalogEntry
import ng.pidgingpt.net.ModelCatalog
import ng.pidgingpt.net.OnlineAuth
import ng.pidgingpt.net.OnlineConfigValidator
import ng.pidgingpt.data.*
import ng.pidgingpt.inference.EngineState
import ng.pidgingpt.inference.GenState
import java.text.DateFormat
import java.util.Date

enum class Screen { Onboarding, Chat, History, Dictionary, Model, Voice, Settings, AiSettings, About }

const val TAGLINE = "Your Pidgin. Your AI. Offline."   // still true: offline works with no account, and online AI is off until you allow it

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
                Screen.AiSettings -> AiSettingsScreen(vm) { screen = Screen.Chat }
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

private fun providerName(k: String?): String = when (k) { "ONLINE" -> "Cloud AI (online)"; "LOCAL_PIDGIN" -> "Local Pidgin model"; "LOCAL_ENGLISH" -> "Local English model"; else -> "" }

private fun netText(n: NetworkState): String {
    val t = n.transport.name.lowercase().replaceFirstChar { it.uppercase() }
    return when { !n.known -> "Checking the network\u2026"; !n.connected -> "No network connection."
        !n.validated -> "Connected over $t, but Android found no working internet on this network (sign-in page, no data, or DNS problem)."
        else -> "Internet available over $t." }
}

// ---------------------------------------------------------------- onboarding

@Composable fun OnboardingScreen(done: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Background).padding(28.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp)); BrandMark(112.dp); Spacer(Modifier.height(20.dp))
        Text("ViNT", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.White)
        Spacer(Modifier.height(6.dp)); Text(TAGLINE, fontSize = 16.sp, color = Palette.TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        listOf(Icons.Default.AirplanemodeActive to "Works offline. Chat runs on your phone, so airplane mode is fine. Online AI is optional and off until you allow it.",
            Icons.Default.Lock to "Private. Chats stay on this device. Messages leave it only if you turn on online AI, and never in Offline Only.",
            Icons.Default.Memory to "Needs a model. Download the English model when you tap Download (about 1 GB), or import a .gguf yourself. Nothing downloads without your tap.").forEach { (i, t) ->
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
    val failures by vm.failures.collectAsState(); val lastRun by vm.lastRun.collectAsState(); val status by vm.providerStatus.collectAsState(); val restored by vm.restoredDraft.collectAsState()
    var input by remember { mutableStateOf("") }; var menu by remember { mutableStateOf(false) }; var confirmClear by remember { mutableStateOf(false) }
    val list = rememberLazyListState(); val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    val ready = status.selected != null; val busy = gen is GenState.Generating
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.content?.length) { if (msgs.isNotEmpty()) list.scrollToItem(msgs.lastIndex) }
    LaunchedEffect(restored) { restored?.let { input = it; vm.consumeDraft() } }        // a message is never lost when no model can answer: it comes back into the box

    if (confirmClear) VintDialog("Clear this conversation?", "This deletes it from your phone. It cannot be undone.", "Clear", { confirmClear = false; vm.deleteChat(vm.conversationId) }, { confirmClear = false })

    Scaffold(containerColor = Palette.Background, contentColor = Palette.White, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Background, titleContentColor = Palette.White, actionIconContentColor = Palette.White), title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(34.dp, active = busy); Spacer(Modifier.width(10.dp))
                Column { Text("ViNT", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(ready); Spacer(Modifier.width(6.dp))
                        Text(if (busy) (if (engine is EngineState.Loading) "Loading model\u2026 (the first reply can take a minute)" else "Generating\u2026") else status.label, fontSize = 11.sp, color = Palette.TextSecondary, maxLines = 1) } } } },
            actions = {
                IconButton({ vm.newChat() }, colors = vintIconButtonColors()) { Icon(Icons.Default.Add, "New conversation") }
                IconButton({ go(Screen.Voice) }, colors = vintIconButtonColors()) { Box(Modifier.semantics { contentDescription = "Voice chat" }) { BotHead(30.dp) } }
                IconButton({ go(Screen.History) }, colors = vintIconButtonColors()) { Icon(Icons.Default.History, "Conversation history") }
                Box {
                    IconButton({ menu = true }, colors = vintIconButtonColors()) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }, containerColor = Palette.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Palette.Border)) {
                        listOf("Slang dictionary" to Screen.Dictionary, "Model" to Screen.Model, "AI mode & online" to Screen.AiSettings, "Settings & privacy" to Screen.Settings, "About ViNT" to Screen.About)
                            .forEach { (n, s) -> DropdownMenuItem(text = { Text(n, color = Palette.White) }, onClick = { menu = false; go(s) }) }
                        HorizontalDivider(color = Palette.Border)
                        DropdownMenuItem(text = { Text("Clear this conversation", color = Palette.White) }, onClick = { menu = false; confirmClear = true })
                    } } })
    }, bottomBar = {
        Column(Modifier.background(Palette.Background).imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val g = gen) {
                is GenState.Failed -> {
                    Notice(g.message, onClick = { vm.dismissGenError() })
                    if (msgs.lastOrNull()?.role == "assistant") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VintOutlinedButton("Retry", { vm.retry() }); VintOutlinedButton("Retry offline", { vm.retry(ProviderKind.LOCAL_ENGLISH) }) }
                }
                is GenState.Cancelled -> Notice("Generation stopped after ${g.tokens} tokens. The partial reply is saved.", Icons.Default.Info) { vm.dismissGenError() }
                else -> {}
            }
            if (busy) VintProgress(Modifier.fillMaxWidth())
            else lastRun?.let { Text(listOfNotNull(it.providerLabel, it.detail, it.switchNote).joinToString(" \u00b7 "), fontSize = 10.sp, color = Palette.TextSecondary, modifier = Modifier.padding(start = 12.dp)) }
            if (!busy && status.willSendOnline) Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, tint = Palette.Green, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                Text("Your next message will be sent online (Cloud AI).", fontSize = 11.sp, color = Palette.White) }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), enabled = ready,
                    placeholder = { Text(if (ready) "Talk to ViNT…" else "Set up a model to chat") }, maxLines = 5,
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
                            if (m.content.isEmpty() && busy && m.id == msgs.lastOrNull()?.id) ThinkingBubble(engine is EngineState.Loading)
                            else Text(m.content.ifEmpty { "(no reply: ${failures[m.id] ?: "stopped"})" }, color = if (mine) Palette.OnGreen else Palette.White, lineHeight = 22.sp)
                            if (!mine && m.content.isNotEmpty() && providerName(m.provider).isNotEmpty()) secondary(providerName(m.provider), 10)
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
                secondary("ViNT answers with a language model that runs on your phone. None is installed yet. Download the English model or import a .gguf file. You can also turn on online AI in AI settings.", 14)
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
                .onSuccess { vm.settings.modelFileName = it.file.name; vm.registerImported(it); msg = "Imported ${it.file.name}. SHA-256 ${it.sha256}. Not loaded yet: tap Load." }
                .onFailure { err = it.message ?: "Import failed." }
            progress = null; vm.refreshModelState(); tick++
        }
    }
    val list = remember(tick, engine) { vm.models.list() }
    toDelete?.let { m -> VintDialog("Delete model?", "${m.file.name} (${Formatter.formatFileSize(ctx, m.sizeBytes)}) will be removed from this phone.", "Delete", { vm.deleteModel(m); toDelete = null; tick++ }, { toDelete = null }) }

    Page("Model", onBack) {
        secondary("ViNT runs a language model on your phone. Download the English model below (only when you tap Download), or import a .gguf file you already have. Models stay in this app\u2019s private storage.", 14)
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
        Section("Download (uses the internet, only when you tap)")
        ModelCatalog.all.forEach { DownloadCard(vm, it) { tick++ } }
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
                ProfileBlock(vm, m) { tick++ }
                SelfTestBlock(vm)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isReady) VintOutlinedButton("Unload", { vm.unloadModel() }) else VintButton("Load", { vm.loadModel(m.file.name) }, enabled = c.ok && engine !is EngineState.Loading)
                    VintOutlinedButton("Delete", { toDelete = m }) } }
        }
        secondary("“Ready” appears only after the on-device runtime has loaded the model successfully. A model that is merely installed is never reported as ready. A general-purpose model is not a Pidgin-trained model: see Settings → About for what is and is not verified.", 12)
    }
}

@Composable private fun DownloadCard(vm: ChatViewModel, entry: CatalogEntry, onDone: () -> Unit) {
    val ctx = LocalContext.current; val dl by vm.download.collectAsState(); var confirm by remember { mutableStateOf(false) }; var tick by remember { mutableIntStateOf(0) }
    val mine = dl?.takeIf { it.entryId == entry.id }
    val partial = remember(mine, tick) { vm.partialBytes(entry) }
    val installed = remember(mine) { vm.models.list().any { it.file.name == entry.fileName } }
    LaunchedEffect(mine?.finished) { if (mine?.finished == true) onDone() }
    fun sz(b: Long) = Formatter.formatFileSize(ctx, b)
    if (confirm) VintDialog("Download ${entry.displayName}?",
        "ViNT will use the internet to download this file:\n${entry.url}\n\nSize: ${entry.sizeText}\nLicense: ${entry.license}\nFree storage: ${sz(vm.models.freeStorageBytes())}\n\nIt is saved only on this phone and checked before it is installed. You can delete it any time. Nothing from your chats is sent.",
        if (partial > 0) "Resume" else "Download", { confirm = false; vm.downloadModel(entry) }, { confirm = false })
    Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(entry.displayName, fontWeight = FontWeight.Bold)
        secondary(entry.notes, 12)
        secondary("Size: ${entry.sizeText} \u00b7 License: ${entry.license} (${entry.licenseSource})", 12)
        secondary("Integrity: the file is checked against the checksum the file host reports, then validated as a GGUF. No pinned checksum is recorded for it yet.", 11)
        if (mine?.running == true) {
            val pct = if (mine.total > 0) " (${(mine.done * 100 / mine.total).toInt()}%)" else ""
            secondary("Downloading\u2026 ${sz(mine.done)}${if (mine.total > 0) " of ${sz(mine.total)}" else ""}$pct", 13); VintProgress(Modifier.fillMaxWidth())
            VintOutlinedButton("Cancel", { vm.cancelDownload() })
        } else {
            mine?.message?.let { if (mine.finished) secondary(it, 12) else Notice(it) }
            if (!installed) VintButton(if (partial > 0) "Resume download (${sz(partial)} already saved)" else "Download", { confirm = true }, Modifier.fillMaxWidth())
            else secondary("Installed.", 12)
            if (partial > 0 && !installed) VintOutlinedButton("Discard partial file", { vm.discardPartial(entry); tick++ })
        }
    }
}

@Composable private fun SelfTestBlock(vm: ChatViewModel) {
    val t by vm.selfTest.collectAsState()
    VintOutlinedButton("Test model (load + say hello)", { vm.runSelfTest() })
    t?.let { secondary(it, 12) }
}

@Composable private fun ProfileBlock(vm: ChatViewModel, m: ModelInfo, onChange: () -> Unit) {
    val p = vm.profileOf(m); var dialog by remember { mutableStateOf(false) }
    secondary("Source: ${p.provenance}", 11)
    p.integrity?.let { secondary("Integrity: $it", 11) }
    secondary("Runtime on this phone: " + when (p.runtime) { RuntimeStatus.LOADED_OK -> "loaded successfully"; RuntimeStatus.LOAD_FAILED -> "failed to load" + (p.runtimeNote?.let { " ($it)" } ?: ""); RuntimeStatus.UNVERIFIED -> "not tested yet" }, 11)
    secondary(if (p.pidginEligible) "Pidgin: marked available (${if (p.pidginEvidence == Evidence.EVALUATED) "you evaluated it" else "you configured it"}). It answers Pidgin requests; the English model stays the fallback."
        else "Pidgin: unverified. This model is used for English until you mark it as your Pidgin model.", 11)
    if (p.pidginEligible) VintOutlinedButton("Stop using as Pidgin model", { vm.unmarkPidgin(m); onChange() })
    else VintOutlinedButton("This is my Pidgin model\u2026", { dialog = true })
    if (dialog) PidginDialog({ dialog = false }) { ev, note -> vm.markPidginCapable(m, ev, note); dialog = false; onChange() }
}

@Composable private fun PidginDialog(onDismiss: () -> Unit, onConfirm: (Evidence, String) -> Unit) {
    var ev by remember { mutableStateOf<Evidence?>(null) }; var note by remember { mutableStateOf("") }
    AlertDialog({ onDismiss() }, shape = CardShape, containerColor = Palette.Surface, tonalElevation = 0.dp, titleContentColor = Palette.White, textContentColor = Palette.TextSecondary,
        title = { Text("Use as Pidgin model") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            secondary("Only for a model that really was trained or tested on Nigerian Pidgin. General models such as Qwen are NOT Pidgin models: do not mark them. Choose the true statement:", 13)
            Row(Modifier.clickable { ev = Evidence.EVALUATED }, verticalAlignment = Alignment.CenterVertically) { RadioButton(ev == Evidence.EVALUATED, { ev = Evidence.EVALUATED }, colors = vintRadioColors()); Text("I evaluated it on Pidgin and it passed", fontSize = 13.sp, color = Palette.White) }
            Row(Modifier.clickable { ev = Evidence.USER_CONFIGURED }, verticalAlignment = Alignment.CenterVertically) { RadioButton(ev == Evidence.USER_CONFIGURED, { ev = Evidence.USER_CONFIGURED }, colors = vintRadioColors()); Text("It is my own Pidgin model and I choose to use it", fontSize = 13.sp, color = Palette.White) }
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true, shape = PillShape, colors = vintTextFieldColors()) } },
        confirmButton = { VintTextButton("Use it", { ev?.let { onConfirm(it, note) } }) },
        dismissButton = { TextButton({ onDismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = Palette.White)) { Text("Cancel") } })
}

// ---------------------------------------------------------------- AI settings (mode, online service, language, voice)

@Composable private fun ChoiceRow(selected: Boolean, label: String, hint: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected, { onClick() }, enabled = enabled, colors = vintRadioColors()); Spacer(Modifier.width(6.dp))
        Column { Text(label, fontSize = 14.sp, color = if (enabled) Palette.White else Palette.TextDisabled); if (hint != null) secondary(hint, 12) }
    }
}

@Composable private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 14.sp); secondary(hint, 12) }
        Spacer(Modifier.width(8.dp)); VintSwitch(checked, onChange)
    }
}

@Composable fun AiSettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by vm.providerStatus.collectAsState(); val net by vm.connectivity.state.collectAsState()
    var mode by remember { mutableStateOf(vm.settings.aiMode) }; var lang by remember { mutableStateOf(vm.settings.responseLanguage) }
    var pref by remember { mutableStateOf(vm.settings.preferredProvider) }; var consent by remember { mutableStateOf(vm.settings.onlineConsent) }
    var url by remember { mutableStateOf(vm.settings.onlineBaseUrl) }; var model by remember { mutableStateOf(vm.settings.onlineModel) }
    var auth by remember { mutableStateOf(vm.settings.onlineAuth) }; var secret by remember { mutableStateOf("") }; var hasSecret by remember { mutableStateOf(vm.secrets.hasSecret) }
    var voiceOnline by remember { mutableStateOf(vm.settings.onlineInVoice) }; var speak by remember { mutableStateOf(vm.settings.speakReplies) }
    var help by remember { mutableStateOf(vm.settings.pidginHelp) }
    var testMsg by remember { mutableStateOf<String?>(null) }; var testing by remember { mutableStateOf(false) }
    var askConsent by remember { mutableStateOf(false) }; var clear by remember { mutableStateOf(false) }
    fun changed() { vm.onAiSettingsChanged() }
    val problems = OnlineConfigValidator.validate(vm.onlineConfig(), hasSecret)
    val keyLooksLikeProviderKey = auth == OnlineAuth.PROXY && secret.isNotBlank() && OnlineConfigValidator.looksLikeProviderKey(secret)

    if (askConsent) VintDialog("Allow online AI?",
        "When ViNT uses the online service, the text of your messages and the recent conversation are sent over HTTPS to the address you configure. That service has its own privacy policy, and may charge you. Microphone audio is never sent. Offline only always blocks this. You can switch it off at any time.",
        "Allow", { askConsent = false; consent = true; vm.settings.onlineConsent = true; changed() }, { askConsent = false })
    if (clear) VintDialog("Clear all history?", "Every saved conversation will be deleted from your phone.", "Clear all", { vm.clearAllChats(); clear = false }, { clear = false })

    Page("AI mode & online", onBack) {
        Section("Right now")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(status.selected != null); Spacer(Modifier.width(8.dp)); Text(status.label, fontWeight = FontWeight.SemiBold) }
            status.detail?.let { secondary(it, 12) }
            secondary("Network: ${netText(net)}", 12)
            secondary("This shows the provider the next message would use, not just whether Wi-Fi is connected.", 11)
        }

        Section("AI mode")
        Column(Modifier.fillMaxWidth().vintCard().padding(8.dp)) {
            ChoiceRow(mode == AiMode.AUTOMATIC, "Automatic", "Online when it is allowed and the internet works, otherwise on this phone. A verified local Pidgin model answers Pidgin first.") { mode = AiMode.AUTOMATIC; vm.settings.aiMode = mode; changed() }
            ChoiceRow(mode == AiMode.ONLINE_PREFERRED, "Online preferred", "Always try online first when allowed. Falls back to this phone if the service fails.") { mode = AiMode.ONLINE_PREFERRED; vm.settings.aiMode = mode; changed() }
            ChoiceRow(mode == AiMode.OFFLINE_ONLY, "Offline only", "Nothing you type is ever sent online. Only models on this phone answer.") { mode = AiMode.OFFLINE_ONLY; vm.settings.aiMode = mode; changed() }
        }
        secondary("A reply already in progress is never restarted when the connection changes. The new setting applies to your next message.", 12)

        Section("Model choice")
        Column(Modifier.fillMaxWidth().vintCard().padding(8.dp)) {
            ChoiceRow(pref == null, "Automatic") { pref = null; vm.settings.preferredProvider = null; changed() }
            ChoiceRow(pref == ProviderKind.LOCAL_ENGLISH, "Local English model first") { pref = ProviderKind.LOCAL_ENGLISH; vm.settings.preferredProvider = pref; changed() }
            ChoiceRow(pref == ProviderKind.LOCAL_PIDGIN, "Local Pidgin model first", "Only works once you have marked a model as your Pidgin model (Model screen).") { pref = ProviderKind.LOCAL_PIDGIN; vm.settings.preferredProvider = pref; changed() }
            ChoiceRow(pref == ProviderKind.ONLINE, "Online first", "Ignored in Offline only mode.") { pref = ProviderKind.ONLINE; vm.settings.preferredProvider = pref; changed() }
        }

        Section("Reply language")
        Column(Modifier.fillMaxWidth().vintCard().padding(8.dp)) {
            ChoiceRow(lang == ResponseLanguage.AUTOMATIC, "Automatic", "ViNT estimates from your message and follows you, including mixed English and Pidgin. Your words are never translated or changed.") { lang = ResponseLanguage.AUTOMATIC; vm.settings.responseLanguage = lang; changed() }
            ChoiceRow(lang == ResponseLanguage.ENGLISH, "English") { lang = ResponseLanguage.ENGLISH; vm.settings.responseLanguage = lang; changed() }
            ChoiceRow(lang == ResponseLanguage.PIDGIN, "Nigerian Pidgin", "The style (natural or strict) is set in Settings & privacy.") { lang = ResponseLanguage.PIDGIN; vm.settings.responseLanguage = lang; changed() }
        }
        secondary("An English-only model\u2019s Pidgin is unverified. Treat it as an observed ability, not fluency.", 12)
        OutlinedTextField(help, { help = it; vm.settings.pidginHelp = it }, Modifier.fillMaxWidth(), label = { Text("Instruction for English models about Pidgin") }, minLines = 2, maxLines = 5, shape = RoundedCornerShape(20.dp), colors = vintTextFieldColors())
        VintOutlinedButton("Reset instruction", { help = PromptBuilder.DEFAULT_PIDGIN_HELP; vm.settings.pidginHelp = help })

        Section("Online service")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SwitchRow("Allow sending my messages online", "Off by default. Needed before any message can leave this phone.", consent) { on -> if (on) askConsent = true else { consent = false; vm.settings.onlineConsent = false; changed() } }
            secondary("Works with any OpenAI-compatible chat endpoint over HTTPS. For anything you share with other people, point this at your own backend proxy, which holds the provider\u2019s secret. ViNT cannot know your provider\u2019s price or quota: check them yourself.", 12)
            OutlinedTextField(url, { url = it; vm.settings.onlineBaseUrl = it; changed() }, Modifier.fillMaxWidth(), label = { Text("Service address (https://\u2026/v1)") }, singleLine = true, shape = PillShape, colors = vintTextFieldColors())
            OutlinedTextField(model, { model = it; vm.settings.onlineModel = it; changed() }, Modifier.fillMaxWidth(), label = { Text("Model name") }, singleLine = true, shape = PillShape, colors = vintTextFieldColors())
            ChoiceRow(auth == OnlineAuth.PROXY, "My backend proxy (recommended)", "No provider key on this phone. An access token for your proxy is optional.") { auth = OnlineAuth.PROXY; vm.settings.onlineAuth = auth; changed() }
            ChoiceRow(auth == OnlineAuth.DIRECT_DEV_KEY, "My own provider key (testing only)", if (vm.isDebuggableBuild) "Stored encrypted on this phone. Not secure for public distribution." else "Disabled: only debug builds may hold a provider key.", enabled = vm.isDebuggableBuild) { auth = OnlineAuth.DIRECT_DEV_KEY; vm.settings.onlineAuth = auth; changed() }
            if (auth == OnlineAuth.DIRECT_DEV_KEY) Notice("A key typed into an app can be extracted from the phone. Use a key with a spending limit, and never ship this build to other people.", Icons.Default.Info)
            OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text(if (auth == OnlineAuth.PROXY) "Proxy access token (optional)" else "Provider key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = PillShape, colors = vintTextFieldColors())
            if (keyLooksLikeProviderKey) Notice("That looks like a provider API key. Do not put one in the proxy token field: the proxy should hold it.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VintButton("Save", { vm.secrets.put(secret); secret = ""; hasSecret = vm.secrets.hasSecret; changed() }, enabled = secret.isNotBlank() && !keyLooksLikeProviderKey)
                VintOutlinedButton("Remove saved", { vm.secrets.put(null); hasSecret = false; changed() }, enabled = hasSecret)
            }
            secondary(if (hasSecret) "A secret is saved (encrypted, never shown again)." else "No secret saved.", 12)
            problems.forEach { secondary("\u2022 $it", 12) }
            VintOutlinedButton(if (testing) "Testing\u2026" else "Test connection", {
                testing = true; testMsg = null
                scope.launch(Dispatchers.IO) { val r = vm.onlineProvider.testConnection(); testMsg = r.fold({ it }, { it.message ?: "The test failed." }); testing = false; if (r.isSuccess) vm.onAiSettingsChanged() }
            }, enabled = !testing && mode != AiMode.OFFLINE_ONLY && problems.isEmpty())
            secondary(if (mode == AiMode.OFFLINE_ONLY) "Test is off in Offline only mode." else "Sends one tiny test message that contains none of your chats.", 11)
            testMsg?.let { secondary(it, 12) }
        }

        Section("Voice")
        Column(Modifier.fillMaxWidth().vintCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SwitchRow("Speak replies in voice mode", "Needs a voice installed (Voice screen). Voices run on this phone and do not need the internet.", speak) { speak = it; vm.settings.speakReplies = it }
            SwitchRow("Allow online AI in voice mode", "Off by default. Speech recognition and speech output always stay on this phone. If you turn this on, only the recognised text is sent online, never audio.", voiceOnline) { voiceOnline = it; vm.settings.onlineInVoice = it; changed() }
            secondary("No installed voice is a tested Nigerian accent. Voices are labelled honestly on the Voice screen.", 12)
        }

        Section("History")
        VintOutlinedButton("Clear all local conversation history", { clear = true }, Modifier.fillMaxWidth().padding(bottom = 12.dp))
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
            row(null, if (hasInternet == true) "This build can use the internet for two things only: online AI (off until you allow it, and never used in Offline only mode) and model downloads you start yourself." else if (hasInternet == false) "This build declares no network permission, so online AI and downloads cannot work." else "Could not read this app's permissions.")()
            row(true, "Replies come from the model on this phone unless you allow online AI in AI settings. The status line always names the provider.")()
            row(true, "Microphone is used only while voice mode is on and you are talking. Speech recognition and speech output run on this phone. Audio is never saved or sent.")()
            row(true, "Chats, settings and the slang dictionary are stored only on this phone.")()
            row(null, "This checks what the app declares. It is not a traffic audit. To check for yourself, switch on airplane mode, or choose Offline only, and chat.")() }
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
                "Online AI, if you turn it on, is a third-party service with its own terms, price and privacy policy. ViNT cannot verify them.", "Speed and memory depend on your phone. Large models may not run.", "Version $version is an early build. Offline behaviour should be confirmed on your own device.").forEach { secondary("• $it", 13) } }
    }
}
