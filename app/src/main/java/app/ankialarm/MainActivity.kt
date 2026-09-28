package app.ankialarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
                item { SettingsCard() }
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

    fun build() = initial.copy(
        hour = time.hour,
        minute = time.minute,
        days = days,
        label = label.trim(),
        cardsToReview = cards,
        deckId = deck?.id,
        deckName = deck?.name,
        snoozeMinutes = snooze,
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
            Section("Deck") { DeckPicker(deck, decks) { deck = it } }
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
