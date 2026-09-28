package app.ankialarm

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

data class SetupStatus(
    val ankiInstalled: Boolean = true,
    val ankiPermission: Boolean = true,
    val notifications: Boolean = true,
    val exactAlarms: Boolean = true,
    val fullScreen: Boolean = true,
) {
    companion object {
        fun check(c: Context) = SetupStatus(
            ankiInstalled = AnkiDroid.isInstalled(c),
            ankiPermission = AnkiDroid.hasPermission(c),
            notifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            exactAlarms = AlarmScheduler.canScheduleExact(c),
            fullScreen = Build.VERSION.SDK_INT < 34 ||
                c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
        )
    }
}

private class SetupItem(val title: String, val detail: String, val action: String, val onClick: () -> Unit)

class MainActivity : ComponentActivity() {
    private var alarms by mutableStateOf(emptyList<Alarm>())
    private var decks by mutableStateOf(emptyList<AnkiDroid.Deck>())
    private var setup by mutableStateOf(SetupStatus())
    private var syncAfter by mutableStateOf(true)
    private val askedPermissions = mutableSetOf<String>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private var wakeCode by mutableStateOf<String?>(null)
    private var registeringCode by mutableStateOf(false)
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            registeringCode = true
        } else {
            Toast.makeText(this, "Allow camera access in the app's settings to scan a wake-up code.", Toast.LENGTH_LONG).show()
            openAppSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AnkiAlarmTheme { MainScreen() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        alarms = AlarmStore.all(this)
        setup = SetupStatus.check(this)
        syncAfter = AlarmStore.syncAfterAlarm(this)
        wakeCode = AlarmStore.wakeCode(this)
        lifecycleScope.launch {
            decks = withContext(Dispatchers.IO) {
                if (AnkiDroid.isAvailable(this@MainActivity)) {
                    runCatching { AnkiDroid.decks(this@MainActivity) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
        }
    }

    private fun saveAlarm(alarm: Alarm, announce: Boolean = true) {
        AlarmStore.save(this, alarm)
        AlarmScheduler.schedule(this, alarm)
        alarms = AlarmStore.all(this)
        if (announce && alarm.enabled) {
            val until = formatUntil(AlarmScheduler.nextTrigger(alarm))
            Toast.makeText(this, "Alarm set for $until from now", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteAlarm(id: Int) {
        AlarmScheduler.cancel(this, id)
        AlarmStore.delete(this, id)
        alarms = AlarmStore.all(this)
    }

    /** Asks once; if Android won't show the prompt again, open this app's settings page instead. */
    private fun requestPermission(permission: String) {
        if (askedPermissions.add(permission)) permissionLauncher.launch(permission) else openAppSettings()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action, Uri.fromParts("package", packageName, null))) }
            .onFailure { openAppSettings() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        var editing by remember { mutableStateOf<Alarm?>(null) }
        val current = editing
        if (current != null) {
            EditAlarmScreen(
                initial = current,
                isNew = alarms.none { it.id == current.id },
                decks = decks,
                onCancel = { editing = null },
                onSave = {
                    saveAlarm(it)
                    editing = null
                },
                onTest = {
                    saveAlarm(it, announce = false)
                    editing = null
                    AlarmService.start(this, it.id, snoozeCount = 0, test = true)
                },
                onDelete = {
                    deleteAlarm(it.id)
                    editing = null
                },
            )
            return
        }
        Scaffold(
            topBar = { TopAppBar(title = { Text("Anki Alarm") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { editing = Alarm(id = AlarmStore.newId(this), hour = 7, minute = 0) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add alarm")
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SetupChecklist() }
                if (alarms.isEmpty()) {
                    item {
                        Text(
                            "No alarms yet. Tap + to add one.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(alarms, key = { it.id }) { alarm ->
                    AlarmRow(
                        alarm = alarm,
                        onClick = { editing = alarm },
                        onToggle = { on -> saveAlarm(alarm.copy(enabled = on), announce = on) },
                    )
                }
                item { CardPicturesCard() }
                item { WakeCodeCard() }
                item { SettingsCard() }
                item { AboutFooter() }
            }
        }
    }

    @Composable
    private fun SetupChecklist() {
        val s = setup
        val items = buildList {
            if (!s.ankiInstalled) {
                add(SetupItem("Install AnkiDroid", "Your cards come from AnkiDroid. Until it's installed, alarms ask math questions instead.", "Install") { AnkiDroid.openStore(this@MainActivity) })
            } else if (!s.ankiPermission) {
                add(SetupItem("Allow access to AnkiDroid", "Needed to show your due cards and save your answers.", "Allow") { requestPermission(AnkiDroid.PERMISSION) })
            }
            if (!s.notifications && Build.VERSION.SDK_INT >= 33) {
                add(SetupItem("Allow notifications", "The ringing alarm is shown as a notification.", "Allow") { requestPermission(Manifest.permission.POST_NOTIFICATIONS) })
            }
            if (!s.exactAlarms && Build.VERSION.SDK_INT >= 31) {
                add(SetupItem("Allow alarms & reminders", "Lets the alarm ring at the exact minute.", "Open") { openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) })
            }
            if (!s.fullScreen && Build.VERSION.SDK_INT >= 34) {
                add(SetupItem("Allow full-screen alarms", "Lets your cards appear over the lock screen.", "Open") { openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) })
            }
        }
        if (items.isEmpty()) return
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Finish setup", style = MaterialTheme.typography.titleMedium)
                items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(onClick = item.onClick) { Text(item.action) }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AlarmRow(alarm: Alarm, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        formatTime(this@MainActivity, alarm.hour, alarm.minute),
                        style = MaterialTheme.typography.displaySmall,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else muted,
                    )
                    Text(
                        listOfNotNull(alarm.label.ifBlank { null }, describeDays(alarm.days)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${cardsText(alarm.cardsToReview)} · ${alarm.deckName ?: "current deck"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }
        }
    }

    @Composable
    private fun CardPicturesCard() {
        var count by remember { mutableIntStateOf(CardMedia.count(this@MainActivity)) }
        var adding by remember { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            adding = true
            lifecycleScope.launch {
                val added = withContext(Dispatchers.IO) {
                    runCatching { CardMedia.addFromPackage(this@MainActivity, uri) }.getOrNull()
                }
                adding = false
                count = CardMedia.count(this@MainActivity)
                val message = when (added) {
                    null -> "Couldn't read that file. Pick an .apkg or .colpkg exported from Anki."
                    0 -> "No pictures in that file. When exporting, tick \"Include media\"."
                    else -> "Added $added pictures"
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Card pictures", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "AnkiDroid doesn't let other apps see your card pictures. To show them, export your deck in AnkiDroid " +
                        "(deck menu \u2192 Export \u2192 Anki deck package, with \"Include media\" ticked) and add the file here. " +
                        "Add it again after you add new pictures to your cards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (count > 0) {
                    Text("$count pictures saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !adding) {
                        Text(if (adding) "Adding\u2026" else "Add deck file")
                    }
                    if (count > 0 && !adding) {
                        TextButton(onClick = {
                            CardMedia.clear(this@MainActivity)
                            count = 0
                        }) { Text("Remove all") }
                    }
                }
            }
        }
    }

    private fun startRegisteringCode() {
        if (hasCameraPermission(this)) registeringCode = true else cameraLauncher.launch(Manifest.permission.CAMERA)
    }

    @Composable
    private fun WakeCodeCard() {
        val code = wakeCode
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wake-up code", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    if (code == null) {
                        "Scan any barcode or QR code: a toothpaste tube, a printed QR on the bathroom mirror, a coffee jar. " +
                            "Alarms with \"Scan your wake-up code first\" won't show cards until you get up and scan it."
                    } else {
                        "Saved (${code.take(32)}${if (code.length > 32) "…" else ""}). Alarms with \"Scan your wake-up code first\" need it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { startRegisteringCode() }) { Text(if (code == null) "Scan a code" else "Change code") }
                    if (code != null) {
                        TextButton(onClick = {
                            AlarmStore.setWakeCode(this@MainActivity, null)
                            wakeCode = null
                        }) { Text("Remove") }
                    }
                }
            }
        }
        if (registeringCode) {
            RegisterCodeDialog(
                onDismiss = { registeringCode = false },
                onCode = {
                    AlarmStore.setWakeCode(this@MainActivity, it)
                    wakeCode = it
                    registeringCode = false
                    Toast.makeText(this@MainActivity, "Wake-up code saved", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    @Composable
    private fun SettingsCard() {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sync after waking up", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "When you're done, unlock your phone and AnkiDroid syncs your reviews to AnkiWeb, and from there to Anki on your computer.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = syncAfter,
                    onCheckedChange = {
                        syncAfter = it
                        AlarmStore.setSyncAfterAlarm(this@MainActivity, it)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditAlarmScreen(
    initial: Alarm,
    isNew: Boolean,
    decks: List<AnkiDroid.Deck>,
    onCancel: () -> Unit,
    onSave: (Alarm) -> Unit,
    onTest: (Alarm) -> Unit,
    onDelete: (Alarm) -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onCancel)
    val time = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    var days by remember { mutableStateOf(initial.days) }
    var label by remember { mutableStateOf(initial.label) }
    var cards by remember { mutableIntStateOf(initial.cardsToReview) }
    var deck by remember { mutableStateOf(initial.deckId?.let { AnkiDroid.Deck(it, initial.deckName ?: "Deck $it") }) }
    var snooze by remember { mutableIntStateOf(initial.snoozeMinutes) }
    var soundUri by remember { mutableStateOf(initial.soundUri) }
    var soundName by remember { mutableStateOf(initial.soundName) }
    var shakes by remember { mutableIntStateOf(initial.shakesPerCard) }
    var requireScan by remember { mutableStateOf(initial.requireScan) }
    var gentleStart by remember { mutableStateOf(initial.gentleStart) }
    val hasWakeCode = remember { AlarmStore.wakeCode(context) != null }

    fun build() = initial.copy(
        hour = time.hour,
        minute = time.minute,
        days = days,
        label = label.trim(),
        cardsToReview = cards,
        deckId = deck?.id,
        deckName = deck?.name,
        snoozeMinutes = snooze,
        soundUri = soundUri,
        soundName = soundName,
        shakesPerCard = shakes,
        requireScan = requireScan,
        gentleStart = gentleStart,
        enabled = true,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New alarm" else "Edit alarm") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { onDelete(initial) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { onTest(build()) }, modifier = Modifier.weight(1f)) { Text("Save & test") }
                Button(onClick = { onSave(build()) }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = time) }
            Section("Repeat") { DayPicker(days) { days = it } }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Section("Cards to review before it stops") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalButton(onClick = { cards = (cards - 1).coerceAtLeast(1) }) { Text("−") }
                    Text("$cards", style = MaterialTheme.typography.headlineSmall)
                    FilledTonalButton(onClick = { cards = (cards + 1).coerceAtMost(30) }) { Text("+") }
                }
            }
            Section("Get out of bed") {
                SwitchRow(
                    title = "Scan your wake-up code first",
                    detail = if (hasWakeCode) {
                        "No cards until you get up and scan the code you saved."
                    } else {
                        "Save a wake-up code on the main screen to use this."
                    },
                    checked = requireScan && hasWakeCode,
                    enabled = hasWakeCode,
                    onChange = { requireScan = it },
                )
            }
            Section("Volume") {
                SwitchRow(
                    title = "Gentle start",
                    detail = "Starts quietly and reaches full volume after 30 seconds.",
                    checked = gentleStart,
                    onChange = { gentleStart = it },
                )
                Text(
                    "While you're answering, the alarm drops to 30%. Stop touching the screen for 30 seconds and it's back at full volume.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section("Shakes before each card") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalButton(onClick = { shakes = (shakes - SHAKE_STEP).coerceAtLeast(0) }) { Text("−") }
                    Text(if (shakes == 0) "Off" else "$shakes", style = MaterialTheme.typography.headlineSmall)
                    FilledTonalButton(onClick = { shakes = (shakes + SHAKE_STEP).coerceAtMost(50) }) { Text("+") }
                }
                Text(
                    "Each card stays hidden until you shake your phone this many times, so you can't answer half asleep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section("Deck") { DeckPicker(deck, decks) { deck = it } }
            Section("Sound") {
                SoundPicker(soundUri, soundName) { uri, name ->
                    soundUri = uri
                    soundName = name
                }
            }
            Section("Snooze") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 10).forEach { m ->
                        FilterChip(
                            selected = snooze == m,
                            onClick = { snooze = m },
                            label = { Text(if (m == 0) "Off" else "$m min") },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private const val SHAKE_STEP = 5

@Composable
private fun AboutFooter() {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Made by Pete", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text(
            "Free for anyone to use, for any exam. Not for resale.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RegisterCodeDialog(onDismiss: () -> Unit, onCode: (String) -> Unit) {
    var done by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.8f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Point the camera at your code", style = MaterialTheme.typography.titleMedium)
                BarcodeScanner(
                    onCode = {
                        if (!done) {
                            done = true
                            onCode(it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SoundPicker(uri: String?, name: String?, onChange: (uri: String?, name: String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember { SoundPreview(context) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    LaunchedEffect(uri) { preview.stop() }
    var open by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(Sounds.imported(context)) }
    var adding by remember { mutableStateOf(false) }

    val phoneSoundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picked = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
        if (result.resultCode == Activity.RESULT_OK && picked != null) {
            onChange(picked.toString(), Sounds.ringtoneTitle(context, picked) ?: "Phone sound")
            open = false
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        adding = true
        scope.launch {
            val sound = withContext(Dispatchers.IO) { Sounds.add(context, source) }
            adding = false
            if (sound == null) {
                Toast.makeText(context, "Couldn't use that file. Pick an audio file (MP3, M4A, OGG, WAV…) under 30 MB.", Toast.LENGTH_LONG).show()
            } else {
                imported = Sounds.imported(context)
                onChange(sound.uri, sound.name)
                open = false
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Text(name ?: "Default alarm sound", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        FilledTonalButton(onClick = { if (preview.playing) preview.stop() else preview.play(uri) }) {
            Text(if (preview.playing) "Stop" else "Play")
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Alarm sound") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SoundOption("Default alarm sound", selected = uri == null) { onChange(null, null) }
                    if (uri != null && !Sounds.isImported(context, uri)) {
                        SoundOption(name ?: "Phone sound", selected = true) {}
                    }
                    imported.forEach { sound ->
                        SoundOption(
                            title = sound.name,
                            selected = uri == sound.uri,
                            onDelete = {
                                preview.stop()
                                Sounds.delete(context, sound)
                                // Alarms that used this file go back to the default sound.
                                AlarmStore.all(context).filter { it.soundUri == sound.uri }.forEach {
                                    AlarmStore.save(context, it.copy(soundUri = null, soundName = null))
                                }
                                imported = Sounds.imported(context)
                                if (uri == sound.uri) onChange(null, null)
                            },
                            onClick = { onChange(sound.uri, sound.name) },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = {
                        runCatching { phoneSoundPicker.launch(phoneSoundIntent(context, uri)) }
                            .onFailure { Toast.makeText(context, "This phone has no sound picker.", Toast.LENGTH_SHORT).show() }
                    }) { Text("Choose a phone sound…") }
                    TextButton(onClick = { filePicker.launch(arrayOf("audio/*")) }, enabled = !adding) {
                        Text(if (adding) "Adding…" else "Add from file…")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
        )
    }
}

private fun phoneSoundIntent(context: Context, current: String?): Intent {
    val existing = current?.takeUnless { Sounds.isImported(context, it) }?.let(Uri::parse)
    return Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
}

@Composable
private fun SoundOption(title: String, selected: Boolean, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove $title") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun DayPicker(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        WEEK.forEach { day ->
            val selected = day in days
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onChange(if (selected) days - day else days + day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
    Text(
        if (days.isEmpty()) "Once, then it turns itself off" else describeDays(days),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeckPicker(selected: AnkiDroid.Deck?, decks: List<AnkiDroid.Deck>, onSelect: (AnkiDroid.Deck?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.name ?: "Current deck in AnkiDroid",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Current deck in AnkiDroid") },
                onClick = {
                    onSelect(null)
                    open = false
                },
            )
            decks.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.name) },
                    onClick = {
                        onSelect(d)
                        open = false
                    },
                )
            }
        }
    }
    if (decks.isEmpty()) {
        Text(
            "Allow access to AnkiDroid on the main screen to pick a specific deck.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
