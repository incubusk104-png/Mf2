package com.rork.mindsetframestracker.ui.avatar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.mindsetframestracker.data.AppData
import com.rork.mindsetframestracker.data.AvatarConfig
import com.rork.mindsetframestracker.data.CompanionTask
import com.rork.mindsetframestracker.data.CompanionTaskType
import com.rork.mindsetframestracker.data.isCompanionItemUnlocked
import com.rork.mindsetframestracker.data.progress
import com.rork.mindsetframestracker.ui.AppStrings
import com.rork.mindsetframestracker.ui.AppViewModel
import com.rork.mindsetframestracker.ui.appStrings
import kotlinx.coroutines.delay

/** Customization categories shown as chips in the Studio. */
private enum class StudioCategory {
    GENDER, EXPRESSION, OUTFIT, PET, FRAME, SKIN, FACE, EYES, MOUTH, HAIR, HAIR_COLOR,
}

/**
 * Companion Studio — full-screen avatar customizer.
 *
 * Gender models, expression presets, real outfit designs, and a horizontal
 * shelf of shoulder pets. Core customization is free; starred exclusives
 * unlock by finishing daily tasks (never sold), and circular background
 * frames stay tied to the permanent streak badges.
 *
 * Selections save instantly through [AppViewModel.setAvatar], so the
 * preview, the Home screen chip, and persistence never drift apart.
 */
@Composable
fun CompanionStudioSheet(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    val data by viewModel.state.collectAsStateWithLifecycle()
    val newUnlocks by viewModel.newCompanionUnlocks.collectAsStateWithLifecycle()
    val s = appStrings()
    val settings = data.settings
    val avatar = settings.avatar
    val haptics = LocalHapticFeedback.current
    var category by remember { mutableStateOf(StudioCategory.GENDER) }
    var lockedHint by remember { mutableStateOf<String?>(null) }

    // Re-evaluate task unlocks whenever the Studio opens, so anything the
    // user earned since the last visit is ready and celebrated here.
    LaunchedEffect(Unit) {
        viewModel.refreshCompanionUnlocks()
    }

    // Surface newly earned exclusives as a celebration banner, then consume.
    LaunchedEffect(newUnlocks) {
        if (newUnlocks.isNotEmpty()) {
            val names = newUnlocks.joinToString(", ") { unlockDisplayName(it, s) }
            lockedHint = String.format(s.studioUnlockedNew, names)
            viewModel.consumeCompanionUnlocks()
        }
    }

    LaunchedEffect(lockedHint) {
        if (lockedHint != null) {
            delay(3200)
            lockedHint = null
        }
    }

    fun select(newConfig: AvatarConfig) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.setAvatar(newConfig)
    }

    fun showTaskHint(task: CompanionTask) {
        val done = task.progress(data).coerceAtMost(task.target)
        lockedHint = String.format(s.studioTaskLocked, taskLabel(task, s)) +
            " · " + String.format(s.studioTaskProgress, done, task.target)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = s.settingsClose,
                        )
                    }
                    Text(
                        text = s.studioTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    CompanionAvatar(
                        config = avatar,
                        modifier = Modifier
                            .size(156.dp)
                            .border(3.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }
                Text(
                    text = s.studioSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 8.dp),
                )

                AnimatedVisibility(visible = lockedHint != null) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = if (lockedHint?.startsWith("🔒") == false &&
                                    lockedHint?.contains("✨") == true
                                ) Icons.Filled.Star else Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = lockedHint.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                val categories = StudioCategory.entries
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(categoryLabel(cat, s)) },
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when (category) {
                        StudioCategory.GENDER -> GenderPicker(
                            avatar = avatar,
                            s = s,
                            onSelect = { select(avatar.copy(gender = it)) },
                        )
                        StudioCategory.PET -> PetShelf(
                            avatar = avatar,
                            data = data,
                            s = s,
                            onSelect = { select(avatar.copy(companion = it)) },
                            onLocked = { showTaskHint(it) },
                        )
                        else -> StudioGrid(
                            category = category,
                            avatar = avatar,
                            data = data,
                            s = s,
                            onSelect = { select(it) },
                            onLockedTask = { showTaskHint(it) },
                            onLockedFrame = { frame ->
                                lockedHint = if (frame.foundingOnly) {
                                    s.studioLockedFounding
                                } else {
                                    String.format(
                                        s.studioLockedStreak,
                                        frame.requiredTier?.daysRequired ?: 0,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Gender ─────────────────────────────────────────────────────────────

@Composable
private fun GenderPicker(
    avatar: AvatarConfig,
    s: AppStrings,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(0 to s.studioFemale, 1 to s.studioMale).forEach { (index, label) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OptionCell(
                        selected = avatar.gender == index,
                        onClick = { onSelect(index) },
                        modifier = Modifier.size(124.dp),
                    ) {
                        CompanionAvatar(
                            config = avatar.copy(gender = index, companion = 0, frame = 1),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (avatar.gender == index) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (avatar.gender == index) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = s.studioEarnHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

// ── Pets — expanded horizontal shelf ───────────────────────────────────

@Composable
private fun PetShelf(
    avatar: AvatarConfig,
    data: AppData,
    s: AppStrings,
    onSelect: (Int) -> Unit,
    onLocked: (CompanionTask) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            itemsIndexed(AvatarCatalog.pets) { index, pet ->
                val unlocked = isCompanionItemUnlocked(pet.task, pet.id, data)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(84.dp),
                ) {
                    OptionCell(
                        selected = avatar.companion == index,
                        locked = !unlocked,
                        exclusive = pet.task != null,
                        onClick = {
                            if (unlocked) onSelect(index) else pet.task?.let(onLocked)
                        },
                        modifier = Modifier.size(76.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            if (pet.emoji.isEmpty()) {
                                Icon(
                                    imageVector = Icons.Outlined.Block,
                                    contentDescription = s.studioNone,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                            } else {
                                Text(text = pet.emoji, fontSize = 32.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = s.nameFor(AvatarCatalog.petNameKey(pet.id)).ifEmpty { s.studioNone },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(
            text = s.studioEarnHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

// ── Grid categories ────────────────────────────────────────────────────

@Composable
private fun StudioGrid(
    category: StudioCategory,
    avatar: AvatarConfig,
    data: AppData,
    s: AppStrings,
    onSelect: (AvatarConfig) -> Unit,
    onLockedTask: (CompanionTask) -> Unit,
    onLockedFrame: (AvatarFrame) -> Unit,
) {
    val settings = data.settings
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 72.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        when (category) {
            StudioCategory.EXPRESSION -> itemsIndexed(AvatarCatalog.expressions) { index, expr ->
                val unlocked = isCompanionItemUnlocked(expr.task, expr.id, data)
                OptionCell(
                    selected = avatar.expression == index,
                    locked = !unlocked,
                    exclusive = expr.task != null,
                    onClick = {
                        if (unlocked) {
                            onSelect(avatar.copy(expression = index))
                        } else {
                            expr.task?.let(onLockedTask)
                        }
                    },
                ) {
                    CompanionAvatar(
                        config = avatar.copy(expression = index, companion = 0, frame = 1),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            StudioCategory.OUTFIT -> itemsIndexed(AvatarCatalog.outfits) { index, outfit ->
                val unlocked = isCompanionItemUnlocked(outfit.task, outfit.id, data)
                OptionCell(
                    selected = avatar.outfit == index,
                    locked = !unlocked,
                    exclusive = outfit.task != null,
                    onClick = {
                        if (unlocked) {
                            onSelect(avatar.copy(outfit = index))
                        } else {
                            outfit.task?.let(onLockedTask)
                        }
                    },
                ) {
                    CompanionAvatar(
                        config = avatar.copy(outfit = index, companion = 0, frame = 1),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            StudioCategory.FRAME -> itemsIndexed(AvatarCatalog.frames) { index, frame ->
                val unlocked = frame.isUnlocked(settings.earnedBadges)
                OptionCell(
                    selected = avatar.frame == index,
                    locked = !unlocked,
                    lockLabel = when {
                        frame.foundingOnly -> "★"
                        frame.requiredTier != null -> "${frame.requiredTier.daysRequired}d"
                        else -> null
                    },
                    onClick = {
                        if (unlocked) {
                            onSelect(avatar.copy(frame = index))
                        } else {
                            onLockedFrame(frame)
                        }
                    },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawFrameBackground(frame.style)
                    }
                }
            }
            StudioCategory.SKIN -> itemsIndexed(AvatarCatalog.skinTones) { index, tone ->
                OptionCell(
                    selected = avatar.skinTone == index,
                    onClick = { onSelect(avatar.copy(skinTone = index)) },
                ) { Box(modifier = Modifier.fillMaxSize().background(tone)) }
            }
            StudioCategory.FACE -> items(AvatarCatalog.FACE_COUNT) { index ->
                OptionCell(
                    selected = avatar.faceShape == index,
                    onClick = { onSelect(avatar.copy(faceShape = index)) },
                ) {
                    CompanionAvatar(
                        config = avatar.copy(faceShape = index, companion = 0, frame = 1),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            StudioCategory.EYES -> items(AvatarCatalog.EYES_COUNT) { index ->
                OptionCell(
                    selected = avatar.eyes == index,
                    onClick = { onSelect(avatar.copy(eyes = index, expression = 0)) },
                ) {
                    CompanionAvatar(
                        config = avatar.copy(eyes = index, expression = 0, companion = 0, frame = 1),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            StudioCategory.MOUTH -> items(AvatarCatalog.MOUTH_COUNT) { index ->
                OptionCell(
                    selected = avatar.mouth == index,
                    onClick = { onSelect(avatar.copy(mouth = index, expression = 0)) },
                ) {
                    CompanionAvatar(
                        config = avatar.copy(mouth = index, expression = 0, companion = 0, frame = 1),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            StudioCategory.HAIR -> items(AvatarCatalog.HAIR_COUNT) { index ->
                OptionCell(
                    selected = avatar.hair == index,
                    onClick = { onSelect(avatar.copy(hair = index)) },
                ) {
                    CompanionAvatar(
                        config = avatar.copy(hair = index, companion = 0, frame = 1),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            StudioCategory.HAIR_COLOR -> itemsIndexed(AvatarCatalog.hairColors) { index, color ->
                OptionCell(
                    selected = avatar.hairColor == index,
                    onClick = { onSelect(avatar.copy(hairColor = index)) },
                ) { Box(modifier = Modifier.fillMaxSize().background(color)) }
            }
            StudioCategory.GENDER, StudioCategory.PET -> Unit
        }
    }
}

// ── Shared bits ────────────────────────────────────────────────────────

private fun categoryLabel(category: StudioCategory, s: AppStrings): String = when (category) {
    StudioCategory.GENDER -> s.studioCatGender
    StudioCategory.EXPRESSION -> s.studioCatExpression
    StudioCategory.OUTFIT -> s.studioCatOutfit
    StudioCategory.PET -> s.studioCatCompanion
    StudioCategory.FRAME -> s.studioCatFrame
    StudioCategory.SKIN -> s.studioCatSkin
    StudioCategory.FACE -> s.studioCatFace
    StudioCategory.EYES -> s.studioCatEyes
    StudioCategory.MOUTH -> s.studioCatMouth
    StudioCategory.HAIR -> s.studioCatHair
    StudioCategory.HAIR_COLOR -> s.studioCatHairColor
}

/** Human-readable description of an unlock task, e.g. "Check in 3 days in a row". */
private fun taskLabel(task: CompanionTask, s: AppStrings): String = when (task.type) {
    CompanionTaskType.COMPLETE_ALL_TODAY -> s.studioTaskCompleteAllToday
    CompanionTaskType.CHECKIN_STREAK -> String.format(s.studioTaskCheckinStreak, task.target)
    CompanionTaskType.FULL_STREAK -> String.format(s.studioTaskFullStreak, task.target)
    CompanionTaskType.TOTAL_CHECKINS -> String.format(s.studioTaskTotalCheckins, task.target)
    CompanionTaskType.REFLECTIONS_WRITTEN -> String.format(s.studioTaskReflections, task.target)
    CompanionTaskType.MOODS_LOGGED -> String.format(s.studioTaskMoods, task.target)
}

/** Localized display name for a newly unlocked outfit/pet/expression id. */
private fun unlockDisplayName(id: String, s: AppStrings): String {
    AvatarCatalog.outfits.firstOrNull { it.id == id }?.let {
        return s.nameFor(AvatarCatalog.outfitNameKey(id)).ifEmpty { id }
    }
    AvatarCatalog.pets.firstOrNull { it.id == id }?.let {
        return s.nameFor(AvatarCatalog.petNameKey(id)).ifEmpty { id }
    }
    AvatarCatalog.expressions.firstOrNull { it.id == id }?.let {
        return s.nameFor(AvatarCatalog.expressionNameKey(id)).ifEmpty { id }
    }
    return id
}

/**
 * One circular option in the Studio: selection ring, optional padlock shade
 * with a tiny unlock label, and a gold star badge marking task-earned
 * exclusives (visible locked or unlocked).
 */
@Composable
private fun OptionCell(
    selected: Boolean,
    onClick: () -> Unit,
    locked: Boolean = false,
    exclusive: Boolean = false,
    lockLabel: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize().alpha(if (locked) 0.55f else 1f)) {
            content()
        }
        if (locked) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = Color(0xFFFFFCF5),
                        modifier = Modifier.size(16.dp),
                    )
                    if (lockLabel != null) {
                        Text(
                            text = lockLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFFCF5),
                        )
                    }
                }
            }
        }
        if (exclusive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 8.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4C3A1E).copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color(0xFFF3CB63),
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}
