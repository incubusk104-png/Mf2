package com.rork.mindsetframestracker.ui.screens

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.mindsetframestracker.BuildConfig
import com.rork.mindsetframestracker.R
import com.rork.mindsetframestracker.ui.theme.LocalMoodTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Brand splash: a soft accent glow blooms behind the logo, the logo settles
 * in with a gentle spring, then the wordmark and tagline drift up. Total
 * time on screen stays ~1.4s (400ms with reduced motion).
 */
@Composable
fun SplashScreen(
    reducedMotion: Boolean,
    onFinished: () -> Unit,
) {
    val moodTheme = LocalMoodTheme.current
    val logoScale = remember { Animatable(0.72f) }
    val logoAlpha = remember { Animatable(0f) }
    val glowAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val textOffset = remember { Animatable(18f) }

    LaunchedEffect(Unit) {
        try {
            if (reducedMotion) {
                logoScale.snapTo(1f)
                logoAlpha.snapTo(1f)
                glowAlpha.snapTo(1f)
                textAlpha.snapTo(1f)
                textOffset.snapTo(0f)
                delay(400L)
            } else {
                launch {
                    glowAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    logoAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    logoScale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                    )
                }
                delay(300L)
                launch {
                    textAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    textOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
                    )
                }
                delay(1100L)
            }
        } catch (e: Exception) {
            // Animation failure must never block navigation past the splash.
            if (BuildConfig.DEBUG) Log.w("SplashScreen", "Animation failed: ${e.message}")
        }
        onFinished()
    }

    val glowColor = moodTheme.accent

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                if (size.width <= 0f || size.height <= 0f) return@drawBehind
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.22f * glowAlpha.value),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.42f),
                        radius = (size.width * 0.78f).coerceAtLeast(1f),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.brand_logo),
                contentDescription = "Mindset Frames logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(148.dp)
                    .graphicsLayer {
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                        alpha = logoAlpha.value
                    }
                    .clip(RoundedCornerShape(32.dp)),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha.value
                    translationY = textOffset.value * density
                },
            ) {
                Text(
                    text = "Mindset Frames",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    text = "ONE FRAME AT A TIME",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.4.sp,
                    color = moodTheme.accent,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
