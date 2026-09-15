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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.rork.mindsetframestracker.data.SupabaseSync

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
 * Whether the Founding Member card may render at all.
 *
 * [Checking] is deliberately distinct from [Unavailable] so the card starts
 * hidden *before* the network call resolves, instead of flashing on for a
 * frame and then disappearing once the answer arrives.
 */
private enum class FoundingCardState {
    /** Eligibility call in flight — hidden until we actually know. */
    Checking,

    /** Server confirmed a founding slot is still free for this install. */
    Eligible,

    /** Sold out, or this install already claimed a slot. */
    NotEligible,

    /** Check could not be completed — fail closed, stay hidden. */
    Unavailable,
}

// ── Products ───────────────────────────────────────────────────────────────
// Live AppGallery product ids. Keep in step with SubscriptionBilling.KNOWN_
// PRODUCT_IDS and Entitlements.tierForProductId.
private const val PRODUCT_REGULAR_YEARLY = "mindset_premium_yearly"
private const val PRODUCT_REGULAR_MONTHLY = "mindset_premium_monthly"
private const val PRODUCT_FOUNDING_YEARLY = "mindset_premium_founding_yearly"
private const val PRODUCT_FOUNDING_MONTHLY = "mindset_premium_founding_monthly"

/**
 * Founding-member target per country/region. Display fallback only, used when
 * the server did not report a cap back — the server owns the real number
 * (founding_member_cap_for_region(), default 500), and it is the server's
 * answer that decides whether the card renders at all.
 */
private const val DEFAULT_FOUNDING_TARGET = 500

/**
 * USD anchors used only when the AppGallery price query cannot answer, so the
 * row is never blank. Mirrors web/src/lib/pricing.ts. The store price from
 * SubscriptionBilling.queryPrices() always wins when it is available, because
 * that is the number the buyer is actually charged.
 */
private const val ANCHOR_REGULAR_YEARLY = "\$34.99"
private const val ANCHOR_REGULAR_MONTHLY = "\$4.99"

/** Store price when AppGallery answered, else the marketing anchor. */
private fun priceLabel(storePrices: Map<String, String>, productId: String, anchor: String): String =
    storePrices[productId]?.takeIf { it.isNotBlank() } ?: anchor

/**
 * Premium upgrade sheet — polished with a clear Free vs Premium comparison
 * table, feature breakdown by tier, and native Huawei IAP purchase buttons.
 *
 * [onPurchaseStarted] must record the product id in the ViewModel so
 * MainActivity.onActivityResult can attribute the purchase result;
 * [onRestore] triggers an owned-purchases query ("Restore purchase").
 *
 * [foundingEligibility] gates the Founding Member block. Unless it resolves to
 * a positive "eligible" answer the block is not rendered at all, and only the
 * two regular premium plans are offered. Passing null is safe — it is treated
 * exactly like a failed check (card hidden).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumSheet(
    onDismiss: () -> Unit,
    onPurchaseStarted: (String) -> Unit = {},
    onRestore: (() -> Unit)? = null,
    foundingEligibility: (suspend () -> SupabaseSync.FoundingEligibility)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val activity = LocalActivity.current
    val huaweiRed = Color(0xFFC7000B)
    var purchaseError by remember { mutableStateOf<String?>(null) }

    // ── Founding Member gate ───────────────────────────────────────────────
    // This endpoint is the ONLY gate on the Founding Member block. It used to
    // render unconditionally behind a hardcoded "first 100 only" line, so the
    // last buyer was shown a button that could never be honoured. The cap is
    // now enforced server-side PER COUNTRY/REGION (500 by default) by the
    // founding-member-eligibility Edge Function, and the sheet offers the card
    // only when that endpoint positively says a slot is still free in the
    // caller's own region.
    //
    // Fail-closed by construction: every path that is not a positive
    // [FoundingCardState.Eligible] — sold out, already claimed, cloud sync
    // unconfigured, offline, non-2xx, thrown exception, null probe — leaves the
    // card hidden. Failing open would let someone pay for a tier that no longer
    // exists, which is far worse than briefly hiding an offer.
    //
    // The probe is read through rememberUpdatedState and the effect is keyed on
    // Unit, so it runs exactly once per sheet open. Keying the effect on the
    // lambda itself would restart the network call on every recomposition,
    // because the callers pass a fresh lambda instance each time.
    var foundingCard by remember { mutableStateOf(FoundingCardState.Checking) }
    // Per-region target + how many are left in THIS region, as reported by the
    // server. Both are shown on the card, so the "first N" copy is the real
    // number for the user's own country rather than a hardcoded global figure.
    var foundingCap by remember { mutableStateOf(DEFAULT_FOUNDING_TARGET) }
    var foundingRemaining by remember { mutableStateOf(0) }
    // Live AppGallery prices keyed by product id. Empty until the store answers.
    var storePrices by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val currentProbe by rememberUpdatedState(foundingEligibility)
    LaunchedEffect(Unit) {
        val probe = currentProbe
        if (probe == null) {
            foundingCard = FoundingCardState.Unavailable
            return@LaunchedEffect
        }
        foundingCard = runCatching { probe() }
            .map { result ->
                when (result) {
                    is SupabaseSync.FoundingEligibility.Eligible -> {
                        foundingCap = result.cap.takeIf { it > 0 } ?: DEFAULT_FOUNDING_TARGET
                        foundingRemaining = result.remaining
                        FoundingCardState.Eligible
                    }
                    is SupabaseSync.FoundingEligibility.NotEligible -> {
                        foundingCap = result.cap.takeIf { it > 0 } ?: DEFAULT_FOUNDING_TARGET
                        foundingRemaining = result.remaining
                        FoundingCardState.NotEligible
                    }
                    is SupabaseSync.FoundingEligibility.Unavailable -> FoundingCardState.Unavailable
                }
            }
            .getOrElse { FoundingCardState.Unavailable }
    }

    // Localized prices for every product this sheet can offer. AGC prices each
    // product in the buyer's own currency, so this is the number the user is
    // actually charged — always preferable to the hardcoded anchor above. It
    // matters most on the regular plans: once a region's founding target is met
    // the sheet falls back to them, and they must show the real regular price.
    LaunchedEffect(Unit) {
        storePrices = when (
            val result = SubscriptionBilling.queryPrices(
                context,
                listOf(
                    PRODUCT_REGULAR_YEARLY,
                    PRODUCT_REGULAR_MONTHLY,
                    PRODUCT_FOUNDING_YEARLY,
                    PRODUCT_FOUNDING_MONTHLY,
                ),
            )
        ) {
            is SubscriptionBilling.PriceResult.Available -> result.prices
            SubscriptionBilling.PriceResult.Unavailable -> emptyMap()
        }
    }

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
            // ── Header ─────────────────────────────────────────────────────
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

            // ── Free vs Premium comparison ─────────────────────────────────
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

            // ── Feature highlights ─────────────────────────────────────────
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

            // ── Plan cards ───────────────────────────────────────────────────
            // MUTUALLY EXCLUSIVE, resolved per country/region.
            //
            // The server owns the cap (500 per region by default) and is the
            // only thing that decides which card this user is allowed to buy:
            //
            //   • region still has slots → Founding Member card ONLY, at the
            //     founding price, with the regular price struck through. The
            //     regular card is not rendered at all, so the two are never
            //     offered side by side.
            //   • region has filled up  → regular card ONLY, at the regular
            //     price. The founding card disappears entirely rather than
            //     being disabled — there is no tier left to sell.
            //   • check unresolved      → a neutral placeholder with NO
            //     purchase button. Selling before the answer arrives risks
            //     charging for a tier that no longer exists in this region.
            //
            // Whichever card is on screen, the upgrade button routes through
            // the Huawei IAP flow (SubscriptionBilling.purchase) for the
            // product id matching the region's state — founding while the
            // region has slots, regular once it is full.
            val regularYearly = priceLabel(storePrices, PRODUCT_REGULAR_YEARLY, ANCHOR_REGULAR_YEARLY)
            val regularMonthly = priceLabel(storePrices, PRODUCT_REGULAR_MONTHLY, ANCHOR_REGULAR_MONTHLY)
            val foundingYearly = storePrices[PRODUCT_FOUNDING_YEARLY].orEmpty()
            val foundingMonthly = storePrices[PRODUCT_FOUNDING_MONTHLY].orEmpty()

            // One place that starts a purchase, wherever it is triggered from:
            // remember the product id so MainActivity.onActivityResult can
            // attribute the result, clear any previous error, and hand the
            // IAP flow to SubscriptionBilling.
            fun buy(productId: String) {
                val act = activity
                if (act == null) {
                    purchaseError = "Open the app on your device to complete the purchase."
                    return
                }
                purchaseError = null
                onPurchaseStarted(productId)
                SubscriptionBilling.purchase(act, productId) { message ->
                    purchaseError = message
                }
            }

            when (foundingCard) {
                FoundingCardState.Eligible -> FoundingPlanCard(
                    cap = foundingCap,
                    remaining = foundingRemaining,
                    monthlyPrice = foundingMonthly.ifBlank { regularMonthly },
                    yearlyPrice = foundingYearly.ifBlank { regularYearly },
                    regularMonthlyPrice = regularMonthly,
                    regularYearlyPrice = regularYearly,
                    onPurchaseYearly = { buy(PRODUCT_FOUNDING_YEARLY) },
                    onPurchaseMonthly = { buy(PRODUCT_FOUNDING_MONTHLY) },
                )

                // Sold out, already claimed, probe failed, or cloud sync not
                // configured — the founding tier is not sellable in this
                // region, so the regular plans are the only offer. Treating
                // Unavailable the same as NotEligible keeps a user who is
                // offline able to buy something, while still never showing a
                // founding card we cannot honour.
                FoundingCardState.NotEligible,
                FoundingCardState.Unavailable,
                -> RegularPlanCard(
                    monthlyPrice = regularMonthly,
                    yearlyPrice = regularYearly,
                    onPurchaseYearly = { buy(PRODUCT_REGULAR_YEARLY) },
                    onPurchaseMonthly = { buy(PRODUCT_REGULAR_MONTHLY) },
                )

                // Still asking the server. No buttons until we know which tier
                // this region is entitled to.
                FoundingCardState.Checking -> PlanSlotPlaceholder()
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

            // ── Always-free callout ────────────────────────────────────────
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