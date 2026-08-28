package com.rork.mindsetframestracker.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.R
import com.rork.mindsetframestracker.data.AppLanguage
import com.rork.mindsetframestracker.data.ContentPack
import com.rork.mindsetframestracker.data.MoodMode
import com.rork.mindsetframestracker.ui.appStrings
import com.rork.mindsetframestracker.ui.components.MoodPicker
import com.rork.mindsetframestracker.ui.theme.moodThemeFor
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 5

/**
 * First-launch onboarding: a brief swipeable carousel. Three explainer pages
 * show how the app works (daily mood check-in, small habits and streaks,
 * weekly patterns), followed by two setup pages (starter habits, first mood).
 * Swiping and the primary button both advance; Skip is always available.
 *
 * Onboarding carries NO account UI by design — sign-up, sign-in, and cloud
 * restore all happen in the automatic save-your-progress popup that appears
 * on the Today screen right after onboarding (see AuthPromptSheet).
 */
@Composable
fun OnboardingScreen(
    onFinish: (habits: List<String>, mood: MoodMode?) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val selectedStarters = remember { mutableStateOf(setOf<String>()) }
    val customHabits = remember { mutableListOf<String>().toMutableStateList() }
    var selectedMood by remember { mutableStateOf<MoodMode?>(null) }

    fun chosenHabits(): List<String> = selectedStarters.value.toList() + customHabits

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PagerDots(current = pagerState.currentPage)
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = { onFinish(chosenHabits(), selectedMood) }) {
                Text(appStrings().onbSkip)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                when (page) {
                    0 -> WelcomePage()
                    1 -> MoodExplainerPage()
                    2 -> HabitsExplainerPage()
                    3 -> HabitStep(
                        selected = selectedStarters.value,
                        onToggle = { name ->
                            selectedStarters.value =
                                if (name in selectedStarters.value) selectedStarters.value - name
                                else selectedStarters.value + name
                        },
                        customHabits = customHabits,
                        onAddCustom = { name ->
                            if (name.isNotBlank() && customHabits.none { it.equals(name.trim(), ignoreCase = true) }) {
                                customHabits.add(name.trim())
                            }
                        },
                    )
                    else -> MoodStep(
                        selected = selectedMood,
                        onSelect = { selectedMood = it },
                    )
                }
            }
        }

        Button(
            onClick = {
                if (pagerState.currentPage == PAGE_COUNT - 1) {
                    onFinish(chosenHabits(), selectedMood)
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .height(56.dp),
        ) {
            Text(
                text = when (pagerState.currentPage) {
                    0 -> appStrings().onbSeeHowItWorks
                    1, 2 -> appStrings().onbNext
                    3 -> appStrings().onbContinue
                    else -> appStrings().onbStartMyDay
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun PagerDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(PAGE_COUNT) { index ->
            val isActive = index == current
            val dotWidth by animateDpAsState(
                targetValue = if (isActive) 22.dp else 8.dp,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "pagerDotWidth$index",
            )
            val dotColor by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                animationSpec = tween(durationMillis = 300),
                label = "pagerDotColor$index",
            )
            Box(
                modifier = Modifier
                    .size(width = dotWidth, height = 8.dp)
                    .background(color = dotColor, shape = RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun ExplainerText(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    )
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.brand_logo),
            contentDescription = "Mindset Frames logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(110.dp)
                .clip(RoundedCornerShape(26.dp)),
        )
        val s = appStrings()
        ExplainerText(
            title = s.onbWelcomeTitle,
            body = s.onbWelcomeBody,
        )
        Text(
            text = s.onbFoundingMember,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 16.dp),
        )
        Text(
            text = s.onbSwipeToSee,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

private data class MoodPreview(val mode: MoodMode, val labelKey: String, val icon: ImageVector)

private val moodPreviews: List<MoodPreview> = listOf(
    MoodPreview(MoodMode.CALM, "moodCalm", Icons.Outlined.Spa),
    MoodPreview(MoodMode.FOCUSED, "moodFocused", Icons.Outlined.TrackChanges),
    MoodPreview(MoodMode.MOTIVATED, "moodMotivated", Icons.Outlined.Bolt),
    MoodPreview(MoodMode.OVERWHELMED, "moodOverwhelmed", Icons.Outlined.Cloud),
)

@Composable
private fun MoodExplainerPage() {
    val s = appStrings()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            moodPreviews.chunked(2).forEach { rowPreviews ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowPreviews.forEach { preview ->
                        val accent = moodThemeFor(preview.mode, isDark, "classic", true).accent
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = accent.copy(alpha = if (isDark) 0.2f else 0.12f),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 52.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = preview.icon,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = when (preview.labelKey) {
                                        "moodCalm" -> s.moodCalm
                                        "moodFocused" -> s.moodFocused
                                        "moodMotivated" -> s.moodMotivated
                                        else -> s.moodOverwhelmed
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
        ExplainerText(
            title = s.onbMoodTitle,
            body = s.onbMoodBody,
        )
    }
}

@Composable
private fun MockHabitRow(name: String, isChecked: Boolean, streak: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isChecked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isChecked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.LocalFireDepartment,
            contentDescription = null,
            tint = if (isChecked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "$streak",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun HabitsExplainerPage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MockHabitRow(name = "Drink water", isChecked = true, streak = 6)
                MockHabitRow(name = "5 min journaling", isChecked = false, streak = 3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(7) { index ->
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        color = if (index < 5) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                    Text(
                        text = appStrings().onbThisWeek,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        ExplainerText(
            title = appStrings().onbHabitsTitle,
            body = appStrings().onbHabitsBody,
        )
    }
}

@Composable
private fun HabitStep(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    customHabits: List<String>,
    onAddCustom: (String) -> Unit,
) {
    var customText by remember { mutableStateOf("") }
    val s = appStrings()
    val starterList = ContentPack.starterHabitsFor(AppLanguage.ENGLISH) // Onboarding always uses English for starter habit names

    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = s.onbPickStarter,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = s.onbChooseFew,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            starterList.forEach { name ->
                val isSelected = name in selected
                Surface(
                    onClick = { onToggle(name) },
                    shape = MaterialTheme.shapes.large,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.CheckCircle
                            else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = if (isSelected) s.onbSelected else s.onbNotSelected,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            customHabits.forEach { name ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = s.onbAdded,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                placeholder = { Text(s.onbAddYourOwn) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    onAddCustom(customText)
                    customText = ""
                },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = s.onbAddCustomHabit,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MoodStep(
    selected: MoodMode?,
    onSelect: (MoodMode) -> Unit,
) {
    val s = appStrings()
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = s.onbHowArriving,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = s.onbAnswerShapes,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        MoodPicker(selected = selected, onSelect = onSelect)
    }
}
