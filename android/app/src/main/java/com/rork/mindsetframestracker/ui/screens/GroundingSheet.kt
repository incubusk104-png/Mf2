package com.rork.mindsetframestracker.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.Dates
import com.rork.mindsetframestracker.ui.AppStrings
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The grounding toolkit — a quiet full-screen space with three tools:
 *
 * 1. **Box breathing** — the classic 4-4-4-4 cadence with a softly scaling
 *    square, per-second countdown, and a haptic pulse at each phase change.
 * 2. **5-4-3-2-1 senses** — step-by-step sensory grounding cards.
 * 3. **One line** — a one-sentence micro-journal saved to local data
 *    (`AppData.reflections`), with the last week of lines shown below.
 *
 * The breathing square animates even in moods whose decorative motion is
 * off (the motion IS the guidance), but honors the explicit reduced-motion
 * accessibility setting by falling back to text-only pacing.
 */
@Composable
fun GroundingSheet(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    val data by viewModel.state.collectAsStateWithLifecycle()
    val s = appStrings()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = s.adPassMaybeLater,
                        )
                    }
                    Text(
                        text = s.groundingTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    listOf(s.groundingTabBreathe, s.groundingTabSenses, s.groundingTabNote)
                        .forEachIndexed { index, label ->
                            FilterChip(
                                selected = tab == index,
                                onClick = { tab = index },
                                label = { Text(label) },
                            )
                        }
                }

                when (tab) {
                    0 -> BreatheSection(reducedMotion = data.settings.reducedMotion, s = s)
                    1 -> SensesSection(s = s)
                    else -> NoteSection(
                        data = data,
                        s = s,
                        onSave = viewModel::saveReflection,
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ── Box breathing ──────────────────────────────────────────────────────

private const val PHASE_SECONDS = 4

@Composable
private fun BreatheSection(reducedMotion: Boolean, s: AppStrings) {
    val moodTheme = LocalMoodTheme.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(PHASE_SECONDS) }
    var cycles by remember { mutableIntStateOf(0) }
    val scale = remember { Animatable(0.62f) }

    // Phase engine: each pass animates the square (inhale grows, exhale
    // shrinks, holds stay) while the one-second countdown ticks alongside.
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (isActive) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val target = when (phase) {
                0 -> 1f
                2 -> 0.62f
                else -> null
            }
            coroutineScope {
                if (target != null) {
                    launch {
                        if (reducedMotion) {
                            scale.snapTo(target)
                        } else {
                            scale.animateTo(
                                targetValue = target,
                                animationSpec = tween(
                                    durationMillis = PHASE_SECONDS * 1000,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        }
                    }
                }
                repeat(PHASE_SECONDS) { tick ->
                    secondsLeft = PHASE_SECONDS - tick
                    delay(1_000)
                }
            }
            if (phase == 3) cycles++
            phase = (phase + 1) % 4
        }
    }

    val phaseLabels = listOf(s.breatheInhale, s.breatheHold, s.breatheExhale, s.breatheHold)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = s.breatheHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(180.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .background(
                        brush = Brush.linearGradient(
                            listOf(moodTheme.gradient.first(), moodTheme.gradient.last()),
                        ),
                        shape = RoundedCornerShape(40.dp),
                    ),
            ) {
                Text(
                    text = secondsLeft.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    color = Color(0xFFFFFCF5),
                )
            }
        }
        Crossfade(targetState = phaseLabels[phase], label = "breathePhase") { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "$cycles ${s.breatheCycles}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Button(onClick = { running = !running }) {
                Text(if (running) s.breathePause else s.breatheStart)
            }
            TextButton(
                onClick = {
                    running = false
                    phase = 0
                    secondsLeft = PHASE_SECONDS
                    cycles = 0
                    scope.launch { scale.snapTo(0.62f) }
                },
            ) { Text(s.breatheReset) }
        }
    }
}

// ── 5-4-3-2-1 sensory grounding ────────────────────────────────────────

@Composable
private fun SensesSection(s: AppStrings) {
    val haptics = LocalHapticFeedback.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = listOf(
        5 to s.senses5,
        4 to s.senses4,
        3 to s.senses3,
        2 to s.senses2,
        1 to s.senses1,
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = s.sensesIntro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                if (step < steps.size) {
                    val (count, prompt) = steps[step]
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 18.dp),
                    ) {
                        repeat(steps.size) { i ->
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (i <= step) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(4.dp),
                                    ),
                            )
                        }
                    }
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            step++
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                    ) { Text(s.sensesNext) }
                } else {
                    Text(text = "🌿", fontSize = 44.sp)
                    Text(
                        text = s.sensesDone,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TextButton(
                        onClick = { step = 0 },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(s.sensesRestart) }
                }
            }
        }
    }
}

// ── One-line micro-journal ─────────────────────────────────────────────

@Composable
private fun NoteSection(
    data: AppData,
    s: AppStrings,
    onSave: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val today = Dates.todayKey()
    val savedLine = data.reflections[today].orEmpty()
    var text by remember(savedLine) { mutableStateOf(savedLine) }
    var showSaved by remember { mutableStateOf(false) }

    LaunchedEffect(showSaved) {
        if (showSaved) {
            delay(2_200)
            showSaved = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = s.noteTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 160) text = it },
            placeholder = { Text(s.notePlaceholder) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Button(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onSave(text)
                showSaved = true
            },
            enabled = text.isNotBlank() && text.trim() != savedLine,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) { Text(s.noteSave) }
        AnimatedVisibility(visible = showSaved) {
            Text(
                text = s.noteSaved,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        val recent = remember(data.reflections) {
            data.reflections.entries.sortedByDescending { it.key }.take(7)
        }
        if (recent.isNotEmpty()) {
            Text(
                text = s.noteRecent,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
            )
            val formatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
            recent.forEach { (key, line) ->
                val label = runCatching { LocalDate.parse(key).format(formatter) }.getOrDefault(key)
                Column(modifier = Modifier.padding(vertical = 5.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
