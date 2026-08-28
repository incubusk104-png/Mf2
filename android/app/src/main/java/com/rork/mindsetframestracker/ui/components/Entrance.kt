package com.rork.mindsetframestracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One-shot entrance wrapper: content fades in and drifts up slightly,
 * staggered by [index]. Snaps instantly when the active mood (or the
 * reduced-motion setting) disables animation.
 */
@Composable
fun EntranceItem(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val moodTheme = LocalMoodTheme.current
    val offsetY = remember { Animatable(28f) }
    val alphaAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (moodTheme.motion.enabled) {
            delay(index * 70L)
            launch {
                offsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 430, easing = FastOutSlowInEasing),
                )
            }
            launch {
                alphaAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
                )
            }
        } else {
            offsetY.snapTo(0f)
            alphaAnim.snapTo(1f)
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            translationY = offsetY.value * density
            alpha = alphaAnim.value
        },
    ) {
        content()
    }
}
