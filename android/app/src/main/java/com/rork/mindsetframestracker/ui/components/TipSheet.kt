package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rork.mindsetframestracker.billing.TipBilling
import com.rork.mindsetframestracker.billing.TipProduct

/**
 * Tip sheet — polished to match the PremiumSheet conventions (icon header,
 * Material 3 bottom sheet, full-width buttons) and driven by the real
 * localized prices Huawei reports for the signed-in account/region.
 *
 * Prices are fetched via [TipBilling.fetchTipProducts] when the sheet opens.
 * While loading, the buttons show a small progress indicator and are
 * disabled; if the price query fails the sheet falls back to the built-in
 * labels so the user can still tip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipSheet(
    onDismiss: () -> Unit,
    onSendTip: (String) -> Unit, // Passes the selected Product ID ("tip_small", "tip_medium", "tip_large")
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    var products by remember { mutableStateOf<Map<String, TipProduct>>(emptyMap()) }
    var pricesLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        TipBilling.fetchTipProducts(context) { result ->
            products = result
            pricesLoaded = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Text(
                text = "Support App Development",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "If you enjoy tracking your mindset frames, consider leaving a small tip to help keep updates rolling!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )

            // Tip Tier Buttons mapped to your Huawei IAP Product IDs.
            // Prices come from Huawei's obtainProductInfo so they match the
            // account's region/currency; the built-in labels are the fallback
            // when the query fails (offline, HMS not ready).

            // Shown only when the store answered but none of our consumable
            // ids exist in it yet (e.g. the tip products have not been added
            // in AppGallery Connect) — the reference prices below would
            // otherwise look authoritative when they are not.
            if (pricesLoaded && products.isEmpty()) {
                Text(
                    text = "Showing reference prices — open AppGallery once so we can " +
                        "read your local prices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            TipTierButton(
                productId = "tip_small",
                products = products,
                pricesLoaded = pricesLoaded,
                fallbackLabel = "☕ Small Tip",
                fallbackPrice = "$1.00",
                onClick = { onSendTip("tip_small") },
            )

            Spacer(modifier = Modifier.height(8.dp))

            TipTierButton(
                productId = "tip_medium",
                products = products,
                pricesLoaded = pricesLoaded,
                fallbackLabel = "🚀 Medium Tip",
                fallbackPrice = "$3.00",
                onClick = { onSendTip("tip_medium") },
            )

            Spacer(modifier = Modifier.height(8.dp))

            TipTierButton(
                productId = "tip_large",
                products = products,
                pricesLoaded = pricesLoaded,
                fallbackLabel = "👑 Large Tip",
                fallbackPrice = "$5.00",
                onClick = { onSendTip("tip_large") },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Same billing disclosure the upgrade sheet carries, so the two
            // purchase surfaces read as one product rather than two.
            Text(
                text = "Billed through your Huawei ID on AppGallery. A tip is a one-off " +
                    "purchase and never auto-renews.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Maybe later")
            }
        }
    }
}

/** One tip tier button — shows the Huawei-reported price when available. */
@Composable
private fun TipTierButton(
    productId: String,
    products: Map<String, TipProduct>,
    pricesLoaded: Boolean,
    fallbackLabel: String,
    fallbackPrice: String,
    onClick: () -> Unit,
) {
    val product = products[productId]
    val priceText = product?.price?.takeIf { it.isNotBlank() } ?: fallbackPrice
    val label = product?.label ?: fallbackLabel

    // Deliberately ALWAYS enabled. The button used to be gated on
    // `product != null`, which disabled every tier whenever Huawei's price
    // query came back empty — and that query returns empty precisely when the
    // price lookup is unavailable (offline, HMS not ready, IAP not activated).
    // That turned a cosmetic price-lookup failure into "you cannot tip at
    // all". The price is a label here, not a precondition: the purchase itself
    // never needs it, and the built-in fallback price below covers display.
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (!pricesLoaded) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.size(8.dp))
            }
            Text("$label ($priceText)")
        }
    }
}