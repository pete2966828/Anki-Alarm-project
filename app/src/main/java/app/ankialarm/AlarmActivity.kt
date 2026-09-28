package app.ankialarm

import android.app.Application
import android.app.KeyguardManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import kotlin.math.sqrt
import kotlin.random.Random

private const val MAX_SNOOZES = 3

/** Shown over the lock screen while the alarm rings. The alarm stops once enough cards are reviewed. */
class AlarmActivity : ComponentActivity() {
    private val vm: AlarmViewModel by viewModels()
    private var finishingAlarm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // No escaping with the back button: the cards are the way out.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            AnkiAlarmTheme {
                val ringing by AlarmService.ringing.collectAsStateWithLifecycle()
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(ringing) {
                    val r = ringing
                    if (r != null) {
                        vm.start(r, canShake = hasAccelerometer(), wakeCode = usableWakeCode(r.alarm))
                    } else if (!finishingAlarm) {
                        finish()
                    }
                }
                LaunchedEffect(state.finished) {
                    if (state.finished) finishAlarm()
                }
                val r = ringing
                AlarmScreen(
                    state = state,
                    label = r?.alarm?.label.orEmpty(),
                    snoozeMinutes = if (r != null && r.alarm.snoozeMinutes > 0 && r.snoozeCount < MAX_SNOOZES) {
                        r.alarm.snoozeMinutes
                    } else {
                        null
                    },
                    onShowAnswer = vm::showAnswer,
                    onEase = vm::answerCard,
                    onMathSubmit = vm::submitMath,
                    onShake = {
                        AlarmService.noteActivity()
                        vm.onShake()
                    },
                    onSkipShaking = vm::skipShaking,
                    onScanned = vm::onScanned,
                    onSkipScan = vm::skipScan,
                    onSnooze = { if (r != null) snooze(r) },
                )
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        AlarmService.noteActivity()
        return super.dispatchTouchEvent(ev)
    }

    /** The code to scan first, or null when this alarm doesn't need one (or scanning is impossible). */
    private fun usableWakeCode(alarm: Alarm): String? {
        if (!alarm.requireScan) return null
        val code = AlarmStore.wakeCode(this) ?: return null
        return if (hasCameraPermission(this)) code else null
    }

    private fun hasAccelerometer(): Boolean =
        getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    private fun finishAlarm() {
        finishingAlarm = true
        AlarmService.stop(this)
        if (!AlarmStore.syncAfterAlarm(this) || !AnkiDroid.isInstalled(this)) {
            finish()
            return
        }
        // AnkiDroid can only sync once the phone is unlocked, so ask for that first.
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            AnkiDroid.requestSync(this)
            finish()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                AnkiDroid.requestSync(this@AlarmActivity)
                finish()
            }

            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    private fun snooze(r: AlarmService.Ringing) {
        finishingAlarm = true
        AlarmScheduler.scheduleSnooze(this, r.alarm.id, r.alarm.snoozeMinutes, r.snoozeCount + 1)
        AlarmService.stop(this)
        Toast.makeText(this, "Snoozing for ${r.alarm.snoozeMinutes} min", Toast.LENGTH_SHORT).show()
        finish()
    }
}

sealed interface Task {
    data class Card(val card: AnkiDroid.DueCard, val shownAt: Long) : Task

    /** Fallback when AnkiDroid can't give us a card, so the alarm can always be turned off. */
    data class Math(val a: Int, val b: Int, val c: Int, val nonce: Long = System.nanoTime()) : Task {
        val text: String get() = "$a × $b + $c"
        val answer: Int get() = a * b + c
    }
}

data class AlarmUiState(
    val loading: Boolean = true,
    val done: Int = 0,
    val goal: Int = 1,
    val task: Task? = null,
    val answerShown: Boolean = false,
    val notice: String? = null,
    val finished: Boolean = false,
    /** Shakes still needed before the current card is revealed. */
    val shakesLeft: Int = 0,
    val shakesPerTask: Int = 0,
    /** Set while the wake-up code still has to be scanned before any card shows. */
    val wakeCode: String? = null,
    val wrongCode: Boolean = false,
)

class AlarmViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(AlarmUiState())
    val state = _state.asStateFlow()
    private var deckId: Long? = null
    private var started = false
    private var saveError: String? = null

    fun start(ringing: AlarmService.Ringing, canShake: Boolean, wakeCode: String?) {
        if (started) return
        started = true
        deckId = ringing.alarm.deckId
        _state.value = AlarmUiState(
            goal = ringing.alarm.cardsToReview.coerceAtLeast(1),
            // Without an accelerometer there's no way to shake, so skip the shaking.
            shakesPerTask = if (canShake) ringing.alarm.shakesPerCard.coerceAtLeast(0) else 0,
            wakeCode = wakeCode,
        )
        loadNext()
    }

    fun onScanned(value: String) = _state.update {
        val code = it.wakeCode ?: return@update it
        when {
            value.trim() == code.trim() -> {
                // The card's answer time starts now, not while you were walking to the code.
                val task = if (it.task is Task.Card) it.task.copy(shownAt = System.currentTimeMillis()) else it.task
                it.copy(wakeCode = null, wrongCode = false, task = task)
            }
            else -> it.copy(wrongCode = true)
        }
    }

    /** Escape hatch if the camera or the code is unusable. */
    fun skipScan() = _state.update { it.copy(wakeCode = null, wrongCode = false) }

    fun onShake() = _state.update {
        if (it.shakesLeft <= 0) return@update it
        val left = it.shakesLeft - 1
        // The card's answer time (saved to AnkiDroid) starts once it's actually revealed.
        val task = if (left == 0 && it.task is Task.Card) it.task.copy(shownAt = System.currentTimeMillis()) else it.task
        it.copy(shakesLeft = left, task = task)
    }

    /** Escape hatch if the sensor stops reporting: reveal the card without shaking. */
    fun skipShaking() = _state.update { it.copy(shakesLeft = 0) }

    fun showAnswer() = _state.update { it.copy(answerShown = true) }

    fun answerCard(ease: Int) {
        val task = _state.value.task as? Task.Card ?: return
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    AnkiDroid.answer(getApplication(), task.card, ease, System.currentTimeMillis() - task.shownAt)
                }.isSuccess
            }
            saveError = if (ok) null else "Couldn't save your last answer in AnkiDroid."
            _state.update { it.copy(done = it.done + 1) }
            loadNext()
        }
    }

    /** Returns false when the answer is wrong. */
    fun submitMath(input: String): Boolean {
        val task = _state.value.task as? Task.Math ?: return false
        if (input.trim().toIntOrNull() != task.answer) return false
        _state.update { it.copy(done = it.done + 1) }
        loadNext()
        return true
    }

    private fun loadNext() {
        if (_state.value.done >= _state.value.goal) {
            _state.update { it.copy(finished = true) }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val result: Pair<AnkiDroid.DueCard?, String?> = withContext(Dispatchers.IO) {
                when {
                    !AnkiDroid.isInstalled(ctx) -> null to "AnkiDroid isn't installed, so solve this instead."
                    !AnkiDroid.hasPermission(ctx) -> null to "Anki Alarm isn't allowed to read AnkiDroid yet, so solve this instead."
                    else -> try {
                        AnkiDroid.nextDueCard(ctx, deckId)?.let { it to null }
                            ?: (null to "No cards are due right now, so solve this instead.")
                    } catch (e: Exception) {
                        null to "Couldn't load a card from AnkiDroid, so solve this instead."
                    }
                }
            }
            val (card, notice) = result
            _state.update {
                it.copy(
                    loading = false,
                    answerShown = false,
                    task = card?.let { c -> Task.Card(c, System.currentTimeMillis()) } ?: randomMath(),
                    notice = notice ?: saveError,
                    shakesLeft = it.shakesPerTask,
                )
            }
        }
    }

    private fun randomMath() = Task.Math(Random.nextInt(6, 20), Random.nextInt(3, 10), Random.nextInt(10, 100))
}

@Composable
private fun AlarmScreen(
    state: AlarmUiState,
    label: String,
    snoozeMinutes: Int?,
    onShowAnswer: () -> Unit,
    onEase: (Int) -> Unit,
    onMathSubmit: (String) -> Boolean,
    onShake: () -> Unit,
    onSkipShaking: () -> Unit,
    onScanned: (String) -> Unit,
    onSkipScan: () -> Unit,
    onSnooze: () -> Unit,
) {
    val scanning = state.wakeCode != null
    val shaking = !scanning && state.task != null && state.shakesLeft > 0
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Clock()
                if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.titleMedium)
            }
            val remaining = (state.goal - state.done).coerceAtLeast(0)
            Text(
                if (remaining == 0) "Done!" else "Review ${cardsText(remaining)} to stop the alarm",
                style = MaterialTheme.typography.titleSmall,
            )
            LinearProgressIndicator(
                progress = { state.done.toFloat() / state.goal },
                modifier = Modifier.fillMaxWidth(),
            )
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (scanning) {
                    ScanGate(state.wrongCode, onScanned, onSkipScan)
                } else when (val task = state.task) {
                    null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> if (shaking) {
                        // Keyed on the task so the skip timer restarts for every card.
                        key(task) { ShakeGate(state.shakesLeft, state.shakesPerTask, onShake, onSkipShaking) }
                    } else {
                        TaskView(task, state.answerShown, onMathSubmit)
                    }
                }
            }
            val task = state.task
            if (task is Task.Card && !shaking && !scanning) {
                if (!state.answerShown) {
                    Button(
                        onClick = onShowAnswer,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text("Show answer", style = MaterialTheme.typography.titleMedium) }
                } else {
                    EaseButtons(task.card, enabled = !state.loading, onEase = onEase)
                }
            }
            if (snoozeMinutes != null) {
                TextButton(onClick = onSnooze, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Snooze $snoozeMinutes min")
                }
            }
        }
    }
}

@Composable
private fun TaskView(task: Task, answerShown: Boolean, onMathSubmit: (String) -> Boolean) {
    when (task) {
        is Task.Card -> ElevatedCard(Modifier.fillMaxSize()) {
            CardHtml(
                html = if (answerShown) task.card.answer else task.card.question,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
        is Task.Math -> MathTask(task, onMathSubmit)
    }
}

private const val SKIP_SHAKING_AFTER_MS = 90_000L
private const val SKIP_SCAN_AFTER_MS = 180_000L

@Composable
private fun ScanGate(wrongCode: Boolean, onScanned: (String) -> Unit, onSkip: () -> Unit) {
    // If the camera or the code is unusable, don't leave you stuck with a ringing phone forever.
    var showSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SKIP_SCAN_AFTER_MS)
        showSkip = true
    }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Get up and scan your wake-up code", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        ElevatedCard(Modifier.fillMaxWidth().weight(1f)) {
            BarcodeScanner(onCode = onScanned, modifier = Modifier.fillMaxSize())
        }
        if (wrongCode) {
            Text("That's not your wake-up code.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
        if (showSkip) {
            TextButton(onClick = onSkip) { Text("Can't scan it? Skip this step") }
        }
    }
}

@Composable
private fun ShakeGate(left: Int, total: Int, onShake: () -> Unit, onSkip: () -> Unit) {
    ShakeListener(onShake)
    // If no shake registers for a while (broken sensor, phone in a case that dampens it), offer a way out.
    var showSkip by remember { mutableStateOf(false) }
    LaunchedEffect(left) {
        showSkip = false
        delay(SKIP_SHAKING_AFTER_MS)
        showSkip = true
    }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📳", style = MaterialTheme.typography.displayLarge)
        Text("Shake your phone!", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("$left", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
        Text(
            if (left == 1) "more shake to see the card" else "more shakes to see the card",
            style = MaterialTheme.typography.bodyLarge,
        )
        LinearProgressIndicator(
            progress = { (total - left).toFloat() / total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        if (showSkip) {
            TextButton(onClick = onSkip) { Text("Shaking not working? Show the card") }
        }
    }
}

/** Calls onShake for each firm shake (or quick flip) of the phone. */
@Composable
private fun ShakeListener(onShake: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onShake)
    DisposableEffect(Unit) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val detector = ShakeDetector { latest() }
        if (accelerometer != null) sensors.registerListener(detector, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors?.unregisterListener(detector) }
    }
}

private class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {
    private val ref = FloatArray(3)
    private var refTime = -1L
    private var lastShake = 0L

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val now = event.timestamp / 1_000_000
        if (refTime < 0) {
            setRef(x, y, z, now)
            return
        }
        // A shake is either a strong jolt (well over gravity) or a big change in direction within ~0.1 s.
        val magnitude = sqrt(x * x + y * y + z * z)
        val dx = x - ref[0]
        val dy = y - ref[1]
        val dz = z - ref[2]
        val change = sqrt(dx * dx + dy * dy + dz * dz)
        if ((magnitude > STRONG_JOLT || change > BIG_CHANGE) && now - lastShake > MIN_GAP_MS) {
            lastShake = now
            setRef(x, y, z, now)
            onShake()
        } else if (now - refTime > REF_WINDOW_MS) {
            setRef(x, y, z, now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun setRef(x: Float, y: Float, z: Float, time: Long) {
        ref[0] = x
        ref[1] = y
        ref[2] = z
        refTime = time
    }

    private companion object {
        val STRONG_JOLT = 2.2f * SensorManager.GRAVITY_EARTH
        const val BIG_CHANGE = 13f
        const val MIN_GAP_MS = 300L
        const val REF_WINDOW_MS = 100L
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }
    Text(formatTime(context, now.hour, now.minute), style = MaterialTheme.typography.displayLarge)
}

@Composable
private fun EaseButtons(card: AnkiDroid.DueCard, enabled: Boolean, onEase: (Int) -> Unit) {
    val labels = when (card.buttonCount) {
        2 -> listOf("Again", "Good")
        3 -> listOf("Again", "Good", "Easy")
        else -> listOf("Again", "Hard", "Good", "Easy")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val colors = if (label == "Again") {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            }
            FilledTonalButton(
                onClick = { onEase(i + 1) },
                enabled = enabled,
                colors = colors,
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontWeight = FontWeight.SemiBold)
                    card.nextReviewTimes.getOrNull(i)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun MathTask(task: Task.Math, onSubmit: (String) -> Boolean) {
    var input by remember(task) { mutableStateOf("") }
    var wrong by remember(task) { mutableStateOf(false) }
    val submit = { wrong = !onSubmit(input) }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(task.text, style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
        OutlinedTextField(
            value = input,
            onValueChange = { v ->
                // Typing happens in the keyboard's window, which the activity's touch hook doesn't see.
                AlarmService.noteActivity()
                input = v.filter { it.isDigit() }.take(6)
                wrong = false
            },
            label = { Text("Answer") },
            isError = wrong,
            supportingText = { if (wrong) Text("Not quite, try again") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Button(onClick = { submit() }, enabled = input.isNotEmpty()) { Text("Check") }
    }
}

/** Card fields are HTML, so show them in a WebView (JavaScript off). */
@Composable
private fun CardHtml(html: String, modifier: Modifier) {
    val page = wrapHtml(html, isSystemInDarkTheme())
    val baseUrl = CardMedia.baseUrl(LocalContext.current)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                // Lets <img src="cat.jpg"> load pictures saved from the user's deck file.
                settings.allowFileAccess = true
                setBackgroundColor(Color.TRANSPARENT)
            }
        },
        update = { view ->
            // Only reload when the card changes, not on every recomposition.
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL(baseUrl, page, "text/html", "utf-8", null)
            }
        },
    )
}

private val SOUND_TAG = Regex("\\[sound:[^\\]]*]")

private fun wrapHtml(body: String, dark: Boolean): String {
    val text = if (dark) "#E6E1E5" else "#1C1B1F"
    return """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body { margin: 0; padding: 12px; background: transparent; color: $text;
            font-family: sans-serif; font-size: 22px; line-height: 1.4; text-align: center; overflow-wrap: anywhere; }
          img { max-width: 100%; height: auto; }
          hr { border: none; border-top: 1px solid rgba(128,128,128,.5); margin: 16px 0; }
        </style></head>
        <body>${body.replace(SOUND_TAG, "")}</body></html>
    """.trimIndent()
}
