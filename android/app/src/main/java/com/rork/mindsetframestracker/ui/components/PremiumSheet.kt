package com.rork.mindsetframestracker.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/**
 * Huawei AppGallery listing for Mindset Frames. The `appmarket://` deep link
 * opens the AppGallery app directly on Huawei devices; the web URL is the
 * fallback for devices without AppGallery installed.
 *
 * Until the app is published, [hasListing] is false and the premium sheet
 * shows an in-app "coming soon" note instead of any external link — the free
 * tier never exposes a broken or dead upgrade URL.
 *
 * TODO: After the app is published, replace [APP_GALLERY_APP_ID] with the
 * real AppGallery app id (looks like "C123456789") from AppGallery Connect.
 */
object AppGalleryLink {
    private const val PACKAGE_NAME = "com.mindsetframes.habittracker"

    /** AppGallery app id, e.g. "C123456789". Blank until the app is published. */
    private const val APP_GALLERY_APP_ID = ""

    /** True once the app is live on AppGallery and the listing id is set. */
    val hasListing: Boolean
        get() = APP_GALLERY_APP_ID.isNotBlank()

    /** Opens the Mindset Frames listing on Huawei AppGallery. */
    fun open(context: Context) {
        if (!hasListing) return
        val market = Intent(Intent.ACTION_VIEW, "appmarket://details?id=$PACKAGE_NAME".toUri())
        val opened = runCatching { context.startActivity(market) }.isSuccess
        if (!opened) {
            val web = "https://appgallery.huawei.com/app/$APP_GALLERY_APP_ID"
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, web.toUri())) }
        }
    }
}

/**
 * Premium upgrade sheet — lists everything included in Mindset Frames
 * Premium and routes the upgrade through the Huawei AppGallery listing.
 * Premium stays locked in-app until the entitlement is granted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val huaweiRed = Color(0xFFC7000B)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.WorkspacePremium,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Text(
                    text = "Mindset Frames Premium",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = "Unlock the full experience — deeper practice, richer insights, and your style everywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PremiumBenefitRow(
                    icon = Icons.Outlined.Psychology,
                    title = "Extended prompt packs",
                    description = "Deeper daily reflective prompts beyond the free pack, tuned to your mood.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Exclusive quote library",
                    description = "The full curated quote collection, hand-picked for each mindset frame.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.Insights,
                    title = "Advanced weekly insights",
                    description = "Completion rate, best day, and most consistent habit in your weekly review.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.Palette,
                    title = "12 exclusive accent themes",
                    description = "Sunrise, Forest, Lullaby, Sakura, Ocean, Lavender, Honey, Berry, Mint Candy, Peach, Midnight, and Rosewood.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.Translate,
                    title = "All 26 languages",
                    description = "Unlock every world language beyond your two free ones — English (US & UK) plus your region's language.",
                )
                PremiumBenefitRow(
                    icon = Icons.Filled.CheckCircle,
                    title = "Unlimited habits",
                    description = "Track as many habits as you like — the 5-habit free cap disappears.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.PictureAsPdf,
                    title = "PDF progress reports",
                    description = "Print-ready monthly or custom-range reports — stats, charts, and habit breakdowns.",
                )
            }

            if (AppGalleryLink.hasListing) {
                Button(
                    onClick = { AppGalleryLink.open(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = huaweiRed,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .defaultMinSize(minHeight = 52.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "H",
                            color = huaweiRed,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "Get Premium on AppGallery",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Text(
                    text = "Premium is unlocked through the Mindset Frames listing on Huawei AppGallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            } else {
                // Not published yet — no external link at all, so the free tier
                // never routes anyone to a dead upgrade page.
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = "Coming soon to AppGallery",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Premium will unlock through the official Mindset Frames " +
                                "listing on Huawei AppGallery once it goes live. Everything " +
                                "you track now stays yours — free, forever.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .defaultMinSize(minHeight = 48.dp),
            ) { Text("Maybe later") }
        }
    }
}

/** A single benefit row inside the premium sheet. */
@Composable
fun PremiumBenefitRow(
    icon: ImageVector,
    title: String,
    description: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
