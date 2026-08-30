package com.rork.mindsetframestracker.ui.components

import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.rork.mindsetframestracker.billing.SubscriptionBilling

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
 * Premium upgrade sheet — polished with a clear Free vs Premium comparison
 * table, feature breakdown by tier, and native Huawei IAP purchase buttons.
 *
 * [onPurchaseStarted] must record the product id in the ViewModel so
 * MainActivity.onActivityResult can attribute the purchase result;
 * [onRestore] triggers an owned-purchases query ("Restore purchase").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumSheet(
    onDismiss: () -> Unit,
    onPurchaseStarted: (String) -> Unit = {},
    onRestore: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val activity = LocalActivity.current
    val huaweiRed = Color(0xFFC7000B)
    var purchaseError by remember { mutableStateOf<String?>(null) }

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
            // ── Header ──────────────────────────────────────────────
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

            // ── Free vs Premium comparison ──────────────────────────
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Feature",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1.4f),
                        )
                        Text(
                            text = "Free",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(0.8f),
                        )
                        Text(
                            text = "Premium",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(0.8f),
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    ComparisonRow("Habits", "Up to 5", "Unlimited")
                    ComparisonRow("Daily prompts", "Basic", "Extended packs")
                    ComparisonRow("Quote library", "Limited", "Full curated")
                    ComparisonRow("Weekly insights", "Simple", "Advanced stats")
                    ComparisonRow("Accent themes", "1 (Terracotta)", "All 13")
                    ComparisonRow("Languages", "2", "All 26")
                    ComparisonRow("PDF reports", blocked = true, premium = "Full export")
                    ComparisonRow("AI suggestions", blocked = true, premium = "Gemini-powered")
                    ComparisonRow("Strava sync", blocked = true, premium = "Auto-import")
                    ComparisonRow("Polar / Health Connect", free = "Included", premium = "Included")
                    ComparisonRow("Cloud backup", free = "Included", premium = "Included")
                    ComparisonRow("Ads", free = "None", premium = "None")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Feature highlights ──────────────────────────────────
            Text(
                text = "Everything in Premium",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PremiumBenefitRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Unlimited habits & exclusive quotes",
                    description = "Track as many habits as you like. The full curated quote collection, hand-picked for each mindset frame.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.Psychology,
                    title = "Extended prompt packs",
                    description = "Deeper daily reflective prompts beyond the free pack, tuned to your mood.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.Insights,
                    title = "Advanced weekly insights",
                    description = "Completion rate, best day, most consistent habit, and trend analysis in your weekly review.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.SmartToy,
                    title = "AI-powered suggestions",
                    description = "Gemini AI suggests habits and daily to-dos based on your mood, existing routine, and fitness data.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.FitnessCenter,
                    title = "Strava sync",
                    description = "Auto-import runs, rides, and walks from Strava to automatically complete activity habits.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.Palette,
                    title = "13 accent themes & all 26 languages",
                    description = "Sunrise, Forest, Sakura, Ocean, and 9 more. Every world language unlocked.",
                )
                PremiumBenefitRow(
                    icon = Icons.Outlined.PictureAsPdf,
                    title = "PDF progress reports",
                    description = "Print-ready monthly or custom-range reports — stats, charts, and habit breakdowns.",
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Purchase buttons ────────────────────────────────────
            Button(
                onClick = {
                    val act = activity ?: return@Button
                    purchaseError = null
                    onPurchaseStarted("mindset_premium_yearly")
                    SubscriptionBilling.purchase(act, "mindset_premium_yearly") { message ->
                        purchaseError = message
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = huaweiRed,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Go Premium — Yearly",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Best value — save over 40%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Button(
                onClick = {
                    val act = activity ?: return@Button
                    purchaseError = null
                    onPurchaseStarted("mindset_premium_monthly")
                    SubscriptionBilling.purchase(act, "mindset_premium_monthly") { message ->
                        purchaseError = message
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .defaultMinSize(minHeight = 52.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Go Premium — Monthly",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            purchaseError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }

            Text(
                text = "Billed through your Huawei ID on AppGallery. Cancel anytime in " +
                    "AppGallery > Me > Payments and purchases > Subscriptions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )

            // ── Always-free callout ─────────────────────────────────
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Always free, always yours",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "Core tracker, Polar & Health Connect sync, cloud backup, daily reminders, streak protection, companion studio, and grounding exercises are free forever. No ads anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (onRestore != null) {
                TextButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .defaultMinSize(minHeight = 44.dp),
                ) { Text("Restore purchase") }
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

/** One comparison row in the Free vs Premium table. */
@Composable
private fun ComparisonRow(
    feature: String,
    free: String? = null,
    premium: String,
    blocked: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = feature,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.4f),
        )
        if (blocked) {
            Icon(
                imageVector = Icons.Outlined.Block,
                contentDescription = "Not included",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .weight(0.8f)
                    .size(16.dp),
            )
        } else {
            Text(
                text = free ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.8f),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.weight(0.8f),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = premium,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 3.dp),
                maxLines = 1,
            )
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
