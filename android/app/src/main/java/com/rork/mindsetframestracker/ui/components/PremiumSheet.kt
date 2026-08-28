package com.rork.mindsetframestracker.ui.components

import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
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
 * Premium upgrade sheet — lists everything included in Mindset Frames
 * Premium and starts the native Huawei IAP subscription purchase directly
 * (works for update builds and sandbox testers before the public listing).
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

            // Direct Huawei IAP subscription purchase — sandbox test accounts
            // see the sandbox payment sheet and are never charged.
            Button(
                onClick = {
                    val act = activity ?: return@Button
                    purchaseError = null
                    onPurchaseStarted("mindset_premium_monthly")
                    SubscriptionBilling.purchase(act, "mindset_premium_monthly") { message ->
                        purchaseError = message
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = huaweiRed,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .defaultMinSize(minHeight = 52.dp),
            ) {
                Text(
                    text = "Go Premium — Monthly",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = {
                    val act = activity ?: return@Button
                    purchaseError = null
                    onPurchaseStarted("mindset_premium_yearly")
                    SubscriptionBilling.purchase(act, "mindset_premium_yearly") { message ->
                        purchaseError = message
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .defaultMinSize(minHeight = 52.dp),
            ) {
                Text(
                    text = "Go Premium — Yearly (best value)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
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

            if (onRestore != null) {
                TextButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
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
