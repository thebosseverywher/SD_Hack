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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
// Disambiguate the Material3 Surface composable from the protocol `Surface` data class,
// which lives in this same package (com.flow.app) and would otherwise shadow the
// wildcard import per Kotlin name resolution.
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Single-activity Compose UI (spec §6.1):
 *   - Pairing screen (QR scan via CameraX + ML Kit)
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
            MaterialTheme(colorScheme = lightColorScheme()) {
                M3Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FlowRoot(vm)
                }
            }
        }
    }
}

private enum class Screen { Consent, Pair, Ask }

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flow") },
                actions = { Text(state.statusLine, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodySmall) }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.Ask,
                    onClick = { screen = Screen.Ask },
                    icon = { Icon(Icons.Default.Search, null) },
                    label = { Text("Ask") }
                )
                NavigationBarItem(
                    selected = screen == Screen.Pair,
                    onClick = { screen = Screen.Pair },
                    icon = { Icon(Icons.Default.Search, null) },
                    label = { Text("Pair") }
                )
                NavigationBarItem(
                    selected = screen == Screen.Consent,
                    onClick = { screen = Screen.Consent },
                    icon = { Icon(Icons.Default.Search, null) },
                    label = { Text("Privacy") }
                )
            }
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Your data stays on your device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Flow indexes your photos and phone activity locally to make them searchable. " +
                "All AI runs on the device's NPU. Nothing is uploaded to any cloud. " +
                "When you pair with your laptop, only encrypted search snippets you explicitly " +
                "ask for cross the local network."
        )
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What is captured", fontWeight = FontWeight.SemiBold)
                Text("• Trove — your photo library: OCR text, type (wifi/receipt/…), a small thumbnail.")
                Text("• Trail — apps you open and text you type, as a private timeline.")
                Spacer(Modifier.height(6.dp))
                Text("Never captured", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text("• Passwords and OTP fields — these are detected and skipped before anything is stored.")
            }
        }

        Text("Sensors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SensorRow("Trove (photos)", state.troveOn, onSetTrove)
        SensorRow("Trail (activity)", state.trailOn, onSetTrail)

        Button(onClick = onGrantPerms, modifier = Modifier.fillMaxWidth()) {
            Text("Grant photo / camera / notification permissions")
        }
        OutlinedButton(onClick = { onOpenAccessibility(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("Enable Trail (Accessibility) in Settings")
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue to pairing") }
    }
}

@Composable
private fun SensorRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PairScreen(state: UiState, onQr: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Scan the QR shown on your laptop", style = MaterialTheme.typography.titleMedium)
        if (state.paired) {
            Text("Paired with ${state.peerName}", color = MaterialTheme.colorScheme.primary)
        }
        ElevatedCard(Modifier.fillMaxWidth().weight(1f)) {
            QrScanner(modifier = Modifier.fillMaxSize(), onQr = onQr)
        }
        Text(
            "The laptop renders { ip, port, psk } as a QR (shared/protocol.md §Pairing). " +
                "Scanning derives the session key locally; traffic is encrypted end-to-end.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun AskScreen(state: UiState, onQuery: (String) -> Unit, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ask Flow (e.g. \"where did I park\", \"wifi password\")") },
            trailingIcon = {
                IconButton(onClick = onSubmit) { Icon(Icons.Default.Search, "Search") }
            },
            singleLine = true
        )
        if (state.asking) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (state.answer.isNotBlank()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Answer", fontWeight = FontWeight.SemiBold)
                    Text(state.answer)
                }
            }
        }

        Text("Results", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.results, key = { it.item_id }) { hit -> ResultRow(hit) }
        }
    }
}

@Composable
private fun ResultRow(hit: Hit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Thumbnail (decoded from base64 jpeg) if present.
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
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceBadge(hit.device_id)
                    Spacer(Modifier.width(6.dp))
                    AssistChip(onClick = {}, label = { Text("${hit.source}/${hit.type}") })
                }
                Spacer(Modifier.height(4.dp))
                Text(hit.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            Text(String.format("%.2f", hit.score), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DeviceBadge(deviceId: String) {
    M3Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            deviceId.take(8),
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** Deep-link to the system Accessibility settings so the user can enable Trail (spec §3.2). */
private fun openAccessibilitySettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
