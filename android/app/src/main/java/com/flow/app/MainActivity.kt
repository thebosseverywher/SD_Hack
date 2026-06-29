package com.flow.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
// Disambiguate the Material3 Surface composable from the protocol `Surface` data class,
// which lives in this same package (com.flow.app) and would otherwise shadow the
// wildcard import per Kotlin name resolution.
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Single-activity Compose UI (spec §6.1), restyled to a neo-brutalist look using the
 * shared components in NeoBrutalism.kt (NeoTheme tokens + Neo* composables).
 *
 * Flow is mobile-only and always-on: there is NO laptop, NO pairing, NO federation. The UI
 * is three tabs — Travis (a chat grounded in on-device memory), Memory (the ambient memory
 * being built), and Privacy (consent + sensor toggles). All capture, retrieval, and
 * generation happen on-device; passwords/OTP are never captured.
 */
class MainActivity : ComponentActivity() {

    private val vm: FlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge; the Scaffold + bars re-apply system-bar insets below so nothing
        // (especially the hard-offset neo shadows) gets clipped by the status/nav bars.
        enableEdgeToEdge()
        // Start the foreground indexing service (no-op extra work until perms granted).
        IndexingService.start(this)
        setContent {
            // Neo-brutalist root: black canvas so raised dark-blue blocks pop.
            M3Surface(Modifier.fillMaxSize(), color = NeoTheme.bgCream) {
                FlowRoot(vm)
            }
        }
    }
}

private enum class Screen { Travis, Memory, Privacy }

@Composable
private fun FlowRoot(vm: FlowViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Travis) }

    // Permissions we need up front: media + notifications (no camera — pairing is gone).
    val perms = buildList {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled lazily by the features themselves */ }

    val navItems = listOf(
        NeoNavItem("travis", "Travis", Icons.Default.Search),
        NeoNavItem("memory", "Memory", Icons.Default.Star),
        NeoNavItem("privacy", "Privacy", Icons.Default.Lock),
    )
    val selectedId = when (screen) {
        Screen.Travis -> "travis"
        Screen.Memory -> "memory"
        Screen.Privacy -> "privacy"
    }

    Scaffold(
        containerColor = NeoTheme.bgCream,
        // Apply the safe area to the body; the bars below add their own bar insets so the
        // top bar sits under the status bar and the bottom nav above the gesture/nav bar.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    // A little extra room so the top bar's 6dp hard shadow isn't clipped.
                    .padding(start = 12.dp, end = 18.dp, top = 8.dp)
            ) {
                NeoTopBar(title = "Flow", status = state.statusLine)
            }
        },
        bottomBar = {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                NeoBottomNav(
                    items = navItems,
                    selectedId = selectedId,
                    onSelect = { id ->
                        screen = when (id) {
                            "travis" -> Screen.Travis
                            "memory" -> Screen.Memory
                            else -> Screen.Privacy
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (screen) {
                Screen.Travis -> TravisScreen(
                    state = state,
                    onQuery = vm::setQuery,
                    onSend = vm::sendMessage,
                    onNewChat = vm::newConversation
                )
                Screen.Memory -> MemoryScreen(
                    state = state,
                    notes = vm.recentNotes(),
                    activity = vm.recentActivity()
                )
                Screen.Privacy -> ConsentScreen(
                    state = state,
                    onGrantPerms = { permLauncher.launch(perms) },
                    onSetTrove = vm::setTrove,
                    onSetTrail = vm::setTrail,
                    onOpenAccessibility = { ctx -> openAccessibilitySettings(ctx) },
                    onGoToTravis = { screen = Screen.Travis }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Travis chat
// ---------------------------------------------------------------------------

@Composable
private fun TravisScreen(
    state: UiState,
    onQuery: (String) -> Unit,
    onSend: () -> Unit,
    onNewChat: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            // Extra end/bottom inset so the hard 6dp offset shadows have room to draw.
            .padding(start = 16.dp, top = 14.dp, end = 22.dp, bottom = 14.dp)
    ) {
        // Conversation header: a "New chat" affordance that clears Travis's persisted thread.
        // Only shown once there's something to clear, and disabled mid-generation.
        if (state.messages.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Conversation",
                    fontWeight = FontWeight.Black, fontSize = 18.sp, color = NeoTheme.ink
                )
                Spacer(Modifier.weight(1f))
                NeoOutlineButton(
                    text = "New chat",
                    onClick = onNewChat,
                    enabled = !state.asking
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // Readiness gate: Flow observes & distills phone activity into memory and loads the
        // on-device model first; chatting is unlocked once both are ready.
        if (!state.canAsk) {
            NeoCard(backgroundColor = NeoTheme.surfaceMint, modifier = Modifier.fillMaxWidth()) {
                Text("Getting Flow ready…", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = NeoTheme.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.modelReady)
                        (if (state.modelLoaded) "✓ On-device model loaded"
                        else "✓ Ready (fallback — no model file pushed)")
                    else "⏳ Loading the on-device model…",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, color = NeoTheme.ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (state.dataReady) "✓ ${state.indexedCount} memories collected from this phone"
                    else "⏳ Observing your phone & building memory… (${state.indexedCount}/${state.dataTarget})",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, color = NeoTheme.ink
                )
                if (state.indexedCount == 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Grant permissions on the Privacy tab so Flow can build a memory Travis can answer about.",
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, color = NeoTheme.inkMuted
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Scrollable transcript: user bubbles right (blue), Travis bubbles left (dark blue).
        val listState = rememberLazyListState()
        // Autoscroll to the newest turn on (a) a new turn AND (b) streamed token growth of the
        // in-progress reply — keying on the last turn's length so tokens keep it pinned to bottom.
        val lastLen = state.messages.lastOrNull()?.content?.length ?: 0
        LaunchedEffect(state.messages.size, lastLen) {
            if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.messages.isEmpty()) {
                item {
                    NeoCard(backgroundColor = NeoTheme.surfaceWhite, modifier = Modifier.fillMaxWidth()) {
                        Text("Meet Travis", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = NeoTheme.ink)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Travis is your private, on-device memory companion. Ask things like " +
                                "\"where did I park\", \"what's the wifi password\", or \"what was I just reading\". " +
                                "Travis answers only from what your phone has quietly observed — nothing leaves the device.",
                            fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
                        )
                    }
                }
            }
            items(state.messages.size) { i -> MessageBubble(state.messages[i]) }
            // A quiet, single-line grounding cue — NOT a dump of rows. Travis stays
            // conversational; the full memories live on the Memory tab.
            if (state.lastHits.isNotEmpty() && !state.asking) {
                item {
                    Text(
                        "🧠 recalled from ${state.lastHits.size} ${if (state.lastHits.size == 1) "memory" else "memories"} · see Memory tab",
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = NeoTheme.inkMuted,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }
        }

        // Thin progress bar while Travis is recalling / generating.
        if (state.asking) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .border(NeoTheme.borderWidth, NeoTheme.ink, NeoTheme.shape)
                    .padding(NeoTheme.borderWidth)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(NeoTheme.primaryGreen)
                )
            }
        }

        // Telemetry line: which execution provider is doing the embeddings + memory size.
        Spacer(Modifier.height(8.dp))
        Text(
            "Embeddings: ${state.embedEpLabel}  •  ${state.memoryCount} memories",
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = NeoTheme.inkMuted
        )
        Spacer(Modifier.height(8.dp))

        // Input row: bound to state.query, send enabled only once Travis is ready.
        NeoTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            label = "Ask Travis",
            placeholder = "e.g. \"where did I park\"",
            singleLine = true,
            enabled = state.canAsk,
            trailingIcon = {
                IconButton(onClick = onSend, enabled = state.canAsk && !state.asking) {
                    Icon(
                        Icons.Default.Send,
                        "Send",
                        tint = if (state.canAsk && !state.asking) NeoTheme.ink else NeoTheme.inkMuted
                    )
                }
            }
        )
    }
}

/** One chat bubble: user turns align right on a blue card, Travis turns left on a dark-blue card. */
@Composable
private fun MessageBubble(turn: Travis.Turn) {
    val isUser = turn.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        NeoCard(
            backgroundColor = if (isUser) NeoTheme.primaryGreen else NeoTheme.surfaceMint,
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.fillMaxWidth(0.86f)
        ) {
            Text(
                if (isUser) "You" else "Travis",
                fontWeight = FontWeight.Bold, fontSize = 12.sp, color = NeoTheme.ink
            )
            Spacer(Modifier.height(3.dp))
            Text(
                turn.content.ifBlank { "…" },
                fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Memory browser
// ---------------------------------------------------------------------------

@Composable
private fun MemoryScreen(state: UiState, notes: List<Item>, activity: List<Item>) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 14.dp, end = 22.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Ambient memory",
            fontWeight = FontWeight.Black, fontSize = 28.sp, lineHeight = 32.sp, color = NeoTheme.ink
        )
        Text(
            "Everything Travis has quietly observed on this phone, newest first. " +
                "${state.memoryCount} items indexed on-device.",
            fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.inkMuted
        )

        if (notes.isEmpty() && activity.isEmpty()) {
            NeoCard(backgroundColor = NeoTheme.surfaceWhite, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No memories yet. Grant permissions on the Privacy tab and use your phone for a " +
                        "bit — Flow builds this timeline in the background.",
                    fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
                )
            }
            return@Column
        }

        val grouped: List<Pair<Long, List<Item>>> =
            activity.groupBy { startOfDay(it.ts) }.toList().sortedByDescending { it.first }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (notes.isNotEmpty()) {
                item {
                    Text(
                        "What Travis remembers",
                        fontWeight = FontWeight.Black, fontSize = 18.sp, color = NeoTheme.ink
                    )
                }
                items(notes, key = { it.id }) { note -> MemoryNoteCard(note) }
            }
            if (grouped.isNotEmpty()) {
                item {
                    Text(
                        "Recent activity", modifier = Modifier.padding(top = 6.dp),
                        fontWeight = FontWeight.Black, fontSize = 18.sp, color = NeoTheme.ink
                    )
                }
                grouped.forEach { (day, rows) ->
                    item(key = "h$day") {
                        Text(
                            dayHeaderOf(day / 1000), modifier = Modifier.padding(top = 2.dp),
                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = NeoTheme.inkMuted
                        )
                    }
                    items(rows, key = { it.id }) { item -> MemoryActivityRow(item) }
                }
            }
        }
    }
}

@Composable
private fun MemoryNoteCard(note: Item) {
    NeoCard(
        backgroundColor = NeoTheme.surfaceMint,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            note.text,
            fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp, color = NeoTheme.ink
        )
        Spacer(Modifier.height(6.dp))
        Text(
            timeLabel(note.ts),
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = NeoTheme.inkMuted
        )
    }
}

@Composable
private fun MemoryActivityRow(item: Item) {
    NeoCard(
        backgroundColor = NeoTheme.surfaceWhite,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                friendlyApp(item.app_context) ?: "Phone",
                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = NeoTheme.ink
            )
            Spacer(Modifier.weight(1f))
            Text(
                timeLabel(item.ts),
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = NeoTheme.inkMuted
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.text,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
        )
    }
}

private fun timeLabel(unixSeconds: Long): String {
    val now = System.currentTimeMillis()
    val t = unixSeconds * 1000
    val startToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        t >= startToday               -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
        t >= startToday - 86_400_000L -> "Yesterday"
        now - t < 7L * 86_400_000L    -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
        else                          -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t))
    }
}

private fun dayHeaderOf(unixSeconds: Long): String {
    val t = unixSeconds * 1000
    val startToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        t >= startToday               -> "Today"
        t >= startToday - 86_400_000L -> "Yesterday"
        else                          -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(t))
    }
}

private fun startOfDay(unixSeconds: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = unixSeconds * 1000
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

// ---------------------------------------------------------------------------
// Privacy / consent
// ---------------------------------------------------------------------------

@Composable
private fun ConsentScreen(
    state: UiState,
    onGrantPerms: () -> Unit,
    onSetTrove: (Boolean) -> Unit,
    onSetTrail: (Boolean) -> Unit,
    onOpenAccessibility: (Context) -> Unit,
    onGoToTravis: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Extra end/bottom inset so the hard 6dp offset shadows have room to draw.
            .padding(start = 20.dp, top = 20.dp, end = 26.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Your data stays on your device",
            fontWeight = FontWeight.Black,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            color = NeoTheme.ink
        )
        Text(
            "Flow runs quietly in the background, building a private memory only you can ask " +
                "Travis about. It indexes your photos and phone activity locally and all AI runs " +
                "on the device's NPU. Nothing leaves your phone.",
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = NeoTheme.ink
        )
        NeoCard(backgroundColor = NeoTheme.surfaceWhite) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What is captured", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = NeoTheme.ink)
                Text(
                    "• Trove — your photo library: OCR text, type (wifi/receipt/…), a small thumbnail.",
                    fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
                )
                Text(
                    "• Trail — apps you open and text you type, as a private timeline.",
                    fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
                )
                Spacer(Modifier.height(6.dp))
                Text("Never captured", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = NeoTheme.dangerRed)
                Text(
                    "• Passwords and OTP fields — these are detected and skipped before anything is stored.",
                    fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
                )
            }
        }

        Text("Sensors", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = NeoTheme.ink)
        NeoSwitchRow(label = "Trove (photos)", checked = state.troveOn, onCheckedChange = onSetTrove)
        NeoSwitchRow(label = "Trail (activity)", checked = state.trailOn, onCheckedChange = onSetTrail)

        NeoButton(
            text = "Grant photo / notification permissions",
            onClick = onGrantPerms,
            modifier = Modifier.fillMaxWidth()
        )
        NeoOutlineButton(
            text = "Enable Trail (Accessibility) in Settings",
            onClick = { onOpenAccessibility(ctx) },
            modifier = Modifier.fillMaxWidth()
        )
        NeoButton(
            text = "Go to Travis",
            onClick = onGoToTravis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------
// Grounding result row (reused under the latest Travis answer)
// ---------------------------------------------------------------------------

@Composable
private fun ResultRow(hit: Hit) {
    NeoCard(
        backgroundColor = NeoTheme.surfaceWhite,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Thumbnail (decoded from base64 jpeg) if present — own ink border, no shadow.
            hit.thumb_b64?.let { b64 ->
                val bmp = remember(b64) {
                    runCatching {
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(NeoTheme.borderWidth, NeoTheme.ink, RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                val app = friendlyApp(hit.app_context)
                Text(
                    buildString {
                        if (app != null) append(app).append(" · ")
                        append(timeLabel(hit.ts))
                    },
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = NeoTheme.inkMuted
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    hit.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
                )
            }
        }
    }
}

/** Deep-link to the system Accessibility settings so the user can enable Trail (spec §3.2). */
private fun openAccessibilitySettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
