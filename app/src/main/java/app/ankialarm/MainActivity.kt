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
import androidx.compose.ui.res.stringResource
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
    private var strictMode by mutableStateOf(false)
    private var strictServiceOn by mutableStateOf(false)
    private val askedPermissions = mutableSetOf<String>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private var wakeCode by mutableStateOf<String?>(null)
    private var registeringCode by mutableStateOf(false)
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            registeringCode = true
        } else {
            Toast.makeText(this, getString(R.string.camera_needed), Toast.LENGTH_LONG).show()
            openAppSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AnkiAlarmTheme { MainScreen() } }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Lang.wrap(newBase))

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        alarms = AlarmStore.all(this)
        setup = SetupStatus.check(this)
        syncAfter = AlarmStore.syncAfterAlarm(this)
        wakeCode = AlarmStore.wakeCode(this)
        strictMode = AlarmStore.strictMode(this)
        strictServiceOn = StrictModeService.isEnabled(this)
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
            val until = formatUntil(this, AlarmScheduler.nextTrigger(alarm))
            Toast.makeText(this, getString(R.string.alarm_set_in, until), Toast.LENGTH_SHORT).show()
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
            topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { editing = Alarm(id = AlarmStore.newId(this), hour = 7, minute = 0) }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_alarm))
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
                            stringResource(R.string.no_alarms),
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
                item { StrictModeCard() }
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
                add(SetupItem(getString(R.string.setup_install_anki_title), getString(R.string.setup_install_anki_detail), getString(R.string.action_install)) { AnkiDroid.openStore(this@MainActivity) })
            } else if (!s.ankiPermission) {
                add(SetupItem(getString(R.string.setup_anki_access_title), getString(R.string.setup_anki_access_detail), getString(R.string.action_allow)) { requestPermission(AnkiDroid.PERMISSION) })
            }
            if (!s.notifications && Build.VERSION.SDK_INT >= 33) {
                add(SetupItem(getString(R.string.setup_notifications_title), getString(R.string.setup_notifications_detail), getString(R.string.action_allow)) { requestPermission(Manifest.permission.POST_NOTIFICATIONS) })
            }
            if (!s.exactAlarms && Build.VERSION.SDK_INT >= 31) {
                add(SetupItem(getString(R.string.setup_exact_title), getString(R.string.setup_exact_detail), getString(R.string.action_open)) { openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) })
            }
            if (!s.fullScreen && Build.VERSION.SDK_INT >= 34) {
                add(SetupItem(getString(R.string.setup_fullscreen_title), getString(R.string.setup_fullscreen_detail), getString(R.string.action_open)) { openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) })
            }
        }
        if (items.isEmpty()) return
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.finish_setup), style = MaterialTheme.typography.titleMedium)
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
                        listOfNotNull(alarm.label.ifBlank { null }, describeDays(this@MainActivity, alarm.days)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${cardsText(this@MainActivity, alarm.cardsToReview)} · ${alarm.deckName ?: stringResource(R.string.current_deck_short)}",
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
                    null -> getString(R.string.pictures_read_error)
                    0 -> getString(R.string.pictures_none)
                    else -> plural(R.plurals.pictures_added, added)
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pictures_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.pictures_detail),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (count > 0) {
                    Text(plural(R.plurals.pictures_saved, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !adding) {
                        Text(stringResource(if (adding) R.string.adding else R.string.pictures_add))
                    }
                    if (count > 0 && !adding) {
                        TextButton(onClick = {
                            CardMedia.clear(this@MainActivity)
                            count = 0
                        }) { Text(stringResource(R.string.remove_all)) }
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
                Text(stringResource(R.string.wake_code_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    if (code == null) {
                        stringResource(R.string.wake_code_empty)
                    } else {
                        stringResource(R.string.wake_code_saved, code.take(32) + if (code.length > 32) "…" else "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { startRegisteringCode() }) { Text(stringResource(if (code == null) R.string.wake_code_scan else R.string.wake_code_change)) }
                    if (code != null) {
                        TextButton(onClick = {
                            AlarmStore.setWakeCode(this@MainActivity, null)
                            wakeCode = null
                        }) { Text(stringResource(R.string.action_remove)) }
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
                    Toast.makeText(this@MainActivity, getString(R.string.wake_code_saved_toast), Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    @Composable
    private fun StrictModeCard() {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.strict_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = strictMode,
                        onCheckedChange = {
                            strictMode = it
                            AlarmStore.setStrictMode(this@MainActivity, it)
                        },
                    )
                }
                Text(stringResource(R.string.strict_detail), style = MaterialTheme.typography.bodySmall)
                if (strictMode) {
                    if (strictServiceOn) {
                        Text(
                            stringResource(R.string.strict_accessibility_on),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(stringResource(R.string.strict_needs_accessibility), style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }) {
                            Text(stringResource(R.string.action_open_accessibility))
                        }
                        Text(
                            stringResource(R.string.strict_restricted_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsCard() {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.sync_detail),
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
                LanguagePicker()
            }
        }
    }

    @Composable
    private fun LanguagePicker() {
        val current = Lang.get(this)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.language_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Lang.CHOICES.forEach { tag ->
                    FilterChip(
                        selected = current == tag,
                        onClick = {
                            if (tag != current) {
                                Lang.set(this@MainActivity, tag)
                                // Rebuild the screen so every text switches to the new language.
                                recreate()
                            }
                        },
                        label = {
                            Text(
                                stringResource(
                                    when (tag) {
                                        "en" -> R.string.language_english
                                        "th" -> R.string.language_thai
                                        else -> R.string.language_system
                                    },
                                ),
                            )
                        },
                    )
                }
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
    var deck by remember { mutableStateOf(initial.deckId?.let { AnkiDroid.Deck(it, initial.deckName ?: context.getString(R.string.deck_fallback, it.toString())) }) }
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
                title = { Text(stringResource(if (isNew) R.string.new_alarm else R.string.edit_alarm)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel)) }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { onDelete(initial) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { onTest(build()) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save_and_test)) }
                Button(onClick = { onSave(build()) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = time) }
            Section(stringResource(R.string.section_repeat)) { DayPicker(days) { days = it } }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.label_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Section(stringResource(R.string.section_cards)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalButton(onClick = { cards = (cards - 1).coerceAtLeast(1) }) { Text("−") }
                    Text("$cards", style = MaterialTheme.typography.headlineSmall)
                    FilledTonalButton(onClick = { cards = (cards + 1).coerceAtMost(30) }) { Text("+") }
                }
            }
            Section(stringResource(R.string.section_get_up)) {
                SwitchRow(
                    title = stringResource(R.string.scan_first_title),
                    detail = if (hasWakeCode) {
                        stringResource(R.string.scan_first_detail)
                    } else {
                        stringResource(R.string.scan_first_needs_code)
                    },
                    checked = requireScan && hasWakeCode,
                    enabled = hasWakeCode,
                    onChange = { requireScan = it },
                )
            }
            Section(stringResource(R.string.section_volume)) {
                SwitchRow(
                    title = stringResource(R.string.gentle_title),
                    detail = stringResource(R.string.gentle_detail),
                    checked = gentleStart,
                    onChange = { gentleStart = it },
                )
                Text(
                    stringResource(R.string.volume_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section(stringResource(R.string.section_shakes)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalButton(onClick = { shakes = (shakes - SHAKE_STEP).coerceAtLeast(0) }) { Text("−") }
                    Text(if (shakes == 0) stringResource(R.string.off) else "$shakes", style = MaterialTheme.typography.headlineSmall)
                    FilledTonalButton(onClick = { shakes = (shakes + SHAKE_STEP).coerceAtMost(50) }) { Text("+") }
                }
                Text(
                    stringResource(R.string.shakes_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section(stringResource(R.string.section_deck)) { DeckPicker(deck, decks) { deck = it } }
            Section(stringResource(R.string.section_sound)) {
                SoundPicker(soundUri, soundName) { uri, name ->
                    soundUri = uri
                    soundName = name
                }
            }
            Section(stringResource(R.string.section_snooze)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 10).forEach { m ->
                        FilterChip(
                            selected = snooze == m,
                            onClick = { snooze = m },
                            label = { Text(if (m == 0) stringResource(R.string.off) else stringResource(R.string.minutes_short, m)) },
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
        Text(stringResource(R.string.made_by), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text(
            stringResource(R.string.free_note),
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
                Text(stringResource(R.string.point_camera), style = MaterialTheme.typography.titleMedium)
                BarcodeScanner(
                    onCode = {
                        if (!done) {
                            done = true
                            onCode(it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_cancel)) }
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
            onChange(picked.toString(), Sounds.ringtoneTitle(context, picked) ?: context.getString(R.string.phone_sound))
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
                Toast.makeText(context, context.getString(R.string.sound_file_error), Toast.LENGTH_LONG).show()
            } else {
                imported = Sounds.imported(context)
                onChange(sound.uri, sound.name)
                open = false
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Text(name ?: stringResource(R.string.default_sound), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        FilledTonalButton(onClick = { if (preview.playing) preview.stop() else preview.play(uri) }) {
            Text(stringResource(if (preview.playing) R.string.stop else R.string.play))
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.alarm_sound_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SoundOption(stringResource(R.string.default_sound), selected = uri == null) { onChange(null, null) }
                    if (uri != null && !Sounds.isImported(context, uri)) {
                        SoundOption(name ?: stringResource(R.string.phone_sound), selected = true) {}
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
                            .onFailure { Toast.makeText(context, context.getString(R.string.no_sound_picker), Toast.LENGTH_SHORT).show() }
                    }) { Text(stringResource(R.string.choose_phone_sound)) }
                    TextButton(onClick = { filePicker.launch(arrayOf("audio/*")) }, enabled = !adding) {
                        Text(stringResource(if (adding) R.string.adding else R.string.add_from_file))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } },
        )
    }
}

private fun phoneSoundIntent(context: Context, current: String?): Intent {
    val existing = current?.takeUnless { Sounds.isImported(context, it) }?.let(Uri::parse)
    return Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.alarm_sound_title))
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
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_item, title)) }
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
                    day.getDisplayName(TextStyle.NARROW, LocalContext.current.locale()),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
    Text(
        if (days.isEmpty()) stringResource(R.string.once_then_off) else describeDays(LocalContext.current, days),
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
                selected?.name ?: stringResource(R.string.current_deck),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.current_deck)) },
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
            stringResource(R.string.deck_needs_access),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
