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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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

/**
 * Single-activity Compose UI (spec §6.1), restyled to a neo-brutalist look using the
 * shared components in NeoBrutalism.kt (NeoTheme tokens + Neo* composables):
 *   - Pairing screen (QR scan via CameraX + ML Kit, plus paste-JSON fallback)
 *   - Query box + results list (source-device badge + thumbnail)
 *   - Sensor toggles + a clear consent screen
 *     ("what's captured, passwords excluded, all on-device")
 */
class MainActivity : ComponentActivity() {

    private val vm: FlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the foreground indexing service (no-op extra work until perms granted).
        IndexingService.start(this)
        setContent {
            // Neo-brutalist root: cream canvas so raised white/mint/green blocks pop.
            M3Surface(Modifier.fillMaxSize(), color = NeoTheme.bgCream) {
                FlowRoot(vm)
            }
        }
        handlePairIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairIntent(intent)
    }

    /**
     * Dev hook: pair directly from an adb intent extra, bypassing the camera/QR —
     *   adb shell am start -n com.flow.app/.MainActivity --es pair_json '{"ip":...}'
     * Wrapped so any failure (parse, crypto init, dial) is logged at ERROR instead of
     * crashing, and surfaced on the status line.
     */
    private fun handlePairIntent(intent: Intent?) {
        val json = intent?.getStringExtra("pair_json") ?: return
        try {
            vm.onPairingScanned(json)
            android.util.Log.e("Flow/Pair", "intent pair invoked ok")
        } catch (t: Throwable) {
            android.util.Log.e("Flow/Pair", "intent pair failed: ${t.message}", t)
        }
    }
}

private enum class Screen { Consent, Pair, Ask }

@Composable
private fun FlowRoot(vm: FlowViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Consent) }

    // Permissions we need up front: media, notifications, camera (for pairing).
    val perms = buildList {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled lazily by the features themselves */ }

    val navItems = listOf(
        NeoNavItem("ask", "Ask", Icons.Default.Search),
        NeoNavItem("pair", "Pair", Icons.Default.Search),
        NeoNavItem("privacy", "Privacy", Icons.Default.Search),
    )
    val selectedId = when (screen) {
        Screen.Ask -> "ask"
        Screen.Pair -> "pair"
        Screen.Consent -> "privacy"
    }

    Scaffold(
        containerColor = NeoTheme.bgCream,
        topBar = { NeoTopBar(title = "Flow", status = state.statusLine) },
        bottomBar = {
            NeoBottomNav(
                items = navItems,
                selectedId = selectedId,
                onSelect = { id ->
                    screen = when (id) {
                        "ask" -> Screen.Ask
                        "pair" -> Screen.Pair
                        else -> Screen.Consent
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (screen) {
                Screen.Consent -> ConsentScreen(
                    state = state,
                    onGrantPerms = { permLauncher.launch(perms) },
                    onSetTrove = vm::setTrove,
                    onSetTrail = vm::setTrail,
                    onOpenAccessibility = { ctx -> openAccessibilitySettings(ctx) },
                    onContinue = { screen = Screen.Pair }
                )
                Screen.Pair -> PairScreen(state) { qr -> vm.onPairingScanned(qr); screen = Screen.Ask }
                Screen.Ask -> AskScreen(
                    state = state,
                    onQuery = vm::setQuery,
                    onSubmit = vm::runQuery
                )
            }
        }
    }
}

@Composable
private fun ConsentScreen(
    state: UiState,
    onGrantPerms: () -> Unit,
    onSetTrove: (Boolean) -> Unit,
    onSetTrail: (Boolean) -> Unit,
    onOpenAccessibility: (Context) -> Unit,
    onContinue: () -> Unit
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
            "Flow indexes your photos and phone activity locally to make them searchable. " +
                "All AI runs on the device's NPU. Nothing is uploaded to any cloud. " +
                "When you pair with your laptop, only encrypted search snippets you explicitly " +
                "ask for cross the local network.",
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
            text = "Grant photo / camera / notification permissions",
            onClick = onGrantPerms,
            modifier = Modifier.fillMaxWidth()
        )
        NeoOutlineButton(
            text = "Enable Trail (Accessibility) in Settings",
            onClick = { onOpenAccessibility(ctx) },
            modifier = Modifier.fillMaxWidth()
        )
        NeoButton(
            text = "Continue to pairing",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PairScreen(state: UiState, onQr: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 16.dp, end = 22.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Scan the QR shown on your laptop",
            fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = NeoTheme.ink
        )
        if (state.paired) {
            NeoBadge(text = "Paired with ${state.peerName}", backgroundColor = NeoTheme.primaryGreen)
        }
        NeoCard(
            backgroundColor = NeoTheme.surfaceWhite,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            QrScanner(modifier = Modifier.fillMaxSize(), onQr = onQr)
        }
        Text(
            "The laptop renders { ip, port, psk } as a QR (shared/protocol.md §Pairing). " +
                "Scanning derives the session key locally; traffic is encrypted end-to-end.",
            fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = NeoTheme.ink
        )
        // Manual fallback: paste the pairing JSON (the same payload encoded in the QR)
        // for emulators / setups where the camera can't see the laptop screen.
        var manual by remember { mutableStateOf("") }
        NeoTextField(
            value = manual,
            onValueChange = { manual = it },
            modifier = Modifier.fillMaxWidth(),
            label = "…or paste pairing JSON",
            singleLine = true
        )
        NeoButton(
            text = "Pair from pasted JSON",
            onClick = { if (manual.isNotBlank()) onQr(manual.trim()) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AskScreen(state: UiState, onQuery: (String) -> Unit, onSubmit: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 16.dp, end = 22.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Readiness gate: Flow observes & indexes phone data and loads the on-device model
        // first; asking is only unlocked once both are ready.
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
                    if (state.dataReady) "✓ Indexed ${state.indexedCount} items from this phone"
                    else "⏳ Observing & indexing your phone data… (${state.indexedCount}/${state.dataTarget})",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, color = NeoTheme.ink
                )
                if (state.indexedCount == 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Grant permissions on the Privacy tab so Flow can index your photos & activity.",
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, color = NeoTheme.inkMuted
                    )
                }
            }
        }
        NeoTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            label = "Ask Flow (e.g. \"where did I park\", \"wifi password\")",
            singleLine = true,
            enabled = state.canAsk,
            trailingIcon = {
                IconButton(onClick = onSubmit, enabled = state.canAsk) {
                    Icon(Icons.Default.Search, "Search", tint = if (state.canAsk) NeoTheme.ink else NeoTheme.inkMuted)
                }
            }
        )
        if (state.asking) {
            // Flat primaryGreen bar inside a bordered track — no rounded Material indicator.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
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

        if (state.answer.isNotBlank()) {
            NeoCard(backgroundColor = NeoTheme.surfaceMint, modifier = Modifier.fillMaxWidth()) {
                Text("Answer", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = NeoTheme.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    state.answer,
                    fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
                )
            }
        }

        Text("Results", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = NeoTheme.ink)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.results, key = { it.item_id }) { hit -> ResultRow(hit) }
        }
    }
}

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeoBadge(text = hit.device_id.take(8), backgroundColor = NeoTheme.surfaceMint)
                    Spacer(Modifier.width(6.dp))
                    NeoBadge(text = "${hit.source}/${hit.type}", backgroundColor = NeoTheme.primaryGreen)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    hit.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, color = NeoTheme.ink
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                String.format("%.2f", hit.score),
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = NeoTheme.ink
            )
        }
    }
}

/** Deep-link to the system Accessibility settings so the user can enable Trail (spec §3.2). */
private fun openAccessibilitySettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
