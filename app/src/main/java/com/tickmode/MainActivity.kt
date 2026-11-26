package com.tickmode

import android.Manifest
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF080909)
private val Panel = Color(0xFF111313)
private val Raised = Color(0xFF1B1D1D)
private val White = Color(0xFFF0F1F1)
private val Grey = Color(0xFF9A9D9D)
private val Dim = Color(0xFF666A6A)
private val Line = Color(0xFF343737)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()
        setContent { TickModeApp(this) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

private enum class Destination { TIMER, PRESETS, HISTORY, SETTINGS, DETAIL }

@Composable
private fun TickModeApp(activity: MainActivity) {
    val oldUnit = SessionStore.settingString(activity, "interval_unit", "Minutes")
    val oldValue = SessionStore.settingLong(activity, "interval_value", 1L)
    val legacyMs = oldValue.coerceAtLeast(1L) * when (oldUnit) {
        "Seconds" -> 1_000L
        "Hours" -> 3_600_000L
        else -> 60_000L
    }
    val initialMs = SessionStore.settingLong(activity, "interval_ms", legacyMs).coerceIn(1_000L, 86_400_000L)

    var destination by remember { mutableStateOf(Destination.TIMER) }
    var onboarding by remember { mutableStateOf(!SessionStore.settingBoolean(activity, "onboarding_complete", false)) }
    var session by remember { mutableStateOf(SessionStore.current(activity)) }
    var history by remember { mutableStateOf(SessionStore.history(activity)) }
    var selectedDetail by remember { mutableStateOf<TickSession?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hours by remember { mutableStateOf((initialMs / 3_600_000L).toString().padStart(2, '0')) }
    var minutes by remember { mutableStateOf(((initialMs % 3_600_000L) / 60_000L).toString().padStart(2, '0')) }
    var seconds by remember { mutableStateOf(((initialMs % 60_000L) / 1_000L).toString().padStart(2, '0')) }
    var vibration by remember { mutableStateOf(SessionStore.settingBoolean(activity, "vibration", true)) }
    var pattern by remember { mutableStateOf(HapticEngine.normalizePattern(SessionStore.settingString(activity, "pattern", "Medium"))) }
    var sound by remember { mutableStateOf(SessionStore.settingBoolean(activity, "sound", true)) }
    var tone by remember { mutableStateOf(SessionStore.settingString(activity, "tone", "Beep")) }
    var customToneUri by remember { mutableStateOf(SessionStore.settingString(activity, "custom_tone_uri", "")) }
    var notice by remember { mutableStateOf("") }
    var showSignalDialog by remember { mutableStateOf(false) }

    val customAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                customToneUri = uri.toString()
                SessionStore.setString(activity, "custom_tone_uri", customToneUri)
                if (session != null) {
                    val update = Intent(activity, TickModeService::class.java).apply {
                        action = TickModeService.ACTION_UPDATE_SIGNAL
                        putExtra(TickModeService.EXTRA_CUSTOM_TONE_URI, customToneUri)
                    }
                    serviceActionWithIntent(activity, update)
                }
                notice = "Audio file selected."
            } catch (_: Exception) {
                notice = "Could not access that audio file."
            }
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notice = if (granted) "Notifications enabled." else "Allow notifications to show session controls."
    }

    LaunchedEffect(Unit) {
        while (true) {
            session = SessionStore.current(activity)
            history = SessionStore.history(activity)
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    fun intervalMs(): Long {
        val h = hours.toLongOrNull() ?: 0L
        val m = minutes.toLongOrNull() ?: 0L
        val s = seconds.toLongOrNull() ?: 0L
        return h * 3_600_000L + m * 60_000L + s * 1_000L
    }

    fun applyInterval(ms: Long) {
        val safe = ms.coerceIn(1_000L, 86_400_000L)
        hours = (safe / 3_600_000L).toString().padStart(2, '0')
        minutes = ((safe % 3_600_000L) / 60_000L).toString().padStart(2, '0')
        seconds = ((safe % 60_000L) / 1_000L).toString().padStart(2, '0')
    }

    fun saveDefaults() {
        if (intervalMs() in 1_000L..86_400_000L) SessionStore.setLong(activity, "interval_ms", intervalMs())
        SessionStore.setBoolean(activity, "vibration", vibration)
        SessionStore.setString(activity, "pattern", pattern)
        SessionStore.setBoolean(activity, "sound", sound)
        SessionStore.setString(activity, "tone", tone)
        SessionStore.setString(activity, "custom_tone_uri", customToneUri)
    }

    fun updateActiveSignal(vib: Boolean, pat: String, snd: Boolean, selectedTone: String, audioUri: String) {
        val update = Intent(activity, TickModeService::class.java).apply {
            action = TickModeService.ACTION_UPDATE_SIGNAL
            putExtra(TickModeService.EXTRA_VIBRATION, vib)
            putExtra(TickModeService.EXTRA_PATTERN, pat)
            putExtra(TickModeService.EXTRA_SOUND, snd)
            putExtra(TickModeService.EXTRA_TONE, selectedTone)
            putExtra(TickModeService.EXTRA_CUSTOM_TONE_URI, audioUri)
        }
        serviceActionWithIntent(activity, update)
    }

    fun startSession() {
        if (session != null) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val interval = intervalMs()
        val hourValue = hours.toLongOrNull() ?: 0L
        val minuteValue = minutes.toLongOrNull() ?: 0L
        val secondValue = seconds.toLongOrNull() ?: 0L
        if (interval !in 1_000L..86_400_000L || minuteValue !in 0L..59L || secondValue !in 0L..59L || hourValue !in 0L..24L) {
            notice = "Set a valid interval between 00:00:01 and 24:00:00."
            return
        }
        if (hourValue == 24L && (minuteValue != 0L || secondValue != 0L)) {
            notice = "The maximum interval is 24 hours."
            return
        }
        if (!vibration && !sound) {
            notice = "Enable vibration or sound."
            return
        }
        saveDefaults()
        val intent = Intent(activity, TickModeService::class.java).apply {
            action = TickModeService.ACTION_START
            putExtra(TickModeService.EXTRA_INTERVAL, interval)
            putExtra(TickModeService.EXTRA_VIBRATION, vibration)
            putExtra(TickModeService.EXTRA_PATTERN, pattern)
            putExtra(TickModeService.EXTRA_SOUND, sound)
            putExtra(TickModeService.EXTRA_TONE, tone)
            putExtra(TickModeService.EXTRA_CUSTOM_TONE_URI, customToneUri)
        }
        try {
            TickModeService.start(activity, intent)
            notice = ""
        } catch (e: Exception) {
            notice = "Could not start session: ${e.message ?: "system restriction"}"
        }
    }

    fun serviceAction(action: String) {
        try {
            TickModeService.start(activity, Intent(activity, TickModeService::class.java).setAction(action))
        } catch (e: Exception) {
            notice = "Action failed: ${e.message ?: "system restriction"}"
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = White, onPrimary = Ink, background = Ink, surface = Panel,
        onSurface = White, secondary = Grey, outline = Line
    )) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            if (onboarding) {
                WelcomeScreen {
                    SessionStore.setBoolean(activity, "onboarding_complete", true)
                    onboarding = false
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 15.dp)) {
                    AppHeader(
                        onSettings = { destination = Destination.SETTINGS },
                        onBack = { destination = if (destination == Destination.DETAIL) Destination.HISTORY else Destination.TIMER },
                        showBack = destination == Destination.SETTINGS
                    )
                    Spacer(Modifier.height(16.dp))

                    val showTabs = destination == Destination.TIMER && session == null || destination == Destination.PRESETS || destination == Destination.HISTORY
                    if (showTabs) {
                        TopTabs(destination) { destination = it }
                        Spacer(Modifier.height(18.dp))
                    } else if (destination == Destination.TIMER && session != null) {
                        Spacer(Modifier.height(2.dp))
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (destination) {
                            Destination.TIMER -> {
                                if (session == null) {
                                    TimerSetup(
                                        hours, { hours = it }, minutes, { minutes = it }, seconds, { seconds = it },
                                        vibration, sound, pattern, tone, customToneUri.isNotBlank(),
                                        onSignalEdit = { showSignalDialog = true },
                                        onPreview = { previewSignal(activity, vibration, pattern, sound, tone, customToneUri) },
                                        onStart = { startSession() }, notice = notice
                                    )
                                } else {
                                    ActiveSession(
                                        session = session!!, now = now,
                                        onPause = { serviceAction(TickModeService.ACTION_PAUSE) },
                                        onResume = { serviceAction(TickModeService.ACTION_RESUME) },
                                        onStop = { serviceAction(TickModeService.ACTION_STOP); notice = "" },
                                        onEditSignal = { showSignalDialog = true }
                                    )
                                }
                            }
                            Destination.PRESETS -> PresetsScreen { chosen -> applyInterval(chosen); destination = Destination.TIMER }
                            Destination.HISTORY -> HistoryScreen(history) { selectedDetail = it; destination = Destination.DETAIL }
                            Destination.SETTINGS -> SettingsScreen(
                                activity = activity,
                                vibration = vibration,
                                sound = sound,
                                pattern = pattern,
                                tone = tone,
                                customToneSelected = customToneUri.isNotBlank(),
                                notice = notice,
                                onSignalEdit = { showSignalDialog = true },
                                onNotificationSettings = {
                                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else openNotificationSettings(activity)
                                },
                                onBatterySettings = { openBatteryOptimizationSettings(activity) },
                                onTestVibration = { HapticEngine.play(activity, pattern) },
                                onChooseAudio = { customAudioPicker.launch(arrayOf("audio/*")) },
                                onRefresh = { notice = readinessSummary(activity) }
                            )
                            Destination.DETAIL -> SessionDetail(selectedDetail ?: history.firstOrNull()) { destination = Destination.HISTORY }
                        }
                    }
                }
            }
        }
    }

    if (showSignalDialog) {
        val active = session
        val initialVibration = active?.vibrationEnabled ?: vibration
        val initialPattern = active?.pattern ?: pattern
        val initialSound = active?.soundEnabled ?: sound
        val initialTone = active?.tone ?: tone
        val initialUri = customToneUri
        SignalDialog(
            vibration = initialVibration,
            pattern = initialPattern,
            sound = initialSound,
            tone = initialTone,
            customToneUri = initialUri,
            onChooseAudio = { customAudioPicker.launch(arrayOf("audio/*")) },
            onPreview = { vib, pat, snd, selectedTone, audioUri -> previewSignal(activity, vib, pat, snd, selectedTone, audioUri) },
            onDismiss = { showSignalDialog = false },
            onSave = { vib, pat, snd, selectedTone, audioUri ->
                vibration = vib
                pattern = pat
                sound = snd
                tone = selectedTone
                customToneUri = audioUri
                saveDefaults()
                if (active != null) updateActiveSignal(vib, pat, snd, selectedTone, audioUri)
                showSignalDialog = false
            }
        )
    }
}

@Composable
private fun WelcomeScreen(onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        ArchitectureBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 30.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandLockup(centered = true, markSize = 48)
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 48.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(horizontalAlignment = Alignment.Start) {
                    Box(Modifier.width(44.dp).height(1.dp).background(White))
                    Spacer(Modifier.height(20.dp))
                    Text("PRECISION\nINTERVAL\nSIGNALING", color = White, fontSize = 27.sp, lineHeight = 36.sp,
                        letterSpacing = 5.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.height(18.dp))
                    Text("A minimal interval timer.", color = Grey, fontSize = 13.sp, letterSpacing = .3.sp)
                }
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Ink), shape = RectangleShape) {
                    Text("GET STARTED", fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    Text("→", fontSize = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun ArchitectureBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val structure = Path().apply {
            moveTo(w * .78f, h * .12f)
            lineTo(w, h * .02f)
            lineTo(w, h * .76f)
            lineTo(w * .82f, h * .86f)
            close()
        }
        drawPath(structure, Color(0xFF151818))
        val inner = Path().apply {
            moveTo(w * .84f, h * .21f)
            lineTo(w, h * .11f)
            lineTo(w, h * .68f)
            lineTo(w * .89f, h * .76f)
            close()
        }
        drawPath(inner, Color(0xFF202323))
        drawLine(Color(0xFF343838), Offset(w * .79f, h * .11f), Offset(w * .79f, h * .78f), 2f)
        drawLine(Color(0xFF272B2B), Offset(w * .69f, h * .36f), Offset(w, h * .13f), 2f)
        drawArc(Color(0xFF3A3E3E), 205f, 125f, false,
            topLeft = Offset(w * .58f, h * .18f), size = Size(w * .62f, h * .68f), style = Stroke(width = 3f))
        drawLine(Color(0xFF252929), Offset(0f, h * .76f), Offset(w * .78f, h * .76f), 1f)
    }
}

@Composable
private fun AppHeader(onSettings: () -> Unit, onBack: () -> Unit, showBack: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(76.dp)) {
        if (showBack) {
            Text("‹", color = Grey, fontSize = 32.sp, modifier = Modifier.align(Alignment.CenterStart)
                .clickable { onBack() }.padding(horizontal = 5.dp))
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            TickMark(29.dp, 38.dp)
            Spacer(Modifier.height(2.dp))
            Text("T I C K", color = White, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Light)
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            Text("⋮", color = Grey, fontSize = 25.sp, modifier = Modifier.clickable { menuOpen = true }.padding(horizontal = 7.dp, vertical = 8.dp))
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Settings", color = White, fontSize = 13.sp) }, onClick = { menuOpen = false; onSettings() })
            }
        }
    }
}

@Composable
private fun BrandLockup(centered: Boolean, markSize: Int) {
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        TickMark(markSize.dp, (markSize * 1.28f).dp)
        Spacer(Modifier.height(5.dp))
        Text("T I C K", color = White, fontSize = 15.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun TickMark(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(width, height)) {
        val s = 2.1f
        val c = White
        val mid = size.width / 2f
        val bottom = size.height * .92f
        drawLine(c, Offset(size.width * .13f, size.height * .08f), Offset(mid, bottom), s, cap = StrokeCap.Square)
        drawLine(c, Offset(size.width * .87f, size.height * .08f), Offset(mid, bottom), s, cap = StrokeCap.Square)
        drawLine(c, Offset(mid, size.height * .02f), Offset(mid, bottom), s, cap = StrokeCap.Square)
        drawLine(c, Offset(size.width * .34f, size.height * .12f), Offset(mid, size.height * .36f), s * .8f)
        drawLine(c, Offset(size.width * .66f, size.height * .12f), Offset(mid, size.height * .36f), s * .8f)
    }
}

@Composable
private fun TopTabs(current: Destination, onSelect: (Destination) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(Destination.TIMER to "SESSION", Destination.PRESETS to "PRESETS", Destination.HISTORY to "HISTORY").forEach { (dest, label) ->
                Column(Modifier.weight(1f).clickable { onSelect(dest) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = if (current == dest) White else Grey, fontSize = 9.sp, letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(9.dp))
                    Box(Modifier.width(if (current == dest) 54.dp else 0.dp).height(2.dp).background(White))
                }
            }
        }
        HorizontalRule()
    }
}

@Composable
private fun TimerSetup(
    hours: String, onHours: (String) -> Unit,
    minutes: String, onMinutes: (String) -> Unit,
    seconds: String, onSeconds: (String) -> Unit,
    vibration: Boolean, sound: Boolean, pattern: String, tone: String, customToneSelected: Boolean,
    onSignalEdit: () -> Unit, onPreview: () -> Unit, onStart: () -> Unit, notice: String
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(13.dp))
        Text("INTERVAL", color = Grey, fontSize = 9.sp, letterSpacing = 2.2.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().border(1.dp, Line).background(Panel).padding(horizontal = 9.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            TimePart(hours, onHours, "HOURS", Modifier.weight(1f))
            Text(":", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 30.sp, modifier = Modifier.padding(bottom = 16.dp))
            TimePart(minutes, onMinutes, "MINUTES", Modifier.weight(1f))
            Text(":", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 30.sp, modifier = Modifier.padding(bottom = 16.dp))
            TimePart(seconds, onSeconds, "SECONDS", Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("1 SEC" to 1_000L, "5 SEC" to 5_000L, "10 SEC" to 10_000L, "30 SEC" to 30_000L,
                "1 MIN" to 60_000L, "5 MIN" to 300_000L, "15 MIN" to 900_000L).forEach { (label, ms) ->
                Box(Modifier.border(1.dp, Line).clickable { setFieldsFromPreset(ms, onHours, onMinutes, onSeconds) }
                    .padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(label, color = Grey, fontSize = 8.sp, letterSpacing = .8.sp)
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        HorizontalRule()
        Row(Modifier.fillMaxWidth().clickable { onSignalEdit() }.padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⌁", color = White, fontSize = 25.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.Center)
            Column(Modifier.weight(1f)) {
                Text("Signal", color = White, fontSize = 13.sp)
                Text(buildSignalSummary(vibration, sound, pattern, tone, customToneSelected), color = Grey, fontSize = 10.sp)
            }
            Text("›", color = Grey, fontSize = 25.sp)
        }
        HorizontalRule()
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("PREVIEW", color = Dim, fontSize = 8.sp, letterSpacing = 1.4.sp)
            TextButton(onClick = onPreview, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("TEST SIGNAL  ↗", color = White, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(17.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Ink), shape = RectangleShape) {
            Text("▶", fontSize = 13.sp)
            Spacer(Modifier.width(13.dp))
            Text("START SESSION", fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
        }
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(notice, color = Grey, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TimePart(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicTextField(value = value, onValueChange = { onValue(it.filter(Char::isDigit).take(2)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            textStyle = TextStyle(color = White, fontSize = 29.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            decorationBox = { inner -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { inner() } })
        Spacer(Modifier.height(7.dp))
        Text(label, color = Grey, fontSize = 7.sp, letterSpacing = .7.sp, textAlign = TextAlign.Center)
    }
}

private fun setFieldsFromPreset(ms: Long, onHours: (String) -> Unit, onMinutes: (String) -> Unit, onSeconds: (String) -> Unit) {
    val safe = ms.coerceIn(1_000L, 86_400_000L)
    onHours((safe / 3_600_000L).toString().padStart(2, '0'))
    onMinutes(((safe % 3_600_000L) / 60_000L).toString().padStart(2, '0'))
    onSeconds(((safe % 60_000L) / 1_000L).toString().padStart(2, '0'))
}

@Composable
private fun ActiveSession(session: TickSession, now: Long, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit, onEditSignal: () -> Unit) {
    val running = session.status == "RUNNING"
    val remaining = if (running) (session.nextSignalAt - now).coerceAtLeast(0L) else session.remainingMs
    val elapsed = session.elapsed(now)
    val progress = if (session.intervalMs > 0L) (1f - remaining.toFloat() / session.intervalMs.toFloat()).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("SESSION ${session.number.toString().padStart(4, '0')}", color = White, fontSize = 10.sp, letterSpacing = 1.5.sp, fontFamily = FontFamily.Monospace)
            Row(Modifier.border(1.dp, Line).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (running) White else Grey, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(if (running) "RUNNING" else "PAUSED", color = White, fontSize = 8.sp, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CountdownDial(progress, remaining)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            SessionMetric("INTERVAL", compactInterval(session.intervalMs), Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(32.dp).background(Line))
            SessionMetric("SIGNALS", session.signalCount.toString().padStart(3, '0'), Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(32.dp).background(Line))
            SessionMetric("ELAPSED", formatDuration(elapsed), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPause.takeIf { running } ?: onResume,
                modifier = Modifier.weight(1f).height(50.dp), border = BorderStroke(1.dp, Line),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = White), shape = RectangleShape) {
                Text(if (running) "Ⅱ  PAUSE" else "▶  RESUME", fontSize = 9.sp, letterSpacing = 1.2.sp)
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).height(50.dp), border = BorderStroke(1.dp, Line),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = White), shape = RectangleShape) {
                Text("■  STOP", fontSize = 9.sp, letterSpacing = 1.2.sp)
            }
        }
        TextButton(onClick = onEditSignal, modifier = Modifier.padding(top = 5.dp)) {
            Text("SIGNAL SETTINGS", color = Grey, fontSize = 8.sp, letterSpacing = 1.3.sp)
        }
    }
}

@Composable
private fun CountdownDial(progress: Float, remaining: Long) {
    Box(Modifier.size(286.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension * .46f
            for (i in 0 until 72) {
                val angle = Math.toRadians((i * 5.0) - 90.0)
                val outer = radius
                val inner = radius - if (i % 6 == 0) 14f else 9f
                val x1 = cx + cos(angle).toFloat() * inner
                val y1 = cy + sin(angle).toFloat() * inner
                val x2 = cx + cos(angle).toFloat() * outer
                val y2 = cy + sin(angle).toFloat() * outer
                val isActive = i / 72f <= progress
                drawLine(if (isActive) White else Color(0xFF383C3C), Offset(x1, y1), Offset(x2, y2),
                    strokeWidth = if (i % 6 == 0) 3f else 2f, cap = StrokeCap.Square)
            }
            drawArc(color = Color(0xFF262A2A), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(cx - radius + 17f, cy - radius + 17f), size = Size((radius - 17f) * 2f, (radius - 17f) * 2f), style = Stroke(width = 1.2f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NEXT SIGNAL IN", color = Grey, fontSize = 9.sp, letterSpacing = 1.8.sp)
            Spacer(Modifier.height(9.dp))
            Text(formatClock(remaining), color = White, fontSize = 38.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light, textAlign = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.height(11.dp))
            Text("⌁", color = White, fontSize = 23.sp)
        }
    }
}

@Composable
private fun SessionMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Grey, fontSize = 7.sp, letterSpacing = .9.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, color = White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun PresetsScreen(onSelect: (Long) -> Unit) {
    val presets = listOf(
        "1 second" to 1_000L, "5 seconds" to 5_000L,
        "10 seconds" to 10_000L, "30 seconds" to 30_000L,
        "1 minute" to 60_000L, "5 minutes" to 300_000L,
        "15 minutes" to 900_000L, "1 hour" to 3_600_000L
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("PRESETS", color = White, fontSize = 17.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
        Spacer(Modifier.height(5.dp))
        Text("SELECT AN INTERVAL", color = Dim, fontSize = 8.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(17.dp))
        presets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { (label, ms) ->
                    Column(Modifier.weight(1f).padding(bottom = 9.dp).border(1.dp, Line).clickable { onSelect(ms) }.padding(15.dp)) {
                        Text(label.uppercase(), color = White, fontSize = 10.sp, letterSpacing = .7.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(compactInterval(ms), color = Grey, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HistoryScreen(history: List<TickSession>, onSelect: (TickSession) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("HISTORY", color = White, fontSize = 17.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
            Text("${history.size.toString().padStart(3, '0')} SESSIONS", color = Dim, fontSize = 8.sp, letterSpacing = 1.sp)
        }
        HorizontalRule()
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No sessions recorded.", color = Dim, fontSize = 11.sp)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)) {
                items(history.size) { index ->
                    val item = history[index]
                    Column(Modifier.fillMaxWidth().border(1.dp, Line).clickable { onSelect(item) }.padding(horizontal = 13.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("#${item.number.toString().padStart(4, '0')}", color = White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(item.status, color = Grey, fontSize = 8.sp, letterSpacing = .7.sp,
                                modifier = Modifier.border(1.dp, Line).padding(horizontal = 7.dp, vertical = 4.dp))
                        }
                        Spacer(Modifier.height(7.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDate(item.startedAt), color = Grey, fontSize = 9.sp)
                            Text("${item.signalCount.toString().padStart(3, '0')}  ›", color = White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetail(session: TickSession?, onBack: () -> Unit) {
    if (session == null) return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("‹  HISTORY", color = Grey, fontSize = 9.sp, letterSpacing = 1.3.sp,
            modifier = Modifier.clickable { onBack() }.padding(vertical = 10.dp))
        Spacer(Modifier.height(14.dp))
        Text("#${session.number.toString().padStart(4, '0')}", color = White, fontSize = 23.sp, fontFamily = FontFamily.Monospace)
        Text(session.status, color = Grey, fontSize = 8.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(20.dp))
        DetailRow("STARTED", formatDate(session.startedAt))
        DetailRow("ENDED", if (session.endedAt > 0L) formatDate(session.endedAt) else "—")
        DetailRow("INTERVAL", formatInterval(session.intervalMs))
        DetailRow("ACTIVE TIME", formatDuration(session.elapsed(session.endedAt)))
        DetailRow("SIGNALS", session.signalCount.toString())
        DetailRow("VIBRATION", if (session.vibrationEnabled) session.pattern else "OFF")
        DetailRow("SOUND", if (session.soundEnabled) if (session.customToneUri.isNotBlank()) "CUSTOM FILE" else session.tone else "OFF")
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Grey, fontSize = 8.sp, letterSpacing = 1.sp)
            Text(value, color = White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
        }
        HorizontalRule()
    }
}

@Composable
private fun SignalDialog(
    vibration: Boolean, pattern: String, sound: Boolean, tone: String, customToneUri: String,
    onChooseAudio: () -> Unit,
    onPreview: (Boolean, String, Boolean, String, String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Boolean, String, Boolean, String, String) -> Unit
) {
    var vib by remember { mutableStateOf(vibration) }
    var pat by remember { mutableStateOf(pattern) }
    var snd by remember { mutableStateOf(sound) }
    var selectedTone by remember { mutableStateOf(tone) }
    var audioUri by remember(customToneUri) { mutableStateOf(customToneUri) }

    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel,
        title = { Text("SIGNAL", color = White, fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Normal) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow("Vibration", vib) { vib = it }
                Text("PATTERN", color = Grey, fontSize = 8.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                listOf("Short", "Medium", "Long").chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        row.forEach { option ->
                            ChoiceChip(option.uppercase(), pat == option, Modifier.weight(1f)) { pat = option }
                        }
                    }
                }
                Text("130 MS  ·  300 MS  ·  650 MS", color = Dim, fontSize = 8.sp, letterSpacing = .6.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                HorizontalRule()
                SwitchRow("Sound", snd) { snd = it }
                Text("TONE", color = Grey, fontSize = 8.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                listOf("Soft Bell", "Beep", "Ack", "Prompt").chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        row.forEach { option ->
                            ChoiceChip(option.uppercase(), selectedTone == option && audioUri.isBlank(), Modifier.weight(1f)) {
                                selectedTone = option
                                audioUri = ""
                            }
                        }
                    }
                }
                if (selectedTone == "Soft Bell" && audioUri.isBlank()) {
                    Text("Uses the phone's notification sound at reduced volume.", color = Grey, fontSize = 9.sp, lineHeight = 13.sp)
                }
                Row(Modifier.fillMaxWidth().clickable { onChooseAudio() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (audioUri.isNotBlank()) "Custom audio selected" else "Choose audio file", color = White, fontSize = 11.sp)
                        Text(if (audioUri.isNotBlank()) "Stored for future sessions" else "Use an audio file from this device", color = Grey, fontSize = 9.sp)
                    }
                    Text("›", color = Grey, fontSize = 22.sp)
                }
                HorizontalRule()
                TextButton(onClick = { onPreview(vib, pat, snd, selectedTone, audioUri) }, modifier = Modifier.align(Alignment.End)) {
                    Text("TEST SIGNAL", color = White, fontSize = 9.sp, letterSpacing = 1.1.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(vib, pat, snd, selectedTone, audioUri) }) { Text("SAVE", color = White, fontSize = 10.sp, letterSpacing = 1.sp) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Grey, fontSize = 10.sp) } })
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = White, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = White,
                uncheckedThumbColor = Grey, uncheckedTrackColor = Raised))
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.border(1.dp, if (selected) White else Line).background(if (selected) Raised else Panel)
        .clickable { onClick() }.padding(horizontal = 5.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) White else Grey, fontSize = 8.sp, letterSpacing = .4.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SettingsScreen(
    activity: MainActivity, vibration: Boolean, sound: Boolean, pattern: String, tone: String,
    customToneSelected: Boolean, notice: String,
    onSignalEdit: () -> Unit, onNotificationSettings: () -> Unit, onBatterySettings: () -> Unit,
    onTestVibration: () -> Unit, onChooseAudio: () -> Unit, onRefresh: () -> Unit
) {
    val notificationsGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryUnrestricted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || power.isIgnoringBatteryOptimizations(activity.packageName)
    val haptics = HapticEngine.capabilitySummary(activity)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("SETTINGS", color = White, fontSize = 17.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
        Spacer(Modifier.height(15.dp))
        SettingsRow("♧", "Notifications", if (notificationsGranted) "Granted" else "Not granted", onNotificationSettings)
        SettingsRow("◷", "Battery optimization", if (batteryUnrestricted) "Unrestricted" else "Optimized", onBatterySettings)
        SettingsRow("⌁", "Background operation", "Foreground service", onRefresh)
        Spacer(Modifier.height(17.dp))
        HorizontalRule()
        Text("SIGNAL", color = Grey, fontSize = 8.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 16.dp, bottom = 5.dp))
        SettingsRow("⌁", "Default signal", buildSignalSummary(vibration, sound, pattern, tone, customToneSelected), onSignalEdit)
        SettingsRow("◉", "Vibration test", haptics, onTestVibration)
        SettingsRow("♫", "Custom audio", if (customToneSelected) "Selected" else "Choose file", onChooseAudio)
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(notice, color = Grey, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text("TICKMODE  ·  0.6.0", color = Dim, fontSize = 8.sp, letterSpacing = 1.3.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsRow(icon: String, title: String, value: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = White, fontSize = 18.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
            Text(title, color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(value, color = Grey, fontSize = 9.sp, textAlign = TextAlign.End, maxLines = 1)
            Text("  ›", color = Grey, fontSize = 17.sp)
        }
        HorizontalRule()
    }
}

@Composable
private fun HorizontalRule() { Box(Modifier.fillMaxWidth().height(1.dp).background(Line)) }

private fun buildSignalSummary(vibration: Boolean, sound: Boolean, pattern: String, tone: String, customTone: Boolean): String {
    val parts = mutableListOf<String>()
    if (vibration) parts += pattern
    if (sound) parts += if (customTone) "Custom audio" else tone
    return when (parts.size) { 0 -> "Off"; 1 -> parts[0]; else -> "Vibration + Sound" }
}

private fun serviceActionWithIntent(context: Context, intent: Intent) {
    try { TickModeService.start(context, intent) } catch (_: Exception) { }
}

private fun previewSignal(context: Context, vibration: Boolean, pattern: String, sound: Boolean, tone: String, customToneUri: String) {
    if (vibration) HapticEngine.play(context, pattern)
    if (sound) {
        if (customToneUri.isNotBlank()) playCustomAudioPreview(context, customToneUri) else playTonePreview(context, tone)
    }
}

private fun playTonePreview(context: Context, tone: String) {
    SignalToneEngine.stopSoftBell()
    if (tone == "Soft Bell" && SignalToneEngine.playSoftBell(context)) return
    try {
        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        val type = when (tone) {
            "Ack" -> ToneGenerator.TONE_PROP_ACK
            "Prompt" -> ToneGenerator.TONE_PROP_PROMPT
            else -> ToneGenerator.TONE_PROP_BEEP
        }
        generator.startTone(type, 220)
        Handler(Looper.getMainLooper()).postDelayed({ try { generator.release() } catch (_: Exception) { } }, 400)
    } catch (_: Exception) { }
}

private fun playCustomAudioPreview(context: Context, uri: String) {
    SignalToneEngine.stopSoftBell()
    try {
        val player = MediaPlayer()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        player.setDataSource(context, Uri.parse(uri))
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ -> mp.release(); true }
        player.setOnPreparedListener { it.start() }
        player.prepareAsync()
    } catch (_: Exception) { }
}

private fun openBatteryOptimizationSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
}

private fun openNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        context.startActivity(intent)
    } catch (_: Exception) { }
}

private fun readinessSummary(context: Context): String {
    val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val battery = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || power.isIgnoringBatteryOptimizations(context.packageName)
    return "Notifications: ${if (notifications) "granted" else "not granted"}. Battery optimization: ${if (battery) "unrestricted" else "enabled"}."
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun compactInterval(ms: Long): String = when {
    ms % 3_600_000L == 0L -> "${ms / 3_600_000L}h"
    ms % 60_000L == 0L -> "${ms / 60_000L}m"
    else -> "${ms / 1_000L}s"
}

private fun formatInterval(ms: Long): String = when {
    ms % 3_600_000L == 0L -> "${ms / 3_600_000L} hour${if (ms / 3_600_000L == 1L) "" else "s"}"
    ms % 60_000L == 0L -> "${ms / 60_000L} min"
    else -> "${ms / 1_000L} sec"
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}

private fun formatDate(time: Long): String = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(time))
