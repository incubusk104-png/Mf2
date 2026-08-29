package com.rork.mindsetframestracker.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.huawei.hms.iap.Iap
import com.huawei.hms.iap.entity.ConsumeOwnedPurchaseReq
import com.huawei.hms.iap.entity.PurchaseIntentReq
import org.json.JSONObject

sealed class TipPurchaseResult {
    data class Success(val purchaseData: String, val signature: String) : TipPurchaseResult()
    data object Cancelled : TipPurchaseResult()
    data class Error(val message: String) : TipPurchaseResult()
}

/**
 * Native Huawei IAP client for the tip feature.
 *
 * IMPORTANT: purchase() launches the payment sheet via the classic
 * Activity.startActivityForResult(requestCode) path — status.startResolutionForResult()
 * — NOT the Compose ActivityResultContracts.StartIntentSenderForResult
 * launcher. An earlier version tried to extract status.resolution as a
 * PendingIntent/IntentSender manually; in practice Huawei's IAP Status can
 * report hasResolution()=true with resolution=null even on a successful
 * (code 0) status, which silently broke the purchase flow. This is
 * Huawei's own documented pattern for IAP specifically — mirror it exactly,
 * the same way HuaweiAuthClient does for sign-in.
 *
 * Because of this, the result must be caught in MainActivity.onActivityResult
 * (see PURCHASE_REQUEST_CODE), not in a Composable launcher.
 *
 * ## Consumable lifecycle
 *
 * Tips are consumable products (priceType = 0). After a successful purchase,
 * the product MUST be consumed via [consumePurchase] so that:
 * 1. The user can buy the same tip again in the future.
 * 2. Huawei's system does not flag the product as "already owned".
 *
 * [handlePurchaseResult] automatically consumes on success.
 */
object TipBilling {

    private const val TAG = "TipBilling"

    /** Request code for the HMS IAP purchase intent — handled in MainActivity.onActivityResult. */
    const val PURCHASE_REQUEST_CODE = 8889

    fun purchase(
        activity: Activity,
        productId: String,
        onError: (String) -> Unit,
    ) {
        try {
            val req = PurchaseIntentReq().apply {
                priceType = 0 // 0 = consumable product (tips)
                this.productId = productId
                reservedInfor = "mindset_frames_tip"
            }

            Iap.getIapClient(activity).createPurchaseIntent(req)
                .addOnSuccessListener { result ->
                    val status = result.status
                    Log.i(
                        TAG,
                        "createPurchaseIntent result for $productId — " +
                            "statusCode=${status.statusCode}, message=${status.statusMessage}",
                    )
                    if (status.hasResolution()) {
                        try {
                            status.startResolutionForResult(activity, PURCHASE_REQUEST_CODE)
                        } catch (e: IntentSender.SendIntentException) {
                            Log.e(TAG, "startResolutionForResult failed for $productId", e)
                            onError("Could not open the payment sheet. Try again.")
                        }
                    } else {
                        onError(
                            "Payment unavailable (code ${status.statusCode}): " +
                                "${status.statusMessage ?: "no details"}",
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "createPurchaseIntent failed for $productId", e)
                    onError(e.message ?: "Unknown billing error")
                }
        } catch (e: Exception) {
            Log.e(TAG, "createPurchaseIntent threw synchronously for $productId", e)
            onError(e.message ?: "Could not start the purchase. Try again.")
        }
    }

    /** Call from Activity.onActivityResult when requestCode == PURCHASE_REQUEST_CODE. */
    fun handlePurchaseResult(
        context: Context,
        data: Intent?,
        onResult: (TipPurchaseResult) -> Unit,
    ) {
        val purchaseResultInfo = Iap.getIapClient(context).parsePurchaseResultInfoFromIntent(data)
        when (purchaseResultInfo.returnCode) {
            0 -> { // ORDER_STATE_SUCCESS
                // Consume the purchase immediately so it can be re-purchased.
                consumePurchase(context, purchaseResultInfo.inAppPurchaseData)
                onResult(
                    TipPurchaseResult.Success(
                        purchaseResultInfo.inAppPurchaseData,
                        purchaseResultInfo.inAppDataSignature,
                    ),
                )
            }
            -1 -> onResult(TipPurchaseResult.Cancelled) // ORDER_STATE_CANCEL
            else -> onResult(
                TipPurchaseResult.Error("Purchase failed with code: ${purchaseResultInfo.returnCode}"),
            )
        }
    }

    /**
     * Consumes a tip purchase so the same product can be bought again.
     *
     * For consumable products (priceType = 0), Huawei requires calling
     * `consumeOwnedPurchase` after a successful purchase. Without this,
     * the product stays in "owned" state and subsequent purchases fail.
     */
    fun consumePurchase(context: Context, inAppPurchaseData: String?) {
        if (inAppPurchaseData.isNullOrBlank()) {
            Log.w(TAG, "consumePurchase: no purchaseData — skipping")
            return
        }
        val purchaseToken = runCatching {
            JSONObject(inAppPurchaseData).optString("purchaseToken")
        }.getOrNull()

        if (purchaseToken.isNullOrBlank()) {
            Log.w(TAG, "consumePurchase: no purchaseToken in data — skipping")
            return
        }

        val req = ConsumeOwnedPurchaseReq().apply {
            this.purchaseToken = purchaseToken
        }

        Iap.getIapClient(context).consumeOwnedPurchase(req)
            .addOnSuccessListener {
                Log.i(TAG, "Tip consumed successfully (token=$purchaseToken)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "consumeOwnedPurchase failed: ${e.message}", e)
            }
    }

    /**
     * Consumes any un-consumed tip purchases left from previous sessions.
     * Call at app startup to clean up orphaned consumables that were bought
     * but not properly consumed (e.g. process killed between purchase and
     * consume).
     */
    fun consumeUnfinishedPurchases(context: Context) {
        runCatching {
            val req = com.huawei.hms.iap.entity.OwnedPurchasesReq().apply {
                priceType = 0 // consumable
            }
            Iap.getIapClient(context).obtainOwnedPurchases(req)
                .addOnSuccessListener { result ->
                    val dataList = result?.inAppPurchaseDataList.orEmpty()
                    if (dataList.isEmpty()) {
                        Log.i(TAG, "No un-consumed tip purchases found")
                        return@addOnSuccessListener
                    }
                    Log.i(TAG, "Found ${dataList.size} un-consumed tip(s) — consuming now")
                    for (purchaseData in dataList) {
                        consumePurchase(context, purchaseData)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "obtainOwnedPurchases (consumable) failed: ${e.message}")
                }
        }.onFailure {
            Log.w(TAG, "consumeUnfinishedPurchases unavailable: ${it.message}")
        }
    }
}
