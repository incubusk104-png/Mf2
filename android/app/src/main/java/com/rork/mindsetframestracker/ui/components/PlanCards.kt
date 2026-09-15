package com.rork.mindsetframestracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * The two plan cards at the top of the premium sheet, plus the locked-card
 * placeholder for the same slot.
 *
 * They are split into one file because they are the SAME decision rendered
 * three ways — founding available, founding sold out, or not yet resolved —
 * and the whole point of the show/hide rule is that exactly one of them is on
 * screen at a time. Keeping them together makes that exclusivity visible in
 * one place instead of scattered through the sheet body.
 */

/** A price line: the big number plus an optional struck-through reference. */
@Composable
private fun PriceLine(
    price: String,
    strike: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    compact: Boolean = false,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = price,
            style = if (compact) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.headlineMedium
            },
            fontWeight = FontWeight.Bold,
            color = tint,
        )
        if (!strike.isNullOrBlank()) {
            Text(
                text = strike,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
        }
    }
}

/** A small badge in the card's top-right corner. */
@Composable
private fun CardBadge(text: String, container: Color, content: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** One bullet line inside a plan card. */
@Composable
private fun PlanBullet(text: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * The Founding Member card — shown ONLY while the caller's region still has
 * slots left.
 *
 * The regular plans are deliberately NOT rendered while this card is on screen,
 * so the two are mutually exclusive (see [PremiumSheet]): a user in a region
 * that is not yet full is offered the founding price and nothing else. The
 * regular price is still shown, but struck through, so the saving is legible
 * without offering a second, more expensive button beside it.
 */
@Composable
fun FoundingPlanCard(
    cap: Int,
    remaining: Int,
    monthlyPrice: String,
    yearlyPrice: String,
    regularMonthlyPrice: String,
    regularYearlyPrice: String,
    onPurchaseMonthly: () -> Unit,
    onPurchaseYearly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.WorkspacePremium,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Founding Member",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                CardBadge(
                    text = "Locked for life",
                    container = accent,
                    content = MaterialTheme.colorScheme.onPrimary,
                )
            }

            Text(
                text = if (remaining > 0) {
                    "$remaining of $cap left in your region — this price is yours forever."
                } else {
                    "The last of the first $cap in your region — this price is yours forever."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Yearly is the headline offer: founding price, regular struck out.
            PriceLine(
                price = yearlyPrice,
                strike = regularYearlyPrice.takeIf { it.isNotBlank() },
                tint = accent,
            )
            Text(
                text = "per year",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(10.dp))

            PlanBullet("Everything in Premium, permanently discounted", accent)
            PlanBullet("Unlimited habits, all quotes, extended prompts", accent)
            PlanBullet("Advanced weekly insights and PDF reports", accent)
            PlanBullet("Your rate never rises, even after the offer ends", accent)

            Spacer(modifier = Modifier.height(16.dp))

            PrimaryPlanButton(
                title = "Get Founding Member",
                subtitle = "$yearlyPrice \u00b7 yearly",
                emphasized = true,
                onClick = onPurchaseYearly,
            )

            Spacer(modifier = Modifier.height(8.dp))

            SecondaryPlanButton(
                title = "Founding Member — Monthly",
                subtitle = monthlyPrice.takeIf { it.isNotBlank() },
                onClick = onPurchaseMonthly,
            )
        }
    }
}

/**
 * The regular premium card. Shown alone once the caller's region has filled its
 * founding target, and as the only option while eligibility is unresolved.
 */
@Composable
fun RegularPlanCard(
    monthlyPrice: String,
    yearlyPrice: String,
    onPurchaseMonthly: () -> Unit,
    onPurchaseYearly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Premium",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Everything unlocked, billed through your Huawei ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            PriceLine(price = yearlyPrice, tint = accent)
            Text(
                text = "per year \u00b7 best value",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(10.dp))

            PlanBullet("Unlimited habits & exclusive quotes", accent)
            PlanBullet("Extended prompt packs", accent)
            PlanBullet("Advanced weekly insights & PDF reports", accent)
            PlanBullet("AI suggestions and Strava sync", accent)

            Spacer(modifier = Modifier.height(16.dp))

            PrimaryPlanButton(
                title = "Go Premium — Yearly",
                subtitle = "$yearlyPrice \u00b7 best value",
                emphasized = true,
                onClick = onPurchaseYearly,
            )

            Spacer(modifier = Modifier.height(8.dp))

            SecondaryPlanButton(
                title = "Go Premium — Monthly",
                subtitle = monthlyPrice.takeIf { it.isNotBlank() },
                onClick = onPurchaseMonthly,
            )
        }
    }
}

/**
 * Placeholder occupying the plan slot while the region check is in flight.
 *
 * It deliberately offers NO purchase button: the sheet must not sell a tier
 * before it knows which tier the user's region is entitled to.
 */
@Composable
fun PlanSlotPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = "Checking your region's pricing\u2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Filled, full-width call to action used for the headline plan. */
@Composable
private fun PrimaryPlanButton(
    title: String,
    subtitle: String?,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (emphasized) HUAWEI_RED else MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Outlined, full-width secondary option beside the headline plan. */
@Composable
private fun SecondaryPlanButton(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Huawei brand red, shared by the plan buttons and the sheet header. */
internal val HUAWEI_RED = Color(0xFFC7000B)
